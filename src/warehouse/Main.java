package warehouse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class Main {
    public static void main(String[] args) throws InterruptedException {
        Config config = Config.fromSystemProperties();
        Clock clock = new Clock(config.tickTimeMs);
        EventLogger logger = new EventLogger(clock);
        Warehouse warehouse = new Warehouse(config);

        List<Thread> supplierThreads = new ArrayList<>();
        List<Thread> pickerThreads = new ArrayList<>();
        // Shared counter so every picker attempt gets a unique id in the logs.
        AtomicLong pickIdCounter = new AtomicLong(0);

        DeliveryThread delivery = new DeliveryThread(warehouse, clock, logger, config.randomSeed + 1);
        supplierThreads.add(delivery);

        for (int i = 1; i <= config.numStockers; i++) {
            // Give each thread its own seed so runs stay reproducible but not identical per thread.
            supplierThreads.add(new StockerThread(i, warehouse, clock, logger, config.randomSeed + 1000L + i));
        }
        for (int i = 1; i <= config.numPickers; i++) {
            pickerThreads.add(new PickerThread(i, warehouse, clock, logger, config.randomSeed + 2000L + i, pickIdCounter));
        }

        for (Thread t : supplierThreads) {
            t.start();
        }
        for (Thread t : pickerThreads) {
            t.start();
        }

        // Day boundary: after this point no new pick attempts may start.
        while (clock.nowTick() < warehouse.simTicks()) {
            clock.sleepTicks(1);
        }

        warehouse.closeNewAttempts();
        // Let any started attempts finish before stopping the simulation.
        warehouse.awaitNoInFlight();
        for (Thread t : pickerThreads) {
            t.join();
        }

        for (Thread t : supplierThreads) {
            t.join();
        }
    }
}
