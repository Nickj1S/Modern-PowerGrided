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

    public static final ModConfigSpec SPEC = B.build();

    private Config() {}
}
