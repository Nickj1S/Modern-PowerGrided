package mirefresh.mir.electric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;

/**
 * Reusable "electric machine load" for PowerGrid: a two- or three-terminal resistor whose value
 * is re-solved each tick so the machine draws a target wattage (a constant-power load), plus a
 * small joule buffer that recipe / process logic spends from.
 *
 * <p>With a control terminal (index 2) the load also reads a throttle from the control-vs-negative
 * voltage: 0 V = full power, {@code controlFullScale} V = fully throttled (draw nothing / paused),
 * mirroring the FE Inverter's control pin.
 *
 * <p>Not tied to any block-entity base class — {@code mir}'s own machines and the MI connector
 * companion both drive it the same way: {@link #attach} from {@code buildCircuit}, {@link #serverTick}
 * once per server tick, {@link #drawJoules} to spend, {@link #save}/{@link #load} to persist.
 */
public final class ElectricLoad {

    private static final double DT = 0.05;
    private static final double USABLE_OVERHEAD = 1.5;
    private static final double BUFFER_TICKS = 4.0;
    private static final double FLOOR_FRACTION = 0.25;
    private static final double LOWPASS = 0.4;
    private static final double CONTROL_BLEED_R = 100_000.0;

    @Nullable
    private ElectricWire loadWire;
    @Nullable
    private FloatingNode controlNode;
    @Nullable
    private FloatingNode negativeNode;

    private double jouleBuffer;
    private float lastWatts;
    private float lastVoltage;
    private float lastThrottle;

    /** @param withControl add a 3rd (control) terminal that throttles the load. */
    public void attach(IElectricEntity.CircuitBuilder builder, double initialResistance, boolean withControl) {
        builder.setTerminalCount(withControl ? 3 : 2);
        this.loadWire = builder.connect((float) initialResistance, builder.terminalNode(0), builder.terminalNode(1));
        this.negativeNode = builder.terminalNode(1);
        if (withControl) {
            this.controlNode = builder.terminalNode(2);
            builder.connect((float) CONTROL_BLEED_R, this.controlNode, this.negativeNode); // define its potential
        }
    }

    /**
     * @param wantsPower true when the machine has work to do (else it presents {@code idleResistance})
     * @param maxWatts   the machine's power rating
     * @param nominalResistance {@code designVoltage^2 / maxWatts}
     * @param idleResistance    resistance to present when idle (large; barely loads the grid)
     * @param minResistance     absolute hard floor
     * @param controlFullScale  control-pin voltage that fully throttles the load (<= 0 disables control)
     */
    public void serverTick(boolean wantsPower, double maxWatts, double nominalResistance,
                           double idleResistance, double minResistance, double controlFullScale) {
        final double floorR = Math.max(minResistance, nominalResistance * FLOOR_FRACTION);
        final double bufferCap = maxWatts * DT * BUFFER_TICKS;

        double power = loadWire != null ? Math.max(0.0, loadWire.power()) : 0.0;
        double voltage = loadWire != null ? Math.abs(loadWire.potentialDifference()) : 0.0;

        double throttle = 0.0;
        if (controlFullScale > 0.0 && controlNode != null && negativeNode != null) {
            double cv = Math.abs(controlNode.getVoltage() - negativeNode.getVoltage());
            throttle = Mth.clamp(cv / controlFullScale, 0.0, 1.0);
        }
        lastThrottle = (float) throttle;
        double effectiveMaxW = maxWatts * (1.0 - throttle);
        boolean drawing = wantsPower && effectiveMaxW > 1.0e-3;

        double usable = Math.min(power, maxWatts * USABLE_OVERHEAD);
        jouleBuffer = Math.min(jouleBuffer + usable * DT, bufferCap);
        lastWatts = (float) usable;
        lastVoltage = (float) voltage;

        if (loadWire != null) {
            double targetR = (drawing && voltage > 1.0e-3)
                    ? Mth.clamp(voltage * voltage / effectiveMaxW, floorR, idleResistance)
                    : idleResistance;
            double newR = Mth.clamp(loadWire.getResistance() * (1.0 - LOWPASS) + targetR * LOWPASS, floorR, idleResistance);
            if (newR != loadWire.getResistance()) loadWire.setResistance(newR);
        }

        if (!drawing) {
            jouleBuffer = Math.max(0.0, jouleBuffer - maxWatts * DT);
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

    public float watts() {
        return lastWatts;
    }

    public float voltage() {
        return lastVoltage;
    }

    public float throttle() {
        return lastThrottle;
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
