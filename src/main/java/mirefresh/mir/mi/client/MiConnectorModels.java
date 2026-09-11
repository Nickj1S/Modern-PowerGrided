package mirefresh.mir.mi.client;

import aztech.modern_industrialization.api.machine.holder.EnergyComponentHolder;
import aztech.modern_industrialization.machines.blockentities.AbstractStorageMachineBlockEntity;
import aztech.modern_industrialization.machines.blockentities.GeneratorMachineBlockEntity;
import mirefresh.mir.Mir;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Visual-only step of the MI connector integration: appends connector geometry onto the baked
 * models of MI energy-holding blocks, via NeoForge's model-baking hooks. Two overlays:
 *
 * <ul>
 *   <li>{@code mir:block/mi_connector} (the accumulator's 5-pin output+input combo) on the six
 *       storage units — a fixed, known set.</li>
 *   <li>{@code mir:block/mi_connector_side} / {@code mi_connector_top} (the 2-pin consumer
 *       connector) on every other MI machine that can receive EU. There's no static registry of
 *       "which MI blocks are EU consumers" to read at bake time (block ids alone don't say), so
 *       eligibility is found the same way the functional mixin decides it at runtime
 *       ({@code EnergyComponentHolder}, minus generators, minus storage units) — just evaluated
 *       here via a disposable probe {@code BlockEntity} per candidate instead of a live one, since
 *       no world exists yet during model baking.</li>
 * </ul>
 *
 * <p>On every block that gets a connector overlay, MI's own "this side outputs energy" decal
 * ({@code modern_industrialization:block/overlays/output_energy}) is also stripped — the connector
 * is now that indicator. Blocks that aren't electrified (generators, anything not yet a target)
 * keep MI's decal exactly as drawn.
 */
@EventBusSubscriber(modid = Mir.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MiConnectorModels {

    private static final ModelResourceLocation BUFFER_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector"));
    private static final ModelResourceLocation CONSUMER_SIDE_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector_side"));
    private static final ModelResourceLocation CONSUMER_TOP_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector_top"));

    /** The six storage units: fixed set, get the buffer (output+input) overlay. */
    private static final Set<ResourceLocation> BUFFER_TARGETS = Set.of(
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "lv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "mv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "hv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "ev_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "superconductor_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "creative_storage_unit")
    );

    private MiConnectorModels() {}

    @SubscribeEvent
    static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(BUFFER_MODEL);
        event.register(CONSUMER_SIDE_MODEL);
        event.register(CONSUMER_TOP_MODEL);
    }

    @SubscribeEvent
    static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ModList.get().isLoaded("modern_industrialization")) return;

        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        List<BakedQuad> bufferQuads = quadsOf(models, BUFFER_MODEL);
        List<BakedQuad> consumerSideQuads = quadsOf(models, CONSUMER_SIDE_MODEL);
        List<BakedQuad> consumerTopQuads = quadsOf(models, CONSUMER_TOP_MODEL);
        if (bufferQuads == null || consumerSideQuads == null || consumerTopQuads == null) {
            return; // already warned in quadsOf
        }

        Set<ResourceLocation> consumerTargets = findConsumerBlocks();

        // MI's own "this side outputs energy" decal, baked onto whichever face is the block's
        // current output/wrench side. On electrified blocks that's now our connector's job, so it's
        // stripped there — but left alone everywhere else (generators, anything not electrified),
        // since MI's own cable output still works normally on those.
        TextureAtlasSprite outputBadge = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.fromNamespaceAndPath("modern_industrialization", "block/overlays/output_energy"));

        int wrappedBuffer = 0;
        int wrappedConsumer = 0;
        for (ModelResourceLocation key : Set.copyOf(models.keySet())) {
            if (key.variant().equals("inventory")) continue; // block form only
            BakedModel host = models.get(key);
            if (host == null || host instanceof ConnectorModelWrapper) continue;

            if (BUFFER_TARGETS.contains(key.id())) {
                models.put(key, new ConnectorModelWrapper(host, bufferQuads).hidingSprite(outputBadge));
                wrappedBuffer++;
            } else if (consumerTargets.contains(key.id())) {
                models.put(key, new ConnectorModelWrapper(host, consumerSideQuads, consumerTopQuads).hidingSprite(outputBadge));
                wrappedConsumer++;
            }
        }
        Mir.LOGGER.info("[mir] MI connector overlay applied to {} buffer + {} consumer block model(s)",
                wrappedBuffer, wrappedConsumer);
    }

    @Nullable
    private static List<BakedQuad> quadsOf(Map<ModelResourceLocation, BakedModel> models, ModelResourceLocation key) {
        BakedModel model = models.get(key);
        if (model == null) {
            Mir.LOGGER.warn("[mir] connector model {} not baked; skipping MI connector overlay", key);
            return null;
        }
        List<BakedQuad> quads = model.getQuads(null, null, RandomSource.create(42L));
        if (quads.isEmpty()) {
            Mir.LOGGER.warn("[mir] connector model {} produced no quads", key);
            return null;
        }
        return quads;
    }

    /**
     * Every {@code modern_industrialization}-namespaced block whose block entity type can produce
     * an {@code EnergyComponentHolder} instance that isn't a generator or a storage unit. No world
     * exists yet at bake time, so eligibility is found by constructing one disposable probe per
     * block-entity type (via a state from that type's own {@code validBlocks()}, so the probe is
     * always a state it's actually registered for) and immediately discarding it. MI's machine
     * constructors don't touch the level, so this is safe; any type whose constructor does throw
     * is just skipped.
     */
    private static Set<ResourceLocation> findConsumerBlocks() {
        Set<ResourceLocation> found = new HashSet<>();
        for (ResourceLocation typeId : BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet()) {
            if (!"modern_industrialization".equals(typeId.getNamespace())) continue;
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(typeId);
            if (type == null) continue;

            for (Block block : type.getValidBlocks()) {
                try {
                    BlockEntity probe = type.create(BlockPos.ZERO, block.defaultBlockState());
                    if (probe instanceof EnergyComponentHolder
                            && !(probe instanceof GeneratorMachineBlockEntity)
                            && !(probe instanceof AbstractStorageMachineBlockEntity)) {
                        found.add(BuiltInRegistries.BLOCK.getKey(block));
                    }
                } catch (Exception e) {
                    Mir.LOGGER.debug("[mir] couldn't probe {} for EU-consumer status, skipping", typeId, e);
                }
            }
        }
        return found;
    }
}
