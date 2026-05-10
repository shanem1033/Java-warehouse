package warehouse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class Section {
    private final String type;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();

    private int inventory;
    private boolean stockingInProgress;
    private int waitingPickers;

    public Section(String type, int initial, int capacity) {
        this.type = type;
        this.inventory = initial;
        this.capacity = capacity;
        this.stockingInProgress = false;
        this.waitingPickers = 0;
    }

    public String type() {
        return type;
    }

    public int waitingPickers() {
        lock.lock();
        try {
            return waitingPickers;
        } finally {
            lock.unlock();
        }
    }

    public StockSession beginStocking(int requested, AtomicBoolean running) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (stockingInProgress) {
                if (!running.get()) {
                    return new StockSession(false, 0, false);
                }
                // Another stocker already owns this section, so wait for it to finish.
                stateChanged.await();
            }
            if (!running.get()) {
                return new StockSession(false, 0, false);
            }
            int placeable = Math.max(0, Math.min(requested, capacity - inventory));
            if (placeable <= 0) {
                // Caller can decide whether to wait for space or switch sections.
                return new StockSession(true, 0, false);
            }
            stockingInProgress = true;
            return new StockSession(true, placeable, true);
        } finally {
            lock.unlock();
        }
    }

    public void finishStocking(int stocked) {
        lock.lock();
        try {
            inventory += stocked;
            stockingInProgress = false;
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public long waitForPickableAndTakeOne(Clock clock) throws InterruptedException {
        long waited = 0;
        lock.lockInterruptibly();
        try {
            // Picker must wait both for stock availability and for active stocking to finish.
            while (stockingInProgress || inventory == 0) {
                waitingPickers++;
                long before = clock.nowTick();
                try {
                    // Pickers can be blocked either by empty stock or by active stocking.
                    stateChanged.await();
                } finally {
                    waitingPickers--;
                }
                long after = clock.nowTick();
                waited += Math.max(0, after - before);
            }

            inventory -= 1;
            stateChanged.signalAll();
            return waited;
        } finally {
            lock.unlock();
        }
    }

    public void signalAll() {
        lock.lock();
        try {
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isFull() {
        lock.lock();
        try {
            return inventory >= capacity;
        } finally {
            lock.unlock();
        }
    }

    public int freeSpace() {
        lock.lock();
        try {
            return Math.max(0, capacity - inventory);
        } finally {
            lock.unlock();
        }
    }

    public int inventory() {
        lock.lock();
        try {
            return inventory;
        } finally {
            lock.unlock();
        }
    }

    public void waitUntilNotFull(AtomicBoolean running) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // This wait does not mark stockingInProgress, so pickers remain unblocked.
            while (running.get() && inventory >= capacity) {
                stateChanged.await();
            }
        } finally {
            lock.unlock();
        }
    }

    public static final class StockSession {
        public final boolean active;
        public final int placeable;
        // True only while this stocker owns section stocking exclusivity.
        public final boolean holdingStockingSlot;

        public StockSession(boolean active, int placeable, boolean holdingStockingSlot) {
            this.active = active;
            this.placeable = placeable;
            this.holdingStockingSlot = holdingStockingSlot;
        }
    }
}
