package mirefresh.mir;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server/common config. The electrical constants that map recipe energy (joules) and
 * machine tiers onto PowerGrid volts/watts live here so the whole conversion can be tuned
 * without recompiling.
 */
public final class Config {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    /** Joules of grid energy that pay for one unit of a recipe's {@code energy} field. */
    public static final ModConfigSpec.DoubleValue JOULES_PER_RECIPE_UNIT =
            B.comment("Grid joules (watt-seconds) consumed per 1 unit of a recipe's \"energy\" value.")
             .defineInRange("energy.joulesPerRecipeUnit", 1.0, 0.001, 1_000_000.0);

    /** Lower clamp on a machine's modelled load resistance (ohms). Prevents divide-by-near-zero / solver blowups. */
    public static final ModConfigSpec.DoubleValue MACHINE_MIN_RESISTANCE =
            B.comment("Minimum modelled load resistance of a running machine, in ohms.")
             .defineInRange("electrical.machineMinResistance", 0.5, 0.001, 1_000_000.0);

    /** Upper clamp on a machine's modelled load resistance (ohms). Effectively \"idle / open\". */
    public static final ModConfigSpec.DoubleValue MACHINE_MAX_RESISTANCE =
            B.comment("Maximum modelled load resistance (idle machine), in ohms.")
             .defineInRange("electrical.machineMaxResistance", 1_000_000.0, 1.0, 1_000_000_000.0);

    // --- Modern Industrialization bridge (only used when MI is present) ---

    /** Grid joules that pay for 1 EU of an MI machine. */
    public static final ModConfigSpec.DoubleValue MI_JOULES_PER_EU =
            B.comment("Grid joules (watt-seconds) consumed per 1 MI EU.")
             .defineInRange("mi.joulesPerEu", 1.0, 0.001, 1_000_000.0);

    /** Control-pin voltage that fully throttles an MI machine's connector. */
    public static final ModConfigSpec.DoubleValue MI_CONTROL_FULL_SCALE =
            B.comment("Voltage on an MI connector's CONTROL terminal that fully pauses the machine.")
             .defineInRange("mi.controlFullScaleVolts", 20.0, 0.0, 1_000_000.0);

    /** Design voltage an MI connector's load is sized around (V^2 / maxWatts = nominal resistance). */
    public static final ModConfigSpec.DoubleValue MI_DESIGN_VOLTAGE =
            B.comment("Design voltage MI connectors are built to run at.")
             .defineInRange("mi.designVoltage", 64.0, 1.0, 1_000_000.0);

    /** Watts an electrified MI machine draws while working (until per-machine tiers land). */
    public static final ModConfigSpec.DoubleValue MI_DEFAULT_MAX_WATTS =
            B.comment("Watts an electrified MI machine draws while working.")
             .defineInRange("mi.defaultMaxWatts", 640.0, 1.0, 100_000_000.0);

    public static final ModConfigSpec SPEC = B.build();

    private Config() {}
}
