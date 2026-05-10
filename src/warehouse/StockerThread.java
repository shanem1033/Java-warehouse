package warehouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class StockerThread extends Thread {
    private final String tid;
    private final Warehouse warehouse;
    private final Clock clock;
    private final EventLogger logger;
    private final Random rng;
    private int roundRobinStartIndex;
    private long nextBreakTick;

    public StockerThread(int index, Warehouse warehouse, Clock clock, EventLogger logger, long seed) {
        super("S" + index);
        this.tid = "S" + index;
        this.warehouse = warehouse;
        this.clock = clock;
        this.logger = logger;
        this.rng = new Random(seed);
        this.roundRobinStartIndex = Math.max(0, index - 1);
        this.nextBreakTick = scheduleNextBreak(0);
    }

    @Override
    public void run() {
        try {
            while (warehouse.runningFlag().get() && warehouse.shouldKeepSupplying()) {
                long now = clock.nowTick();
                if (shouldTakeBreak(now)) {
                    logBreakBegin(warehouse.config().stockerBreakDurationTicks);
                    clock.sleepTicks(warehouse.config().stockerBreakDurationTicks);
                    logBreakEnd();
                    // Schedule the next break after this one finishes.
                    nextBreakTick = scheduleNextBreak(clock.nowTick());
                    continue;
                }

                long cutoff = warehouse.simTicks() - warehouse.config().stockerCycleCutoffTicks;
                // Avoid starting a fresh stocking cycle right on the day boundary
                if (warehouse.acceptingNewAttempts() && now >= cutoff) {
                    clock.sleepTicks(1);
                    continue;
                }

                TrolleyPool.AcquireResult acquire = warehouse.trolleyPool()
                        .acquireForStocker(clock, warehouse.runningFlag());
                if (acquire == null) {
                    return;
                }
                Trolley trolley = acquire.trolley;
                logAcquire(trolley.id(), acquire.waitedTicks);

                Map<String, Integer> pickupCaps = buildPickupCaps();
                Map<String, Integer> cargo = warehouse.stagingArea().takeUpTo(
                        warehouse.config().trolleyCapacity,
                        warehouse.sectionTypes(),
                        rng,
                        clock,
                        warehouse.runningFlag(),
                        warehouse::shouldKeepSupplying,
                        pickupCaps);

                int totalLoad = total(cargo);
                trolley.setLoad(totalLoad);
                logStockerLoad(cargo, totalLoad);

                if (totalLoad == 0) {
                    // Empty trolley cycles can happen if staging is empty or all pickup caps are zero.
                    logRelease(trolley.id(), 0);
                    warehouse.trolleyPool().release(trolley);
                    continue;
                }

                String location = "staging";
                while (warehouse.runningFlag().get() && trolley.load() > 0) {
                    String nextSectionType = chooseNextSection(cargo);
                    if (nextSectionType == null) {
                        break;
                    }

                    if (!location.equals(nextSectionType)) {
                        logMove(location, nextSectionType, trolley.load(), trolley.id());
                        clock.sleepTicks(10L + trolley.load());
                        location = nextSectionType;
                    }

                    int amount = cargo.get(nextSectionType);
                    Section section = warehouse.section(nextSectionType);
                    Section.StockSession stockSession = section.beginStocking(amount, warehouse.runningFlag());
                    if (!stockSession.active) {
                        break;
                    }
                    if (!stockSession.holdingStockingSlot) {
                        if (section.isFull()) {
                            long waitStart = clock.nowTick();
                            logStockWaitFullBegin(nextSectionType, amount, trolley.id());
                            // While full, stocker is waiting for space without holding section exclusivity.
                            warehouse.onStockerFullWaitStart();
                            try {
                                section.waitUntilNotFull(warehouse.runningFlag());
                            } finally {
                                warehouse.onStockerFullWaitEnd();
                            }
                            long waited = Math.max(0, clock.nowTick() - waitStart);
                            logStockWaitFullEnd(nextSectionType, waited, trolley.id());
                        } else {
                            // Small backoff if the section changed state before we could start stocking.
                            clock.sleepTicks(1);
                        }
                        continue;
                    }

                    logStockBegin(nextSectionType, amount, trolley.id());

                    int stocked = 0;
                    try {
                        for (int i = 0; i < stockSession.placeable; i++) {
                            clock.sleepTicks(1);
                            stocked++;
                        }
                    } finally {
                        section.finishStocking(stocked);
                    }

                    cargo.put(nextSectionType, amount - stocked);
                    trolley.setLoad(trolley.load() - stocked);
                    logStockEnd(nextSectionType, stocked, trolley.load(), trolley.id());

                    if (!warehouse.runningFlag().get()) {
                        break;
                    }

                    if (stocked == 0 && cargo.get(nextSectionType) > 0 && noOtherTypes(cargo, nextSectionType)) {
                        clock.sleepTicks(5);
                    }
                }

                if (trolley.load() == 0) {
                    if (!"staging".equals(location)) {
                        logMove(location, "staging", 0, trolley.id());
                        clock.sleepTicks(10);
                    }
                    logRelease(trolley.id(), trolley.load());
                    warehouse.trolleyPool().release(trolley);
                } else {
                    throw new IllegalStateException("Stocker cannot release trolley with non-zero load");
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String chooseNextSection(Map<String, Integer> cargo) {
        switch (warehouse.config().stockerPolicy) {
            case DEMAND_AWARE:
                return chooseDemandAware(cargo);
            case LOWEST_STOCK:
                return chooseLowestStock(cargo);
            case ROUND_ROBIN:
                return chooseRoundRobin(cargo);
            default:
                throw new IllegalStateException("Unsupported stocker policy: " + warehouse.config().stockerPolicy);
        }
    }

    private String chooseDemandAware(Map<String, Integer> cargo) {
        int bestScore = Integer.MIN_VALUE;
        List<String> candidates = new ArrayList<>();
        // Prefer sections where more pickers are queued; break ties randomly.
        for (String type : warehouse.sectionTypes()) {
            int amount = cargo.get(type);
            if (amount <= 0) {
                continue;
            }
            int score = warehouse.section(type).waitingPickers();
            if (score > bestScore) {
                bestScore = score;
                candidates.clear();
                candidates.add(type);
            } else if (score == bestScore) {
                candidates.add(type);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private String chooseLowestStock(Map<String, Integer> cargo) {
        int lowestInventory = Integer.MAX_VALUE;
        List<String> candidates = new ArrayList<>();
        for (String type : warehouse.sectionTypes()) {
            int amount = cargo.get(type);
            if (amount <= 0) {
                continue;
            }
            int inventory = warehouse.section(type).inventory();
            if (inventory < lowestInventory) {
                lowestInventory = inventory;
                candidates.clear();
                candidates.add(type);
            } else if (inventory == lowestInventory) {
                candidates.add(type);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private String chooseRoundRobin(Map<String, Integer> cargo) {
        List<String> sectionTypes = warehouse.sectionTypes();
        int size = sectionTypes.size();
        // Resume from the previous position so one section does not always go first.
        for (int i = 0; i < size; i++) {
            int index = (roundRobinStartIndex + i) % size;
            String type = sectionTypes.get(index);
            if (cargo.get(type) > 0) {
                roundRobinStartIndex = (index + 1) % size;
                return type;
            }
        }
        return null;
    }

    private boolean noOtherTypes(Map<String, Integer> cargo, String except) {
        for (Map.Entry<String, Integer> entry : cargo.entrySet()) {
            if (!entry.getKey().equals(except) && entry.getValue() > 0) {
                return false;
            }
        }
        return true;
    }

    private int total(Map<String, Integer> counts) {
        int t = 0;
        for (int v : counts.values()) {
            t += v;
        }
        return t;
    }

    private Map<String, Integer> buildPickupCaps() {
        LinkedHashMap<String, Integer> caps = new LinkedHashMap<>();
        for (String type : warehouse.sectionTypes()) {
            caps.put(type, warehouse.section(type).freeSpace());
        }
        return caps;
    }

    private boolean shouldTakeBreak(long now) {
        return warehouse.acceptingNewAttempts()
                && warehouse.config().stockerBreakDurationTicks > 0
                && now >= nextBreakTick;
    }

    private long scheduleNextBreak(long fromTick) {
        int min = warehouse.config().stockerBreakMinTicks;
        int max = warehouse.config().stockerBreakMaxTicks;
        if (max <= min) {
            return fromTick + min;
        }
        return fromTick + min + rng.nextInt(max - min + 1);
    }

    private void logAcquire(int trolleyId, long waitedTicks) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("trolley_id", trolleyId);
        fields.put("waited_ticks", waitedTicks);
        logger.log(tid, "acquire_trolley", fields);
    }

    private void logRelease(int trolleyId, int remainingLoad) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("trolley_id", trolleyId);
        fields.put("remaining_load", remainingLoad);
        logger.log(tid, "release_trolley", fields);
    }

    private void logStockerLoad(Map<String, Integer> load, int totalLoad) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (String type : warehouse.sectionTypes()) {
            fields.put(type, load.get(type));
        }
        fields.put("total_load", totalLoad);
        logger.log(tid, "stocker_load", fields);
    }

    private void logMove(String from, String to, int load, int trolleyId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("from", from);
        fields.put("to", to);
        fields.put("load", load);
        fields.put("trolley_id", trolleyId);
        logger.log(tid, "move", fields);
    }

    private void logStockBegin(String section, int amount, int trolleyId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("section", section);
        fields.put("amount", amount);
        fields.put("trolley_id", trolleyId);
        logger.log(tid, "stock_begin", fields);
    }

    private void logStockEnd(String section, int stocked, int remainingLoad, int trolleyId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("section", section);
        fields.put("stocked", stocked);
        fields.put("remaining_load", remainingLoad);
        fields.put("trolley_id", trolleyId);
        logger.log(tid, "stock_end", fields);
    }

    private void logStockWaitFullBegin(String section, int amount, int trolleyId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("section", section);
        fields.put("amount", amount);
        fields.put("trolley_id", trolleyId);
        logger.log(tid, "stock_wait_full_begin", fields);
    }

    private void logStockWaitFullEnd(String section, long waitedTicks, int trolleyId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("section", section);
        fields.put("waited_ticks", waitedTicks);
        fields.put("trolley_id", trolleyId);
        logger.log(tid, "stock_wait_full_end", fields);
    }

    private void logBreakBegin(int durationTicks) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("duration_ticks", durationTicks);
        logger.log(tid, "break_begin", fields);
    }

    private void logBreakEnd() {
        logger.log(tid, "break_end", new LinkedHashMap<>());
    }
}
