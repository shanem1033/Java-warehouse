package warehouse;

import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public final class PickerThread extends Thread {
    private final String tid;
    private final Warehouse warehouse;
    private final Clock clock;
    private final EventLogger logger;
    private final Random rng;
    private final AtomicLong pickIdCounter;

    public PickerThread(
            int index,
            Warehouse warehouse,
            Clock clock,
            EventLogger logger,
            long seed,
            AtomicLong pickIdCounter) {
        super("P" + index);
        this.tid = "P" + index;
        this.warehouse = warehouse;
        this.clock = clock;
        this.logger = logger;
        this.rng = new Random(seed);
        this.pickIdCounter = pickIdCounter;
    }

    @Override
    public void run() {
        long mean = 10L * warehouse.config().numPickers;
        long nextAttemptTick = clock.nowTick();
        try {
            // Per-picker schedule with mean 10*P gives ~100 total attempts/day in low contention.
            while (warehouse.runningFlag().get()) {
                nextAttemptTick += rng.nextInt((int) (2 * mean + 1L));

                long now = clock.nowTick();
                if (now < nextAttemptTick) {
                    clock.sleepTicks(nextAttemptTick - now);
                }

                if (!warehouse.runningFlag().get()) {
                    break;
                }
                now = clock.nowTick();
                if (!warehouse.canStartNewAttempt(now)) {
                    break;
                }

                TrolleyPool.AcquireResult acquire = warehouse.trolleyPool()
                        .acquireForPicker(clock, warehouse.runningFlag(), warehouse::canBorrowReservedTrolleyForPicker);
                if (acquire == null) {
                    return;
                }
                Trolley trolley = acquire.trolley;
                logAcquire(trolley.id(), acquire.waitedTicks);

                if (!warehouse.tryBeginPickAttempt(clock.nowTick())) {
                    // If shutdown starts after acquire, release cleanly and do not log a pick.
                    trolley.setLoad(0);
                    logRelease(trolley.id(), 0);
                    warehouse.trolleyPool().release(trolley);
                    break;
                }

                String sectionType = warehouse.sectionTypes().get(rng.nextInt(warehouse.sectionTypes().size()));
                long pickId = pickIdCounter.incrementAndGet();
                // Required ordering: pick_start is logged before any section wait.
                logPickStart(pickId, sectionType, trolley.id());

                long waitedForSection;
                try {
                    waitedForSection = warehouse.section(sectionType).waitForPickableAndTakeOne(clock);
                    // The picked box leaves the system after the 1-tick pick action.
                    trolley.setLoad(1);
                    clock.sleepTicks(1);
                    trolley.setLoad(0);
                    logPickDone(pickId, sectionType, waitedForSection, trolley.id());
                } finally {
                    // Keep in-flight accounting balanced even if this attempt is interrupted.
                    warehouse.endPickAttempt();
                }
                logRelease(trolley.id(), trolley.load());
                warehouse.trolleyPool().release(trolley);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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

    private void logPickStart(long pickId, String sectionType, int trolleyId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("pick_id", pickId);
        fields.put("section", sectionType);
        fields.put("trolley_id", trolleyId);
        logger.log(tid, "pick_start", fields);
    }

    private void logPickDone(long pickId, String sectionType, long waitedTicks, int trolleyId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("pick_id", pickId);
        fields.put("section", sectionType);
        fields.put("waited_ticks", waitedTicks);
        fields.put("trolley_id", trolleyId);
        logger.log(tid, "pick_done", fields);
    }
}
