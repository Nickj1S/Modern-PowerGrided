package mirefresh.mir.mi;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * The five-terminal electrical model behind an MI energy-buffer connector:
 *
 * <ul>
 *   <li><b>Output</b> (terminals 0/1 with CONTROL on 2): a {@link VoltageSourceCoupling} — the same
 *       primitive PowerGrid's {@code BatteryBlockEntity} uses — that <em>discharges</em> the buffer
 *       into the grid. CONTROL scales the EMF toward 0.</li>
 *   <li><b>Input</b> (terminals 3/4): a plain resistor that <em>draws</em> grid power to charge the
 *       buffer; its resistance is raised to open-circuit when the buffer is full.</li>
 * </ul>
 *
 * <p>Real batteries are bidirectional through one terminal pair, but MI's GrandPower energy has a
 * hard input/output split, so the connector mirrors that with two physically separate pairs.
 */
public final class MiConnectorCircuit {

    private static final double DT = 0.05;
    private static final double CONTROL_BLEED_R = 100_000.0;

    @Nullable private VoltageSourceCoupling source;
    @Nullable private FloatingNode outPos, outNeg, control;
    @Nullable private ElectricWire inputLoad;
    @Nullable private FloatingNode inPos, inNeg;

    private float lastOutV, lastOutW, lastThrottle, lastInV, lastInW;

    /** The builder must already have {@code setTerminalCount(5)}. */
    public void attach(IElectricEntity.CircuitBuilder builder, double outputInternalR, double inputInitialR) {
        this.outPos = builder.terminalNode(0);
        this.outNeg = builder.terminalNode(1);
        this.control = builder.terminalNode(2);
        this.inPos = builder.terminalNode(3);
        this.inNeg = builder.terminalNode(4);

        this.source = builder.addInternalNode(
                VoltageSourceCoupling.class, outPos, outNeg, Float.valueOf((float) outputInternalR));
        builder.connect((float) CONTROL_BLEED_R, control, outNeg); // define CONTROL's potential
        this.inputLoad = builder.connect((float) inputInitialR, inPos, inNeg);
    }

    /**
     * Input-only 2-terminal mode for a plain EU consumer that never discharges (no output group, no
     * CONTROL). The builder must already have {@code setTerminalCount(2)}.
     */
    public void attachInputOnly(IElectricEntity.CircuitBuilder builder, double inputInitialR) {
        this.inPos = builder.terminalNode(0);
        this.inNeg = builder.terminalNode(1);
        this.inputLoad = builder.connect((float) inputInitialR, inPos, inNeg);
    }

    /**
     * Drive the output source. Discharge only — a grid that over-volts the output terminals is not
     * credited back as stored energy.
     *
     * @return joules delivered to the grid via the output this tick ({@literal >=} 0)
     */
    public double tickOutput(double openCircuitVoltage, double internalResistance, double controlFullScale) {
        if (source == null) return 0.0;

        double throttle = 0.0;
        if (controlFullScale > 0.0 && control != null && outNeg != null) {
            double cv = Math.abs(control.getVoltage() - outNeg.getVoltage());
            throttle = Mth.clamp(cv / controlFullScale, 0.0, 1.0);
        }
        lastThrottle = (float) throttle;

        double emf = Math.max(0.0, openCircuitVoltage) * (1.0 - throttle);
        source.setVoltage(emf);
        source.setResistance((float) internalResistance);

        double power = source.isConverged() ? -source.getCurrent() * source.getVoltage() : 0.0;
        lastOutW = (float) power;
        lastOutV = (float) (outPos != null && outNeg != null
                ? Math.abs(outPos.getVoltage() - outNeg.getVoltage()) : 0.0);
        return Math.max(0.0, power) * DT;
    }

    /**
     * Drive the input load.
     *
     * @param nominalResistance resistance while charging (sized so it draws ~tier power at tier volts)
     * @param openResistance    resistance when the buffer can't take more (effectively disconnected)
     * @param canCharge         whether the buffer has room
     * @return joules drawn from the grid via the input this tick ({@literal >=} 0)
     */
    public double tickInput(double nominalResistance, double openResistance, boolean canCharge) {
        if (inputLoad == null) return 0.0;

        double targetR = canCharge ? nominalResistance : openResistance;
        if (targetR != inputLoad.getResistance()) {
            inputLoad.setResistance(targetR);
        }
        double power = Math.max(0.0, inputLoad.power());
        lastInW = (float) power;
        lastInV = (float) Math.abs(inputLoad.potentialDifference());
        return power * DT;
    }

    public float outputVoltage() { return lastOutV; }
    public float outputWatts()   { return lastOutW; }
    public float inputVoltage()  { return lastInV; }
    public float inputWatts()    { return lastInW; }
    public float throttle()      { return lastThrottle; }
}
