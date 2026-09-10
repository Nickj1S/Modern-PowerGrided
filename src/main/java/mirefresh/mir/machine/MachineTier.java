package mirefresh.mir.machine;

/**
 * Maps an MI-style machine tier onto PowerGrid electrical parameters.
 *
 * <p>{@link #designVoltage} is the terminal voltage the machine is built to run at (a machine
 * models itself as a load resistance {@code V^2 / maxWatts}); {@link #maxWatts} is the ceiling on
 * how fast it will pull energy from the grid, which also caps how fast a recipe can progress.
 *
 * <p>v0.1 ships only {@link #LV}. Higher tiers land once the framework is proven.
 */
public enum MachineTier {
    LV("lv", 64.0, 640.0),
    MV("mv", 256.0, 2_560.0),
    HV("hv", 1024.0, 20_480.0),
    EV("ev", 4096.0, 163_840.0);

    public final String id;
    /** Volts the machine is designed to run at. */
    public final double designVoltage;
    /** Maximum watts drawn from the grid (and max recipe-progress rate). */
    public final double maxWatts;

    MachineTier(String id, double designVoltage, double maxWatts) {
        this.id = id;
        this.designVoltage = designVoltage;
        this.maxWatts = maxWatts;
    }

    /** Load resistance (ohms) that draws exactly {@link #maxWatts} at {@link #designVoltage}. */
    public double nominalResistance() {
        return designVoltage * designVoltage / maxWatts;
    }
}
