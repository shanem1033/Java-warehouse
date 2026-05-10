package warehouse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public final class DeliveryThread extends Thread {
    private final Warehouse warehouse;
    private final Clock clock;
    private final EventLogger logger;
    private final Random rng;

    public DeliveryThread(Warehouse warehouse, Clock clock, EventLogger logger, long seed) {
        super("DEL");
        this.warehouse = warehouse;
        this.clock = clock;
        this.logger = logger;
        this.rng = new Random(seed);
    }

    @Override
    public void run() {
        long nextTick = 0;
        try {
            while (warehouse.runningFlag().get() && warehouse.shouldKeepSupplying()) {
                long now = clock.nowTick();
                if (now < nextTick) {
                    // This keeps delivery checks aligned to the tick timeline.
                    clock.sleepTicks(nextTick - now);
                    continue;
                }

                if (rng.nextDouble() < warehouse.config().deliveryProb) {
                    // Bernoulli arrival model: one delivery trial per tick.
                    Map<String, Integer> delivery = warehouse.zeroSectionMap();
                    for (int i = 0; i < warehouse.config().boxesPerDelivery; i++) {
                        String type = warehouse.sectionTypes().get(rng.nextInt(warehouse.sectionTypes().size()));
                        delivery.put(type, delivery.get(type) + 1);
                    }
                    warehouse.stagingArea().addDelivery(delivery);

                    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                    for (String type : warehouse.sectionTypes()) {
                        fields.put(type, delivery.get(type));
                    }
                    logger.log("DEL", "delivery_arrived", fields);
                }

                nextTick++;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
