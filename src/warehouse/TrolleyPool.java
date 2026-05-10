package warehouse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class TrolleyPool {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition pickerAvailable = lock.newCondition();
    private final Condition stockerAvailable = lock.newCondition();
    private final Deque<Trolley> free = new ArrayDeque<>();
    private final int stockerReserve;

    public TrolleyPool(int count, int stockerReserve) {
        this.stockerReserve = stockerReserve;
        for (int i = 1; i <= count; i++) {
            free.addLast(new Trolley(i));
        }
    }

    public AcquireResult acquireForPicker(
            Clock clock,
            AtomicBoolean running,
            BooleanSupplier allowReserveBorrow) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long waited = 0;
            // Keep a reserve for stockers unless warehouse says it is safe to borrow it.
            while (free.isEmpty() || (free.size() <= stockerReserve && !allowReserveBorrow.getAsBoolean())) {
                if (!running.get()) {
                    return null;
                }
                // waited_ticks is measured in ticks, not ms, so it matches the rest of the simulation.
                long before = clock.nowTick();
                pickerAvailable.await();
                long after = clock.nowTick();
                waited += Math.max(0, after - before);
            }
            Trolley trolley = free.removeFirst();
            trolley.setLoad(0);
            return new AcquireResult(trolley, waited);
        } finally {
            lock.unlock();
        }
    }

    public AcquireResult acquireForStocker(Clock clock, AtomicBoolean running) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long waited = 0;
            while (free.isEmpty()) {
                if (!running.get()) {
                    return null;
                }
                // Stockers only wait for an actually free trolley, not for section state here.
                long before = clock.nowTick();
                stockerAvailable.await();
                long after = clock.nowTick();
                waited += Math.max(0, after - before);
            }
            Trolley trolley = free.removeFirst();
            trolley.setLoad(0);
            return new AcquireResult(trolley, waited);
        } finally {
            lock.unlock();
        }
    }

    public void release(Trolley trolley) {
        lock.lock();
        try {
            if (trolley.load() != 0) {
                throw new IllegalStateException("Cannot release trolley with non-zero load");
            }
            free.addLast(trolley);
            pickerAvailable.signalAll();
            stockerAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void signalAll() {
        lock.lock();
        try {
            pickerAvailable.signalAll();
            stockerAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public static final class AcquireResult {
        public final Trolley trolley;
        public final long waitedTicks;

        public AcquireResult(Trolley trolley, long waitedTicks) {
            this.trolley = trolley;
            this.waitedTicks = waitedTicks;
        }
    }
}
