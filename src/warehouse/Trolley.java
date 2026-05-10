package warehouse;

public final class Trolley {
    private final int id;
    // Load is tracked by owning thread while trolley is checked out.
    private int load;

    public Trolley(int id) {
        this.id = id;
        this.load = 0;
    }

    public int id() {
        return id;
    }

    public int load() {
        return load;
    }

    public void setLoad(int load) {
        this.load = load;
    }
}
