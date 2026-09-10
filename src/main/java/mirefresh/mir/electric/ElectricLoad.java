package mirefresh.mir.electric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;

/**
 * Reusable "electric machine load" for PowerGrid: a two-terminal resistor whose value is
 * re-solved each tick so the machine draws a target wattage (a constant-power load), plus a small
 * joule buffer that recipe / process logic spends from.
 *
 * <p>Not tied to any particular block entity base class — {@code mir}'s own machines drive it from
 * {@link org.patryk3211.powergrid.electricity.base.ElectricBlockEntity}, and the (planned) MI
 * connector block entity drives it the same way. The owner is responsible for:
 * <ul>
 *   <li>calling {@link #attach} from {@code buildCircuit}</li>
 *   <li>calling {@link #serverTick} once per server tick with whether it currently wants power</li>
 *   <li>spending energy with {@link #drawJoules}</li>
 *   <li>persisting via {@link #save}/{@link #load}</li>
 * </ul>
 */
public final class ElectricLoad {

    private static final double DT = 0.05;               // one game tick, seconds
    private static final double USABLE_OVERHEAD = 1.5;   // machine can ingest at most 1.5x its rating
    private static final double BUFFER_TICKS = 4.0;      // buffer holds ~0.2s of headroom
    private static final double FLOOR_FRACTION = 0.25;   // never present < 1/4 of nominal resistance
    private static final double LOWPASS = 0.4;           // resistance low-pass weight toward target

    @Nullable
    private ElectricWire loadWire;
    private double jouleBuffer;
    private float lastWatts;
    private float lastVoltage;

    /** Call from {@code buildCircuit}. Creates terminals 0/1 and the load resistor between them. */
    public void attach(IElectricEntity.CircuitBuilder builder, double initialResistance) {
        builder.setTerminalCount(2);
        this.loadWire = builder.connect((float) initialResistance, builder.terminalNode(0), builder.terminalNode(1));
    }

    /**
     * @param wantsPower true when the machine has work to do (else it presents {@code idleResistance})
     * @param maxWatts   the machine's power rating
     * @param nominalResistance {@code designVoltage^2 / maxWatts}
     * @param idleResistance    resistance to present when idle (large; barely loads the grid)
     * @param minResistance     absolute hard floor
     */
    public void serverTick(boolean wantsPower, double maxWatts,
                           double nominalResistance, double idleResistance, double minResistance) {
        final double floorR = Math.max(minResistance, nominalResistance * FLOOR_FRACTION);
        final double bufferCap = maxWatts * DT * BUFFER_TICKS;

        double power = loadWire != null ? Math.max(0.0, loadWire.power()) : 0.0;
        double voltage = loadWire != null ? Math.abs(loadWire.potentialDifference()) : 0.0;

        double usable = Math.min(power, maxWatts * USABLE_OVERHEAD);
        jouleBuffer = Math.min(jouleBuffer + usable * DT, bufferCap);
        lastWatts = (float) usable;
        lastVoltage = (float) voltage;

        if (loadWire != null) {
            double targetR = (wantsPower && voltage > 1.0e-3)
                    ? Mth.clamp(voltage * voltage / maxWatts, floorR, idleResistance)
                    : idleResistance;
            double newR = Mth.clamp(loadWire.getResistance() * (1.0 - LOWPASS) + targetR * LOWPASS, floorR, idleResistance);
            if (newR != loadWire.getResistance()) loadWire.setResistance(newR);
        }

        if (!wantsPower) {
            jouleBuffer = Math.max(0.0, jouleBuffer - maxWatts * DT); // bleed off fast when idle
        }
    }

    /** Spend up to {@code maxJoules} from the buffer; returns how much was actually available. */
    public double drawJoules(double maxJoules) {
        double take = Math.min(jouleBuffer, Math.max(0.0, maxJoules));
        jouleBuffer -= take;
        return take;
    }

    public double bufferedJoules() {
        return jouleBuffer;
    }

    /** Watts actually usable this tick (raw grid power clamped to the machine's ingest limit). */
    public float watts() {
        return lastWatts;
    }

    /** Terminal voltage measured this tick. */
    public float voltage() {
        return lastVoltage;
    }

    @Nullable
    public ElectricWire wire() {
        return loadWire;
    }

    public void save(CompoundTag tag) {
        tag.putDouble("mirJouleBuffer", jouleBuffer);
    }

    public void load(CompoundTag tag) {
        jouleBuffer = tag.getDouble("mirJouleBuffer");
    }
}
