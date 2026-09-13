package mirefresh.mir.mi.mixin;

import aztech.modern_industrialization.api.machine.holder.EnergyComponentHolder;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.MachineOverlay;
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
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Makes every Modern Industrialization {@code MachineBlockEntity} that can hold EU behave as a
 * PowerGrid {@link IElectric} node, in one of three modes:
 * <ul>
 *   <li>Storage units ({@code AbstractStorageMachineBlockEntity}, minus transformers) get the
 *       5-terminal bidirectional buffer connector: +/&minus;/CONTROL output, +/&minus; input
 *       (see {@link #mir$isBuffer()}).</li>
 *   <li>Generators ({@code GeneratorMachineBlockEntity}) get the 3-terminal output-only generator
 *       connector: +/&minus;/CONTROL, feeding the grid from the machine's own EU production (see
 *       {@link #mir$isGenerator()}).</li>
 *   <li>Every other EU-holding machine ({@code EnergyComponentHolder}) gets the 2-terminal
 *       input-only consumer connector: +/&minus; only, feeding the machine's own recipe energy.</li>
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
    @Unique @Nullable private Boolean mir$isGeneratorCache;
    @Unique @Nullable private Boolean mir$isConsumerCache;
    @Unique @Nullable private Direction mir$connectorFace;
    @Unique @Nullable private Direction mir$connectorEdge;

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
     * Generators get the 3-terminal output-only generator connector — their own EU production
     * feeds the grid, they never draw grid power back.
     */
    @Override
    public boolean mir$isGenerator() {
        if (mir$isGeneratorCache == null) {
            mir$isGeneratorCache = mir$self() instanceof GeneratorMachineBlockEntity;
        }
        return mir$isGeneratorCache;
    }

    /**
     * Any other MI machine that can receive EU gets the 2-terminal input-only consumer connector.
     * Generators and buffers use the other two modes.
     */
    @Unique
    private boolean mir$isConsumer() {
        if (mir$isConsumerCache == null) {
            mir$isConsumerCache = mir$self() instanceof EnergyComponentHolder
                    && !mir$isGenerator()
                    && !mir$isBuffer();
        }
        return mir$isConsumerCache;
    }

    @Override
    public boolean mir$electrified() {
        return mir$isBuffer() || mir$isConsumer() || mir$isGenerator();
    }

    /**
     * Frozen, on first read, to whatever the old outputDirection-or-facing-opposite rule would have
     * given — so existing worlds don't see the connector jump the moment this ships. From then on
     * only {@link #mir$useWrenchOnConnector} moves it.
     *
     * <p>The buffer (storage-unit) connector has no vertical model — see {@code MiConnectorModels}'s
     * {@code mi_connector_buffer_*}, authored only for the four horizontal directions — so UP/DOWN is
     * clamped to SOUTH here whenever it would otherwise surface: from the lazy default above (an old
     * world where MI's own field already pointed vertically), from a saved value predating this
     * clamp, or from an edge-zone click whose target includes a vertical component (e.g. the
     * UP+SOUTH edge). This is the single point everything reads through, so it covers all three.
     */
    @Override
    public Direction mir$connectorFace() {
        if (mir$connectorFace == null) {
            mir$connectorFace = orientation.outputDirection != null
                    ? orientation.outputDirection
                    : orientation.facingDirection.getOpposite();
        }
        if (mir$isBuffer() && (mir$connectorFace == Direction.UP || mir$connectorFace == Direction.DOWN)) {
            return Direction.SOUTH;
        }
        return mir$connectorFace;
    }

    @Override
    @Nullable
    public Direction mir$connectorEdge() {
        return mir$connectorEdge;
    }

    /**
     * Splits the wrench gesture in two: MI's own zone system (see {@code MachineOverlay}) divides
     * each face into a 3x3 grid — a center click (1 touching direction) or a corner click (3) keep
     * driving MI's own {@code outputDirection}/{@code facingDirection} exactly as before, untouched
     * here. An edge-midpoint click (2 touching directions, one of the block's 12 physical edges) is
     * intercepted instead and moves the connector, leaving MI's own fields alone so the connector and
     * MI's (now cosmetically inert) output side are independent.
     *
     * <p>For the 2-terminal consumer connector and the 3-terminal generator connector, both touching
     * directions are kept ({@link #mir$connectorFace} and {@link #mir$connectorEdge} together name
     * the exact edge, dispatched straight to one of the 12 pre-placed {@code mi_connector_edge_*} /
     * {@code mi_generator_connector_edge_*} models — see {@code ConnectorModelWrapper.Placement}).
     * The 5-terminal buffer connector has no edge-specific models — just one directly-placed model
     * per horizontal direction — so it keeps only {@link #mir$connectorFace}, and an edge whose
     * target is UP/DOWN is ignored outright (that connector has no vertical position; see
     * {@link #mir$connectorFace()}'s clamp).
     */
    @Inject(method = "useWrench(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)Z", at = @At("HEAD"), cancellable = true)
    private void mir$useWrenchOnConnector(Player player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<Boolean> cir) {
        if (!mir$electrified()) return;
        Vec3 posInBlock = MachineOverlay.getPosInBlock(hit);
        List<Direction> touching = MachineOverlay.TOUCHING_DIRECTIONS.get(MachineOverlay.findHitIndex(posInBlock));
        if (touching.size() != 2) return; // center or corner zone: MI's own useWrench handles it
        Direction naive = hit.getDirection();
        Direction target = touching.get(0) == naive ? touching.get(1) : touching.get(0);
        if (mir$isBuffer() && (target == Direction.UP || target == Direction.DOWN)) {
            return; // buffer connector has no vertical position; let MI's own wrench handle this click
        }
        mir$connectorFace = target;
        mir$connectorEdge = (mir$isConsumer() || mir$isGenerator()) ? naive : null;

        // Mirrors MachineBlockEntity's own post-useWrench sequence exactly (see the decompiled
        // bytecode this was checked against): a bare sendBlockUpdated syncs the BE's NBT fine, but
        // the client's cached ModelData / compiled chunk mesh only actually gets invalidated through
        // MI's own sync(), which a plain Level.blockUpdated + setChanged doesn't replicate on its own.
        BlockEntity self = mir$self();
        Level level = self.getLevel();
        if (level != null) {
            level.blockUpdated(self.getBlockPos(), Blocks.AIR);
            self.setChanged();
            if (!level.isClientSide()) {
                sync();
            }
        }
        cir.setReturnValue(true);
        cir.cancel();
    }

    @Shadow
    public abstract void sync();

    @Inject(method = "saveAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("TAIL"))
    private void mir$saveConnectorFace(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        if (mir$connectorFace != null) {
            tag.putString("mir_connector_face", mir$connectorFace.getSerializedName());
        }
        if (mir$connectorEdge != null) {
            tag.putString("mir_connector_edge", mir$connectorEdge.getSerializedName());
        }
    }

    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("TAIL"))
    private void mir$loadConnectorFace(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        if (tag.contains("mir_connector_face")) {
            mir$connectorFace = Direction.byName(tag.getString("mir_connector_face"));
        }
        mir$connectorEdge = tag.contains("mir_connector_edge")
                ? Direction.byName(tag.getString("mir_connector_edge"))
                : null;
    }

    /**
     * {@code getUpdateTag} is MI's own lightweight per-component sync payload, not a delegate to
     * {@code saveAdditional} — so it needs its own write here. MI doesn't override
     * {@code handleUpdateTag}, so vanilla's default routes the received tag through
     * {@code loadAdditional} on the client, which is where {@link #mir$loadConnectorFace} reads it
     * back.
     */
    @Inject(method = "getUpdateTag(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("RETURN"))
    private void mir$syncConnectorFace(HolderLookup.Provider provider, CallbackInfoReturnable<CompoundTag> cir) {
        if (mir$connectorFace != null) {
            cir.getReturnValue().putString("mir_connector_face", mir$connectorFace.getSerializedName());
        }
        if (mir$connectorEdge != null) {
            cir.getReturnValue().putString("mir_connector_edge", mir$connectorEdge.getSerializedName());
        }
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
        if (mir$isGenerator()) return MiIntegration.GENERATOR_TERMINAL_COUNT;
        if (mir$isConsumer()) return MiIntegration.CONSUMER_TERMINAL_COUNT;
        return 0;
    }

    @Override
    @Nullable
    public ITerminalPlacement terminal(BlockState state, int index) {
        Direction face = mir$connectorFace();
        if (mir$isBuffer()) return MiIntegration.terminal(index, face);
        if (mir$isGenerator()) return MiIntegration.generatorTerminal(index, face, mir$connectorEdge);
        if (mir$isConsumer()) return MiIntegration.consumerTerminal(index, face, mir$connectorEdge);
        return null;
    }

    @Override
    @Nullable
    public ElectricBehaviour getBehaviour(Level world, BlockPos pos, BlockState state) {
        MiElectricCompanion companion = mir$companion();
        return companion != null ? companion.getElectricBehaviour() : null;
    }
}
