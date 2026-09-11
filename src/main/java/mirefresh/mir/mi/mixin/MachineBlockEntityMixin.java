package mirefresh.mir.mi.mixin;

import aztech.modern_industrialization.api.machine.holder.EnergyComponentHolder;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.blockentities.AbstractStorageMachineBlockEntity;
import aztech.modern_industrialization.machines.blockentities.GeneratorMachineBlockEntity;
import aztech.modern_industrialization.machines.blockentities.TransformerMachineBlockEntity;
import aztech.modern_industrialization.machines.components.EnergyComponent;
import aztech.modern_industrialization.machines.components.OrientationComponent;
import mirefresh.mir.Registration;
import mirefresh.mir.mi.MiElectricCompanion;
import mirefresh.mir.mi.MiElectricHolder;
import mirefresh.mir.mi.MiIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Makes every Modern Industrialization {@code MachineBlockEntity} that can hold EU behave as a
 * PowerGrid {@link IElectric} node, in one of two modes (see {@link #mir$isBuffer()}):
 * <ul>
 *   <li>Storage units ({@code AbstractStorageMachineBlockEntity}, minus transformers) get the
 *       5-terminal bidirectional buffer connector: +/&minus;/CONTROL output, +/&minus; input.</li>
 *   <li>Every other EU-holding machine ({@code EnergyComponentHolder}, minus generators) gets the
 *       2-terminal input-only consumer connector: +/&minus; only, feeding the machine's own recipe
 *       energy.</li>
 * </ul>
 * Power flows between the machine's {@code EnergyComponent} and the grid either way.
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
    @Unique @Nullable private Boolean mir$isBufferCache;
    @Unique @Nullable private Boolean mir$isConsumerCache;

    @Unique
    private BlockEntity mir$self() {
        return (BlockEntity) (Object) this;
    }

    // ---- MiElectricHolder ------------------------------------------------------------------

    /**
     * Storage units (and anything else sharing their base class) get the 5-terminal bidirectional
     * battery connector. Transformers also extend that base but are excluded — they're being purged
     * from the world entirely (see {@code MiTransformerRemoval}), not electrified.
     */
    @Override
    public boolean mir$isBuffer() {
        if (mir$isBufferCache == null) {
            mir$isBufferCache = mir$self() instanceof AbstractStorageMachineBlockEntity
                    && !(mir$self() instanceof TransformerMachineBlockEntity);
        }
        return mir$isBufferCache;
    }

    /**
     * Any other MI machine that can receive EU gets the 2-terminal input-only consumer connector.
     * Generators are excluded (that mechanic isn't being touched) and buffers use the other mode.
     */
    @Unique
    private boolean mir$isConsumer() {
        if (mir$isConsumerCache == null) {
            mir$isConsumerCache = mir$self() instanceof EnergyComponentHolder
                    && !(mir$self() instanceof GeneratorMachineBlockEntity)
                    && !mir$isBuffer();
        }
        return mir$isConsumerCache;
    }

    @Override
    public boolean mir$electrified() {
        return mir$isBuffer() || mir$isConsumer();
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
        if (mir$isBuffer()) return MiIntegration.TERMINAL_COUNT;
        if (mir$isConsumer()) return MiIntegration.CONSUMER_TERMINAL_COUNT;
        return 0;
    }

    @Override
    @Nullable
    public ITerminalPlacement terminal(BlockState state, int index) {
        // Matches ConnectorModelWrapper#faceFor exactly, so the clickable box and the rendered post
        // agree: blocks with an output side (storage units) use it; plain machines (only a front,
        // no output side) get the back face instead.
        Direction face = orientation.outputDirection != null
                ? orientation.outputDirection
                : orientation.facingDirection.getOpposite();
        if (mir$isBuffer()) return MiIntegration.terminal(index, face);
        if (mir$isConsumer()) return MiIntegration.consumerTerminal(index, face);
        return null;
    }

    @Override
    @Nullable
    public ElectricBehaviour getBehaviour(Level world, BlockPos pos, BlockState state) {
        MiElectricCompanion companion = mir$companion();
        return companion != null ? companion.getElectricBehaviour() : null;
    }
}
