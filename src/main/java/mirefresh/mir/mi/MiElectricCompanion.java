package mirefresh.mir.mi;

import aztech.modern_industrialization.machines.components.EnergyComponent;
import aztech.modern_industrialization.util.Simulation;
import mirefresh.mir.Config;
import mirefresh.mir.Mir;
import mirefresh.mir.electric.ElectricBattery;
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
 * (which requires a Create {@code SmartBlockEntity}) so a wire can attach to the MI block and power
 * can flow between the machine's {@link EnergyComponent} and the grid.
 *
 * <p>For an energy-buffer block (storage unit) the model is a bidirectional grid battery: it drives
 * the grid at the tier's design voltage while it has EU, and the joules it exchanges each tick are
 * converted to EU and pushed into / pulled out of the machine's {@code EnergyComponent}. Design
 * voltage and max power scale with the MI cable tier ({@link MiConnectorTier}).
 *
 * <p>Lifecycle (create, tick, chunk-unload, block-removed) is driven entirely from
 * {@link MiIntegration}; this BE never ticks through a vanilla ticker.
 */
public class MiElectricCompanion extends ElectricBlockEntity {

    @Nullable
    private MiConnectorTier tier; // lazy: buildCircuit() runs from the super ctor, before field inits
    @Nullable
    private ElectricBattery battery;
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
     * This BE's type is not registered against the block it sits on, so NeoForge's constructor-time
     * "does this BE type belong on this block" check must be bypassed.
     */
    @Override
    public boolean isValidBlockState(BlockState state) {
        return true;
    }

    private ElectricBattery battery() {
        if (battery == null) battery = new ElectricBattery();
        return battery;
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

    /** Series R that delivers ~maxWatts into a matched load: P_match = V^2 / (4R). */
    private double internalResistance() {
        return Math.max(Config.MACHINE_MIN_RESISTANCE.get(),
                designVoltage() * designVoltage() / (4.0 * maxWatts()));
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        battery().attach(builder, internalResistance(), true); // +, -, CONTROL
    }

    @Override
    public void electricalTick() {
        if (level == null || level.isClientSide) return;

        double jpe = Config.MI_JOULES_PER_EU.get();
        long eu = energy != null ? energy.getEu() : 0L;

        double openCircuitV = eu > 0 ? designVoltage() : 0.0;
        double joules = battery().serverTick(openCircuitV, internalResistance(),
                Config.MI_CONTROL_FULL_SCALE.get());

        if (energy != null && jpe > 0.0) {
            long euDelta = Math.round(joules / jpe);
            if (euDelta > 0) {
                energy.consumeEu(euDelta, Simulation.ACT);        // discharging into the grid
            } else if (euDelta < 0) {
                energy.insertEu(-euDelta, Simulation.ACT);         // grid charging the buffer
            }
        }

        if (++diag % 40 == 0) {
            Mir.LOGGER.info("[mir/mi] {} {} Vgrid={} W={} throttle={} eu={}", tier(), getBlockPos(),
                    String.format("%.1f", battery().voltage()), String.format("%.1f", battery().power()),
                    String.format("%.2f", battery().throttle()), eu);
        }
    }

    /** Measured grid voltage at the connector terminals (for GUI / display). */
    public int voltage() {
        return Math.round(battery().voltage());
    }

    /** EU currently in the bound machine buffer (0 if unbound). */
    public long storedEu() {
        return energy != null ? energy.getEu() : 0L;
    }
}
