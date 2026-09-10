package mirefresh.mir.electric;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * Reusable bidirectional "grid battery" for PowerGrid: a {@link VoltageSourceCoupling} across two
 * terminals whose EMF is driven each tick from an external energy reservoir (an MI
 * {@code EnergyComponent}, a joule buffer, …). Positive solved power = discharging into the grid,
 * negative = the grid charging the reservoir — the caller converts the returned joules to/from its
 * own energy unit and clamps against what the reservoir can give or take.
 *
 * <p>With a control terminal (index 2) the open-circuit voltage is scaled by the control-vs-negative
 * voltage: 0&nbsp;V = full output, {@code controlFullScale}&nbsp;V = output cut, mirroring the FE
 * Inverter's control pin.
 *
 * <p>Modelled on PowerGrid's own {@code BatteryBlockEntity}: a stiff source plus a small series
 * resistance, with the reservoir level fed back into the EMF on the next tick.
 */
public final class ElectricBattery {

    private static final double DT = 0.05;
    private static final double CONTROL_BLEED_R = 100_000.0;

    @Nullable
    private VoltageSourceCoupling source;
    @Nullable
    private FloatingNode positiveNode;
    @Nullable
    private FloatingNode negativeNode;
    @Nullable
    private FloatingNode controlNode;

    private float lastVoltage;
    private float lastPower;
    private float lastThrottle;

    /** @param withControl add a 3rd (control) terminal that scales the output voltage. */
    public void attach(IElectricEntity.CircuitBuilder builder, double internalResistance, boolean withControl) {
        builder.setTerminalCount(withControl ? 3 : 2);
        this.positiveNode = builder.terminalNode(0);
        this.negativeNode = builder.terminalNode(1);
        this.source = builder.addInternalNode(
                VoltageSourceCoupling.class,
                this.positiveNode, this.negativeNode, Float.valueOf((float) internalResistance));
        if (withControl) {
            this.controlNode = builder.terminalNode(2);
            builder.connect((float) CONTROL_BLEED_R, this.controlNode, this.negativeNode); // define its potential
        }
    }

    /**
     * @param openCircuitVoltage EMF the source drives when the reservoir has charge (0 when empty)
     * @param internalResistance series resistance (also caps short-circuit current)
     * @param controlFullScale   control-pin voltage that fully cuts the output ({@literal <=} 0 disables control)
     * @return joules exchanged with the grid this tick — positive discharging, negative charging
     */
    public double serverTick(double openCircuitVoltage, double internalResistance, double controlFullScale) {
        if (source == null) {
            return 0.0;
        }

        double throttle = 0.0;
        if (controlFullScale > 0.0 && controlNode != null && negativeNode != null) {
            double cv = Math.abs(controlNode.getVoltage() - negativeNode.getVoltage());
            throttle = Mth.clamp(cv / controlFullScale, 0.0, 1.0);
        }
        lastThrottle = (float) throttle;

        double emf = Math.max(0.0, openCircuitVoltage) * (1.0 - throttle);
        source.setVoltage(emf);
        source.setResistance((float) internalResistance);

        double power = source.isConverged() ? -source.getCurrent() * source.getVoltage() : 0.0;
        lastPower = (float) power;
        lastVoltage = (float) terminalVoltage();
        return power * DT;
    }

    /** Measured potential difference across the two power terminals (volts, always >= 0). */
    public double terminalVoltage() {
        if (positiveNode == null || negativeNode == null) {
            return 0.0;
        }
        return Math.abs(positiveNode.getVoltage() - negativeNode.getVoltage());
    }

    public float voltage() {
        return lastVoltage;
    }

    /** Instantaneous grid power (watts): positive discharging into the grid, negative charging. */
    public float power() {
        return lastPower;
    }

    public float throttle() {
        return lastThrottle;
    }
}
