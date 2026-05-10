package warehouse;

import java.util.concurrent.TimeUnit;

public final class Clock {
    private final long startNanos;
    private final int tickTimeMs;

    public Clock(int tickTimeMs) {
        this.startNanos = System.nanoTime();
        this.tickTimeMs = tickTimeMs;
    }

    public long nowTick() {
        // Tick is derived from elapsed wall time; threads do not increment a global counter.
        long elapsedNanos = System.nanoTime() - startNanos;
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return elapsedMs / tickTimeMs;
    }

    public void sleepTicks(long ticks) throws InterruptedException {
        if (ticks <= 0) {
            return;
        }
        long sleepMs = ticks * tickTimeMs;
        // Sleeping by ticks keeps action costs and waits on the same time scale.
        Thread.sleep(sleepMs);
    }
}
