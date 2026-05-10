package warehouse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class StagingArea {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();

    public StagingArea(List<String> sectionTypes) {
        for (String type : sectionTypes) {
            counts.put(type, 0);
        }
    }

    public void addDelivery(Map<String, Integer> deliveryCounts) {
        lock.lock();
        try {
            for (Map.Entry<String, Integer> entry : deliveryCounts.entrySet()) {
                counts.put(entry.getKey(), counts.get(entry.getKey()) + entry.getValue());
            }
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Integer> takeUpTo(
            int maxBoxes,
            List<String> sectionTypes,
            Random rng,
            Clock clock,
            AtomicBoolean running,
            BooleanSupplier shouldKeepSupplying,
            Map<String, Integer> perTypeLimits) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (!running.get()) {
                return zeroMap(sectionTypes);
            }
            while (total() == 0) {
                if (!running.get() || !shouldKeepSupplying.getAsBoolean()) {
                    return zeroMap(sectionTypes);
                }
                // Sleep on the condition instead of polling when staging is empty.
                notEmpty.await();
            }

            LinkedHashMap<String, Integer> picked = zeroMap(sectionTypes);
            int toTake = Math.min(maxBoxes, total());
            int offset = rng.nextInt(sectionTypes.size());

            // Distribute picks across section types starting at a random offset.
            // perTypeLimits is a snapshot cap from section free-space at pickup time.
            while (toTake > 0) {
                boolean moved = false;
                for (int i = 0; i < sectionTypes.size() && toTake > 0; i++) {
                    String type = sectionTypes.get((offset + i) % sectionTypes.size());
                    int available = counts.get(type);
                    int currentPicked = picked.get(type);
                    int limit = perTypeLimits.getOrDefault(type, Integer.MAX_VALUE);
                    if (available > 0 && currentPicked < limit) {
                        counts.put(type, available - 1);
                        picked.put(type, currentPicked + 1);
                        toTake--;
                        moved = true;
                    }
                }
                if (!moved) {
                    // No more boxes can be taken without breaking the per-type caps.
                    break;
                }
            }

            clock.sleepTicks(1);
            return picked;
        } finally {
            lock.unlock();
        }
    }

    public void signalAll() {
        lock.lock();
        try {
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private int total() {
        int t = 0;
        for (int v : counts.values()) {
            t += v;
        }
        return t;
    }

    private LinkedHashMap<String, Integer> zeroMap(List<String> sectionTypes) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (String type : sectionTypes) {
            out.put(type, 0);
        }
        return out;
    }
}
