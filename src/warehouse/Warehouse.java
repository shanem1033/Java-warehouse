package warehouse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class Warehouse {
    private final Config config;
    private final TrolleyPool trolleyPool;
    private final StagingArea stagingArea;
    private final LinkedHashMap<String, Section> sections;
    private final AtomicBoolean running;
    // Protects attempt lifecycle state so start/stop boundaries are consistent.
    private final ReentrantLock lifecycleLock;
    private final Condition noInFlight;
    // Lets picker acquires relax reserve when stockers are blocked on full sections.
    private final AtomicInteger stockersWaitingOnFull;
    private boolean acceptingNewAttempts;
    private int inFlightPicks;

    public Warehouse(Config config) {
        this.config = config;
        this.trolleyPool = new TrolleyPool(config.trolleyCount, config.stockerReserve);
        this.stagingArea = new StagingArea(config.sectionTypes);
        this.sections = new LinkedHashMap<>();
        for (String sectionType : config.sectionTypes) {
            sections.put(sectionType, new Section(sectionType, config.initialStockPerSection, config.sectionCapacity));
        }
        this.running = new AtomicBoolean(true);
        this.lifecycleLock = new ReentrantLock();
        this.noInFlight = lifecycleLock.newCondition();
        this.stockersWaitingOnFull = new AtomicInteger(0);
        this.acceptingNewAttempts = true;
        this.inFlightPicks = 0;
    }

    public Config config() {
        return config;
    }

    public TrolleyPool trolleyPool() {
        return trolleyPool;
    }

    public StagingArea stagingArea() {
        return stagingArea;
    }

    public Section section(String type) {
        return sections.get(type);
    }

    public List<String> sectionTypes() {
        return config.sectionTypes;
    }

    public AtomicBoolean runningFlag() {
        return running;
    }

    public long simTicks() {
        return config.simTicks();
    }

    public boolean acceptingNewAttempts() {
        lifecycleLock.lock();
        try {
            return acceptingNewAttempts;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public boolean canStartNewAttempt(long nowTick) {
        lifecycleLock.lock();
        try {
            return acceptingNewAttempts && nowTick < simTicks();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public boolean tryBeginPickAttempt(long nowTick) {
        lifecycleLock.lock();
        try {
            if (!acceptingNewAttempts || nowTick >= simTicks()) {
                return false;
            }
            // Count the attempt here so shutdown knows a picker is already committed.
            inFlightPicks++;
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void closeNewAttempts() {
        lifecycleLock.lock();
        try {
            acceptingNewAttempts = false;
            if (inFlightPicks == 0) {
                noInFlight.signalAll();
                stagingArea.signalAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void endPickAttempt() {
        lifecycleLock.lock();
        try {
            if (inFlightPicks <= 0) {
                throw new IllegalStateException("inFlightPicks underflow");
            }
            inFlightPicks--;
            if (inFlightPicks == 0) {
                noInFlight.signalAll();
                if (!acceptingNewAttempts) {
                    stagingArea.signalAll();
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public int inFlightPicks() {
        lifecycleLock.lock();
        try {
            return inFlightPicks;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void awaitNoInFlight() throws InterruptedException {
        lifecycleLock.lockInterruptibly();
        try {
            // Main thread uses this to drain all attempts after closing new ones.
            while (inFlightPicks > 0) {
                noInFlight.await();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public boolean shouldKeepSupplying() {
        lifecycleLock.lock();
        try {
            // Stockers and deliveries keep going while picks are still draining.
            return acceptingNewAttempts || inFlightPicks > 0;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void onStockerFullWaitStart() {
        // Exposed to trolley policy so pickers can temporarily borrow reserve when needed.
        stockersWaitingOnFull.incrementAndGet();
        trolleyPool.signalAll();
    }

    public void onStockerFullWaitEnd() {
        int value = stockersWaitingOnFull.decrementAndGet();
        if (value < 0) {
            stockersWaitingOnFull.compareAndSet(value, 0);
        }
        trolleyPool.signalAll();
    }

    public boolean canBorrowReservedTrolleyForPicker() {
        return stockersWaitingOnFull.get() > 0;
    }

    public Map<String, Integer> zeroSectionMap() {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (String type : config.sectionTypes) {
            out.put(type, 0);
        }
        return out;
    }

    public void stop() {
        running.set(false);
        trolleyPool.signalAll();
        stagingArea.signalAll();
        for (Section section : sections.values()) {
            section.signalAll();
        }
    }
}
