package mirefresh.mir.mi;

import net.minecraft.resources.ResourceLocation;

/**
 * Maps an MI cable tier (read from the {@code <tier>_storage_unit} block id) onto the connector's
 * electrical parameters:
 *
 * <ul>
 *   <li>{@link #maxWatts} is tied to the tier's EU/t throughput ({@code euPerTick * 20 ticks/s *
 *       joulesPerEu}), so it stays consistent with the EU&harr;joule conversion.</li>
 *   <li>{@link #designVoltage} is the LV base voltage scaled by 4&times; per tier, so the one
 *       config knob ({@code mi.designVoltage}) moves the whole ladder.</li>
 * </ul>
 */
public enum MiConnectorTier {
    LV("lv", 32L, 0),
    MV("mv", 128L, 1),
    HV("hv", 1024L, 2),
    EV("ev", 8192L, 3),
    SV("superconductor", 128_000_000L, 4);

    /** Block-id prefix (e.g. {@code "lv"} for {@code lv_storage_unit}). */
    public final String prefix;
    /** MI cable-tier throughput in EU per tick. */
    public final long euPerTick;
    /** Voltage-ladder step: designVoltage = base * 4^step. */
    public final int step;

    MiConnectorTier(String prefix, long euPerTick, int step) {
        this.prefix = prefix;
        this.euPerTick = euPerTick;
        this.step = step;
    }

    public static MiConnectorTier forBlockId(ResourceLocation id) {
        String path = id.getPath();
        int u = path.indexOf('_');
        String p = u > 0 ? path.substring(0, u) : path;
        for (MiConnectorTier t : values()) {
            if (t.prefix.equals(p)) return t;
        }
        return LV;
    }

    public double designVoltage(double lvBaseVolts) {
        return lvBaseVolts * Math.pow(4.0, step);
    }

    public double maxWatts(double joulesPerEu) {
        return euPerTick * 20.0 * joulesPerEu;
    }
}
