# Warehouse Simulation

A concurrent warehouse simulation written in Java for the Concurrent and Distributed Programming module. The program models a warehouse where multiple threads work simultaneously as pickers, stockers, and a delivery driver, all competing for shared resources like trolleys and section inventory.

## What it does

The simulation runs for a configurable number of days. During each day:

- A **delivery thread** randomly delivers boxes of stock to a staging area
- **Stocker threads** collect boxes from the staging area, load them onto trolleys, and restock the warehouse sections
- **Picker threads** acquire a trolley, travel to a random section, and pick one item from it

All threads run concurrently and have to coordinate access to shared resources. The main concurrency challenges are:

- The trolley pool is shared between pickers and stockers. Stockers have a reserved number of trolleys so they are not constantly blocked by pickers
- Each warehouse section uses a lock and condition variables to manage both stocking and picking safely. Only one stocker can stock a section at a time, and pickers block if the section is empty or being stocked
- The staging area is also protected so multiple stockers do not take the same boxes
- Shutdown has to be clean: the main thread closes new pick attempts, drains any in-flight picks, then joins all threads

## Project structure

```
src/warehouse/
    Main.java           entry point, starts all threads and manages shutdown
    Warehouse.java      central object holding all shared state
    Config.java         loads simulation parameters from warehouse.properties or JVM system properties
    Clock.java          tick-based simulation clock
    EventLogger.java    structured event logging with tick timestamps
    TrolleyPool.java    manages the pool of trolleys with separate conditions for pickers and stockers
    Section.java        a single warehouse section with locking for pick and stock operations
    StagingArea.java    buffer where deliveries land before stockers move them to sections
    Trolley.java        represents one trolley and its current load
    PickerThread.java   picker worker thread
    StockerThread.java  stocker worker thread
    DeliveryThread.java delivery worker thread
```

## Concurrency mechanisms used

- `ReentrantLock` and `Condition` for the trolley pool, each section, and the staging area
- Separate `Condition` variables for pickers and stockers in the trolley pool so they can be woken up independently
- `AtomicBoolean` for the global running flag, checked by all threads each iteration
- `AtomicInteger` to track how many stockers are blocked waiting on a full section (this lets pickers temporarily borrow the stocker reserve)
- An in-flight pick counter protected by the lifecycle lock so the main thread can wait for all active picks to finish before shutting down

## Stocker policies

Stockers use one of three policies to decide which section to stock next:

| Policy | Behaviour |
|---|---|
| `DEMAND_AWARE` | Prioritises sections with the most pickers currently waiting (default) |
| `LOWEST_STOCK` | Prioritises the section with the lowest current inventory |
| `ROUND_ROBIN` | Cycles through sections in order, each stocker starting at a different offset |

## Configuration

All parameters are set in `warehouse.properties`. They can also be overridden by passing JVM system properties (`-DKEY=VALUE`). The config file is loaded first and system properties take priority.

| Property | Default | Description |
|---|---|---|
| `SIM_DAYS` | 1 | Number of simulated days to run |
| `TICK_TIME_MS` | 50 | Real milliseconds per simulation tick (min 50) |
| `SECTION_TYPES` | electronics,books,medicines,clothes,tools | Comma-separated list of section types |
| `INITIAL_STOCK_PER_SECTION` | 5 | Starting inventory in each section |
| `SECTION_CAPACITY` | 10 | Maximum items each section can hold |
| `NUM_PICKERS` | 6 | Number of picker threads |
| `NUM_STOCKERS` | 2 | Number of stocker threads |
| `K` | floor((pickers + stockers) / 2) | Total number of trolleys |
| `STOCKER_RESERVE` | 1 | Trolleys reserved for stockers that pickers cannot normally take |
| `STOCKER_CYCLE_CUTOFF_TICKS` | 30 | Stockers stop starting new cycles this many ticks before end of day |
| `STOCKER_POLICY` | DEMAND_AWARE | Stocker section selection policy |
| `STOCKER_BREAK_MIN_TICKS` | 200 | Minimum ticks between stocker breaks |
| `STOCKER_BREAK_MAX_TICKS` | 300 | Maximum ticks between stocker breaks |
| `STOCKER_BREAK_DURATION_TICKS` | 150 | How long each stocker break lasts |
| `TROLLEY_CAPACITY` | 10 | Maximum boxes a trolley can carry |
| `DELIVERY_PROB` | 0.01 | Probability per tick that a delivery arrives |
| `BOXES_PER_DELIVERY` | 10 | Number of boxes in each delivery |
| `RANDOM_SEED` | 42 | Seed for reproducible runs |

## How to run

Compile from the project root:

```bash
javac -d out src/warehouse/*.java
```

Then run, pointing to the config file:

```bash
java -cp out -DCONFIG_FILE=warehouse.properties warehouse.Main
```

To override a single parameter without editing the file:

```bash
java -cp out -DNUM_PICKERS=10 -DSTOCKER_POLICY=LOWEST_STOCK warehouse.Main
```
