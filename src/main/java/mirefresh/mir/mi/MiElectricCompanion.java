package mirefresh.mir.mi;

import aztech.modern_industrialization.machines.components.EnergyComponent;
import aztech.modern_industrialization.util.Simulation;
import mirefresh.mir.Config;
import mirefresh.mir.Mir;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;

/**
 * A hidden {@link ElectricBlockEntity} that lives at an MI machine's position but is never placed
 * in {@code level.blockEntities}. It exists purely to host PowerGrid's {@code ElectricBehaviour}
 * (which requires a Create {@code SmartBlockEntity}) so wires can attach to the MI block and power
 * can flow between the machine's {@link EnergyComponent} and the grid.
 *
 * <p>Storage units get the full bidirectional buffer circuit (see {@link MiConnectorCircuit}): a
 * 3-pin <b>output</b> that discharges the buffer into the grid, and a 2-pin <b>input</b> that draws
 * grid power to charge it. Every other EU-holding machine gets just the 2-pin input, feeding its own
 * recipe energy — it never discharges back out. Design voltage and max power scale with the MI cable
 * tier ({@link MiConnectorTier}).
 *
 * <p>Lifecycle (create, tick, chunk-unload, block-removed) is driven entirely from
 * {@link MiIntegration}; this BE never ticks through a vanilla ticker.
 */
public class MiElectricCompanion extends ElectricBlockEntity {

    @Nullable
    private MiConnectorTier tier; // lazy: buildCircuit() runs from the super ctor, before field inits
    @Nullable
    private Boolean isBuffer;    // lazy, same reason
    @Nullable
    private MiConnectorCircuit circuit;
    @Nullable
    private EnergyComponent energy;
    private int diag;

    public MiElectricCompanion(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private MiConnectorTier tier() {
        if (tier == null) {
            tier = MiConnectorTier.forBlockId(BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()));
        }
        return tier;
    }

    /**
     * Storage units get the bidirectional buffer circuit (output + input); everything else gets the
     * input-only consumer circuit. The mixin already knows this from the live BE instance
     * ({@code instanceof AbstractStorageMachineBlockEntity}), but that can't reach this constructor
     * (the companion's {@code BlockEntityType} factory only takes pos/state) — so this re-derives it
     * from the block id, same set as {@link MiIntegration#ELECTRIFIED}.
     */
    private boolean isBuffer() {
        if (isBuffer == null) {
            isBuffer = MiIntegration.ELECTRIFIED.contains(BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()));
        }
        return isBuffer;
    }

    /**
     * This BE's type is not registered against the block it sits on, so NeoForge's constructor-time
     * "does this BE type belong on this block" check must be bypassed.
     */
    @Override
    public boolean isValidBlockState(BlockState state) {
        return true;
    }

    private MiConnectorCircuit circuit() {
        if (circuit == null) circuit = new MiConnectorCircuit();
        return circuit;
    }

    /** Bind the MI machine's energy store (set once by the mixin right after construction). */
    public void bindEnergy(@Nullable EnergyComponent energy) {
        this.energy = energy;
    }

    private double designVoltage() {
        return tier().designVoltage(Config.MI_DESIGN_VOLTAGE.get());
    }

    private double maxWatts() {
        return tier().maxWatts(Config.MI_JOULES_PER_EU.get());
    }

    /** Output series R that delivers ~maxWatts into a matched load: P_match = V^2 / (4R). */
    private double outputInternalR() {
        return Math.max(Config.MACHINE_MIN_RESISTANCE.get(),
                designVoltage() * designVoltage() / (4.0 * maxWatts()));
    }

    /** Input load R that draws ~maxWatts when the grid sits at the tier's design voltage. */
    private double inputNominalR() {
        return Math.max(Config.MACHINE_MIN_RESISTANCE.get(),
                designVoltage() * designVoltage() / maxWatts());
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        if (isBuffer()) {
            builder.setTerminalCount(5); // 0,1,2 = output +/-/CONTROL ; 3,4 = input +/-
            circuit().attach(builder, outputInternalR(), inputNominalR());
        } else {
            builder.setTerminalCount(2); // 0,1 = input +/-
            circuit().attachInputOnly(builder, inputNominalR());
        }
    }

    @Override
    public void electricalTick() {
        if (level == null || level.isClientSide) return;

        double jpe = Config.MI_JOULES_PER_EU.get();
        long eu = energy != null ? energy.getEu() : 0L;
        long cap = energy != null ? energy.getCapacity() : 0L;

        // OUTPUT: discharge the buffer into the grid (buffer mode only — plain consumers never
        // feed the grid back, they only draw)
        double joulesOut = 0.0;
        if (isBuffer()) {
            double openCircuitV = eu > 0 ? designVoltage() : 0.0;
            joulesOut = circuit().tickOutput(openCircuitV, outputInternalR(), Config.MI_CONTROL_FULL_SCALE.get());
        }

        // INPUT: draw grid power to charge the buffer / feed the machine's recipe energy
        boolean canCharge = energy != null && eu < cap;
        double joulesIn = circuit().tickInput(inputNominalR(), Config.MACHINE_MAX_RESISTANCE.get(), canCharge);

        if (energy != null && jpe > 0.0) {
            long euOut = Math.round(joulesOut / jpe);
            if (euOut > 0) energy.consumeEu(euOut, Simulation.ACT);
            long euIn = Math.round(joulesIn / jpe);
            if (euIn > 0) energy.insertEu(euIn, Simulation.ACT);
        }

        if (++diag % 40 == 0) {
            Mir.LOGGER.info("[mir/mi] {} {} out={}V/{}W in={}V/{}W throttle={} eu={}", tier(), getBlockPos(),
                    String.format("%.1f", circuit().outputVoltage()), String.format("%.1f", circuit().outputWatts()),
                    String.format("%.1f", circuit().inputVoltage()), String.format("%.1f", circuit().inputWatts()),
                    String.format("%.2f", circuit().throttle()), eu);
        }
    }

    /** Measured output-terminal voltage (for GUI / display). */
    public int voltage() {
        return Math.round(circuit().outputVoltage());
    }

    /** EU currently in the bound machine buffer (0 if unbound). */
    public long storedEu() {
        return energy != null ? energy.getEu() : 0L;
    }
}
