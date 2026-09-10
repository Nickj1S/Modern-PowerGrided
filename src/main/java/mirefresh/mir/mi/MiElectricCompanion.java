package mirefresh.mir.mi;

import aztech.modern_industrialization.machines.components.EnergyComponent;
import aztech.modern_industrialization.util.Simulation;
import mirefresh.mir.Config;
import mirefresh.mir.Mir;
import mirefresh.mir.electric.ElectricBattery;
import net.minecraft.core.BlockPos;
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
 * the grid at {@link Config#MI_DESIGN_VOLTAGE} while it has EU, and the joules it exchanges each
 * tick are converted to EU and pushed into / pulled out of the machine's {@code EnergyComponent}.
 *
 * <p>Lifecycle (create, tick, chunk-unload, block-removed) is driven entirely from
 * {@link MiIntegration}; this BE never ticks through a vanilla ticker.
 */
public class MiElectricCompanion extends ElectricBlockEntity {

    @Nullable
    private ElectricBattery battery;
    @Nullable
    private EnergyComponent energy;
    private int diag;

    public MiElectricCompanion(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
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

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        battery().attach(builder, internalResistance(), true); // +, -, CONTROL
    }

    private static double internalResistance() {
        double v = Config.MI_DESIGN_VOLTAGE.get();
        double maxW = Config.MI_DEFAULT_MAX_WATTS.get();
        // series R that delivers ~maxW into a matched load: P_match = V^2 / (4R)
        return Math.max(Config.MACHINE_MIN_RESISTANCE.get(), v * v / (4.0 * maxW));
    }

    @Override
    public void electricalTick() {
        if (level == null || level.isClientSide) return;

        double jpe = Config.MI_JOULES_PER_EU.get();
        long eu = energy != null ? energy.getEu() : 0L;
        double designV = Config.MI_DESIGN_VOLTAGE.get();

        double openCircuitV = eu > 0 ? designV : 0.0;
        double joules = battery().serverTick(openCircuitV, internalResistance(),
                Config.MI_CONTROL_FULL_SCALE.get());

        if (energy != null && jpe > 0.0) {
            long euDelta = Math.round(joules / jpe);
            if (euDelta > 0) {
                energy.consumeEu(euDelta, Simulation.ACT);       // discharging into the grid
            } else if (euDelta < 0) {
                energy.insertEu(-euDelta, Simulation.ACT);        // grid charging the buffer
            }
        }

        if (++diag % 40 == 0) {
            Mir.LOGGER.info("[mir/mi] {} Vgrid={} W={} throttle={} eu={}", getBlockPos(),
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
