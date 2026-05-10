package warehouse;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Config {
    public enum StockerPolicy {
        DEMAND_AWARE,
        LOWEST_STOCK,
        ROUND_ROBIN
    }

    public final int simDays;
    public final int tickTimeMs;
    public final List<String> sectionTypes;
    public final int initialStockPerSection;
    public final int sectionCapacity;
    public final int numPickers;
    public final int numStockers;
    public final int trolleyCount;
    public final int stockerReserve;
    public final int stockerCycleCutoffTicks;
    public final StockerPolicy stockerPolicy;
    public final int stockerBreakMinTicks;
    public final int stockerBreakMaxTicks;
    public final int stockerBreakDurationTicks;
    public final int trolleyCapacity;
    public final double deliveryProb;
    public final int boxesPerDelivery;
    public final long randomSeed;

    private Config(
            int simDays,
            int tickTimeMs,
            List<String> sectionTypes,
            int initialStockPerSection,
            int sectionCapacity,
            int numPickers,
            int numStockers,
            int trolleyCount,
            int stockerReserve,
            int stockerCycleCutoffTicks,
            StockerPolicy stockerPolicy,
            int stockerBreakMinTicks,
            int stockerBreakMaxTicks,
            int stockerBreakDurationTicks,
            int trolleyCapacity,
            double deliveryProb,
            int boxesPerDelivery,
            long randomSeed) {
        this.simDays = simDays;
        this.tickTimeMs = tickTimeMs;
        this.sectionTypes = sectionTypes;
        this.initialStockPerSection = initialStockPerSection;
        this.sectionCapacity = sectionCapacity;
        this.numPickers = numPickers;
        this.numStockers = numStockers;
        this.trolleyCount = trolleyCount;
        this.stockerReserve = stockerReserve;
        this.stockerCycleCutoffTicks = stockerCycleCutoffTicks;
        this.stockerPolicy = stockerPolicy;
        this.stockerBreakMinTicks = stockerBreakMinTicks;
        this.stockerBreakMaxTicks = stockerBreakMaxTicks;
        this.stockerBreakDurationTicks = stockerBreakDurationTicks;
        this.trolleyCapacity = trolleyCapacity;
        this.deliveryProb = deliveryProb;
        this.boxesPerDelivery = boxesPerDelivery;
        this.randomSeed = randomSeed;
    }

    public static Config fromSystemProperties() {
        // Resolution order is: JVM system property -> config file -> hardcoded default.
        Properties fileProps = loadConfigFile();

        int simDays = intProp("SIM_DAYS", 1, fileProps);
        int tickTimeMs = intProp("TICK_TIME_MS", 50, fileProps);
        if (tickTimeMs < 50) {
            throw new IllegalArgumentException("TICK_TIME_MS must be >= 50");
        }

        List<String> sectionTypes = parseSectionTypes(
                stringProp("SECTION_TYPES", "electronics,books,medicines,clothes,tools", fileProps));
        if (sectionTypes.isEmpty()) {
            throw new IllegalArgumentException("SECTION_TYPES must not be empty");
        }

        int initialStockPerSection = intProp("INITIAL_STOCK_PER_SECTION", 5, fileProps);
        int sectionCapacity = intProp("SECTION_CAPACITY", 10, fileProps);
        int numPickers = intProp("NUM_PICKERS", 6, fileProps);
        int numStockers = intProp("NUM_STOCKERS", 2, fileProps);

        int defaultK = (numPickers + numStockers) / 2;
        int trolleyCount = intProp("K", defaultK, fileProps);
        if (trolleyCount <= 0) {
            throw new IllegalArgumentException("K must be > 0");
        }
        int stockerReserve = intProp("STOCKER_RESERVE", 1, fileProps);
        if (stockerReserve < 0) {
            throw new IllegalArgumentException("STOCKER_RESERVE must be >= 0");
        }
        if (trolleyCount <= stockerReserve || trolleyCount < 2) {
            throw new IllegalArgumentException(
                    "Invalid trolley configuration: K must be > STOCKER_RESERVE and >= 2"
                            + " (K=" + trolleyCount + ", STOCKER_RESERVE=" + stockerReserve + ")");
        }
        int stockerCycleCutoffTicks = intProp("STOCKER_CYCLE_CUTOFF_TICKS", 30, fileProps);
        if (stockerCycleCutoffTicks < 0) {
            throw new IllegalArgumentException("STOCKER_CYCLE_CUTOFF_TICKS must be >= 0");
        }
        StockerPolicy stockerPolicy = stockerPolicyProp("STOCKER_POLICY", StockerPolicy.DEMAND_AWARE, fileProps);
        int stockerBreakMinTicks = intProp("STOCKER_BREAK_MIN_TICKS", 200, fileProps);
        int stockerBreakMaxTicks = intProp("STOCKER_BREAK_MAX_TICKS", 300, fileProps);
        int stockerBreakDurationTicks = intProp("STOCKER_BREAK_DURATION_TICKS", 150, fileProps);
        if (stockerBreakMinTicks < 0 || stockerBreakMaxTicks < 0 || stockerBreakDurationTicks < 0) {
            throw new IllegalArgumentException("Stocker break ticks must be >= 0");
        }
        if (stockerBreakMinTicks > stockerBreakMaxTicks) {
            throw new IllegalArgumentException("STOCKER_BREAK_MIN_TICKS must be <= STOCKER_BREAK_MAX_TICKS");
        }

        int trolleyCapacity = intProp("TROLLEY_CAPACITY", 10, fileProps);
        double deliveryProb = doubleProp("DELIVERY_PROB", 0.01d, fileProps);
        int boxesPerDelivery = intProp("BOXES_PER_DELIVERY", 10, fileProps);
        long randomSeed = longProp("RANDOM_SEED", 42L, fileProps);

        return new Config(
                simDays,
                tickTimeMs,
                sectionTypes,
                initialStockPerSection,
                sectionCapacity,
                numPickers,
                numStockers,
                trolleyCount,
                stockerReserve,
                stockerCycleCutoffTicks,
                stockerPolicy,
                stockerBreakMinTicks,
                stockerBreakMaxTicks,
                stockerBreakDurationTicks,
                trolleyCapacity,
                deliveryProb,
                boxesPerDelivery,
                randomSeed);
    }

    public long simTicks() {
        return 1000L * simDays;
    }

    private static int intProp(String name, int defaultValue, Properties fileProps) {
        String raw = rawProp(name, fileProps);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static long longProp(String name, long defaultValue, Properties fileProps) {
        String raw = rawProp(name, fileProps);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    private static double doubleProp(String name, double defaultValue, Properties fileProps) {
        String raw = rawProp(name, fileProps);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(raw.trim());
    }

    private static String stringProp(String name, String defaultValue, Properties fileProps) {
        String raw = rawProp(name, fileProps);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim();
    }

    private static StockerPolicy stockerPolicyProp(String name, StockerPolicy defaultValue, Properties fileProps) {
        String raw = rawProp(name, fileProps);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return StockerPolicy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid " + name + ": " + raw + ". Expected one of "
                            + Arrays.toString(StockerPolicy.values()),
                    e);
        }
    }

    private static String rawProp(String name, Properties fileProps) {
        String sys = System.getProperty(name);
        if (sys != null) {
            return sys;
        }
        return fileProps.getProperty(name);
    }

    private static Properties loadConfigFile() {
        Properties props = new Properties();
        String pathRaw = System.getProperty("CONFIG_FILE", "warehouse.properties");
        Path path = Paths.get(pathRaw);
        if (!Files.exists(path)) {
            // Missing file is fine; defaults/system props still provide a full config.
            return props;
        }
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            return props;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load config file: " + pathRaw, e);
        }
    }

    private static List<String> parseSectionTypes(String raw) {
        List<String> out = new ArrayList<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(out::add);
        return out;
    }
}
