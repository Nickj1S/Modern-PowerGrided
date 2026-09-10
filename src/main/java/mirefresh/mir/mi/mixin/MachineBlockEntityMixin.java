package mirefresh.mir.mi.mixin;

import aztech.modern_industrialization.api.machine.holder.EnergyComponentHolder;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.components.EnergyComponent;
import aztech.modern_industrialization.machines.components.OrientationComponent;
import mirefresh.mir.Registration;
import mirefresh.mir.mi.MiElectricCompanion;
import mirefresh.mir.mi.MiElectricHolder;
import mirefresh.mir.mi.MiIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Makes every Modern Industrialization {@code MachineBlockEntity} whose block id is listed in
 * {@link MiIntegration#ELECTRIFIED} behave as a PowerGrid {@link IElectric} node: a light wire can
 * be attached to one of three terminals (+, &minus;, CONTROL) sitting on the machine's output face,
 * and power flows between the machine's {@code EnergyComponent} and the grid.
 *
 * <p>The electrical behaviour is not hosted here &mdash; PowerGrid's {@code ElectricBehaviour}
 * requires a Create {@code SmartBlockEntity}, which MI machines are not. Instead a hidden
 * {@link MiElectricCompanion} (a real {@code ElectricBlockEntity}) is created lazily the first time
 * PowerGrid resolves this block's behaviour and is driven from {@link MiIntegration#onLevelTick}.
 * The companion never enters {@code level.blockEntities}; its lifecycle (tick, chunk-unload,
 * block-removed) is managed entirely by {@code MiIntegration}.
 *
 * <p>No NBT hooks: the energy lives in MI's own {@code EnergyComponent}, which MI persists.
 */
@Mixin(MachineBlockEntity.class)
public abstract class MachineBlockEntityMixin implements IElectric, MiElectricHolder {

    @Shadow @Final public OrientationComponent orientation;

    @Unique @Nullable private MiElectricCompanion mir$companion;
    @Unique @Nullable private Boolean mir$electrifiedCache;

    @Unique
    private BlockEntity mir$self() {
        return (BlockEntity) (Object) this;
    }

    // ---- MiElectricHolder ------------------------------------------------------------------

    @Override
    public boolean mir$electrified() {
        if (mir$electrifiedCache == null) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(mir$self().getBlockState().getBlock());
            mir$electrifiedCache = MiIntegration.ELECTRIFIED.contains(id);
        }
        return mir$electrifiedCache;
    }

    @Override
    @Nullable
    public MiElectricCompanion mir$peekCompanion() {
        return mir$companion;
    }

    @Override
    @Nullable
    public MiElectricCompanion mir$companion() {
        if (mir$companion != null && !mir$companion.isRemoved()) {
            return mir$companion;
        }
        if (!mir$electrified()) {
            return null;
        }
        BlockEntity self = mir$self();
        Level level = self.getLevel();
        if (level == null) {
            return null;
        }
        // Built on both sides: the client copy makes PowerGrid's wire-attach handshake
        // (makeHangingWireConnection -> getElectricBehaviour) succeed client-side instead of
        // logging "at least one behaviour is null" and refusing the predicted connection. It is
        // ticked but never simulates (electricalTick() no-ops on the client).
        MiElectricCompanion companion = new MiElectricCompanion(
                Registration.MI_COMPANION_BE.get(), self.getBlockPos(), self.getBlockState());
        companion.setLevel(level);
        if (self instanceof EnergyComponentHolder holder
                && holder.getEnergyComponent() instanceof EnergyComponent ec) {
            companion.bindEnergy(ec);
        }
        mir$companion = companion;
        MiIntegration.addCompanion(companion);
        return companion;
    }

    // ---- IElectric -----------------------------------------------------------------------

    @Override
    public int terminalCount() {
        return mir$electrified() ? 3 : 0;
    }

    @Override
    @Nullable
    public ITerminalPlacement terminal(BlockState state, int index) {
        if (!mir$electrified()) {
            return null;
        }
        Direction face = orientation.outputDirection != null
                ? orientation.outputDirection
                : orientation.facingDirection;
        return MiIntegration.terminal(index, face);
    }

    @Override
    @Nullable
    public ElectricBehaviour getBehaviour(Level world, BlockPos pos, BlockState state) {
        MiElectricCompanion companion = mir$companion();
        return companion != null ? companion.getElectricBehaviour() : null;
    }
}
