package mirefresh.mir.mi;

import mirefresh.mir.Config;
import mirefresh.mir.Mir;
import mirefresh.mir.electric.ElectricLoad;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;

/**
 * A hidden {@link org.patryk3211.powergrid.electricity.base.ElectricBlockEntity} that lives at an
 * MI machine's position but is never placed in {@code level.blockEntities}. It exists purely to
 * host PowerGrid's {@code ElectricBehaviour} (which requires a Create {@code SmartBlockEntity}) so
 * a wire can attach to the MI block. Its lifecycle is driven by hand from the MI block entity
 * mixin (create on setLevel, {@link #tick} from a level-tick event, remove/unload/NBT forwarded).
 */
public class MiElectricCompanion extends ElectricBlockEntity {

    @Nullable
    private ElectricLoad load;
    private boolean wantsPower = true; // TODO: derive from the MI machine's actual demand
    private int diag;

    public MiElectricCompanion(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private ElectricLoad load() {
        if (load == null) load = new ElectricLoad();
        return load;
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        load().attach(builder, Config.MACHINE_MAX_RESISTANCE.get(), true); // +, -, CONTROL
    }

    @Override
    public void electricalTick() {
        if (level == null || level.isClientSide) return;

        double maxW = Config.MI_DEFAULT_MAX_WATTS.get();
        double designV = Config.MI_DESIGN_VOLTAGE.get();
        double nominalR = designV * designV / maxW;

        load().serverTick(wantsPower, maxW, nominalR,
                Config.MACHINE_MAX_RESISTANCE.get(), Config.MACHINE_MIN_RESISTANCE.get(),
                Config.MI_CONTROL_FULL_SCALE.get());

        if (++diag % 40 == 0) {
            Mir.LOGGER.info("[mir/mi] {} V={} W={} throttle={} buf={}", getBlockPos(),
                    String.format("%.1f", load().voltage()), String.format("%.1f", load().watts()),
                    String.format("%.2f", load().throttle()), String.format("%.1f", load().bufferedJoules()));
        }
    }

    /** Take up to {@code maxEu} worth of energy from the grid buffer; returns EU actually available. */
    public long drawEu(long maxEu) {
        double jpe = Config.MI_JOULES_PER_EU.get();
        double got = load().drawJoules(maxEu * jpe);
        return (long) (got / jpe);
    }

    public double bufferedEu() {
        return load().bufferedJoules() / Config.MI_JOULES_PER_EU.get();
    }

    public double bufferCapacityEu() {
        return Config.MI_DEFAULT_MAX_WATTS.get() * 0.05 * 4.0 / Config.MI_JOULES_PER_EU.get();
    }

    public int voltage() {
        return Math.round(load().voltage());
    }

    public void setWantsPower(boolean w) {
        this.wantsPower = w;
    }

    public void saveEnergy(CompoundTag tag) {
        load().save(tag);
    }

    public void loadEnergy(CompoundTag tag) {
        load().load(tag);
    }
}
