package mirefresh.mir.mi.client;

import aztech.modern_industrialization.api.machine.holder.EnergyComponentHolder;
import aztech.modern_industrialization.machines.blockentities.AbstractStorageMachineBlockEntity;
import mirefresh.mir.Mir;
import mirefresh.mir.mi.MiIntegration;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
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
 *   <li>{@code mir:block/mi_generator_connector_*} (the 3-pin output-only combo) on every MI
 *       generator ({@code GeneratorMachineBlockEntity}) — found via
 *       {@link MiIntegration#generatorIds()}, the same probe-based classification used below for
 *       consumers, just filtered to generators instead of excluding them.</li>
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
 * is now that indicator. Blocks that aren't electrified (anything not yet a target) keep MI's
 * decal exactly as drawn.
 */
@EventBusSubscriber(modid = Mir.MODID, value = Dist.CLIENT)
public final class MiConnectorModels {

    private static final ModelResourceLocation BUFFER_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector"));
    private static final ModelResourceLocation CONSUMER_SIDE_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector_side"));
    private static final ModelResourceLocation CONSUMER_TOP_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector_top"));
    private static final ResourceLocation OUTPUT_BADGE_ID =
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "block/overlays/output_energy");

    /**
     * One directly-placed model per physical edge of the block (the 12 places a consumer-connector
     * edge-zone wrench click can put it — see {@code MachineBlockEntityMixin#mir$useWrenchOnConnector}
     * and {@code ConnectorModelWrapper.Placement}). Each is authored in Blockbench for that exact
     * edge, not derived by rotating one canonical shape — rotating a single model around one axis
     * can't reproduce all 12 (they aren't all related by a shared-axis rotation of one another).
     */
    private record EdgeSpec(Direction a, Direction b, ModelResourceLocation model) {}

    private static EdgeSpec edge(Direction a, Direction b, String suffix) {
        return new EdgeSpec(a, b, ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector_edge_" + suffix)));
    }

    /**
     * Directly-placed models for the buffer (storage-unit) connector's four horizontal directions —
     * authored separately per direction rather than derived by rotating {@code mi_connector.json}
     * (still used, unchanged, as the UP/DOWN fallback: no vertical models exist for the buffer
     * connector, see {@code MiIntegration#terminal}).
     */
    private static final Map<Direction, ModelResourceLocation> BUFFER_DIRECT_MODELS = Map.of(
            Direction.SOUTH, bufferModel("south"),
            Direction.WEST, bufferModel("west"),
            Direction.EAST, bufferModel("east"),
            Direction.NORTH, bufferModel("north")
    );

    private static ModelResourceLocation bufferModel(String suffix) {
        return ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector_buffer_" + suffix));
    }

    private static final List<EdgeSpec> CONSUMER_EDGE_SPECS = List.of(
            edge(Direction.UP, Direction.SOUTH, "up_south"),
            edge(Direction.UP, Direction.NORTH, "up_north"),
            edge(Direction.UP, Direction.EAST, "up_east"),
            edge(Direction.UP, Direction.WEST, "up_west"),
            edge(Direction.DOWN, Direction.SOUTH, "down_south"),
            edge(Direction.DOWN, Direction.NORTH, "down_north"),
            edge(Direction.DOWN, Direction.EAST, "down_east"),
            edge(Direction.DOWN, Direction.WEST, "down_west"),
            edge(Direction.SOUTH, Direction.EAST, "south_east"),
            edge(Direction.SOUTH, Direction.WEST, "south_west"),
            edge(Direction.NORTH, Direction.WEST, "north_west"),
            edge(Direction.NORTH, Direction.EAST, "north_east")
    );

    /**
     * Directly-placed models for the generator connector's four horizontal directions — the
     * output-trio-only counterpart of {@link #BUFFER_DIRECT_MODELS} (see
     * {@code mi_generator_connector_south/west/east/north.json}, each derived by taking exactly the
     * corresponding buffer model's first 3 elements — the output trio, no input pair/plates).
     */
    private static final Map<Direction, ModelResourceLocation> GENERATOR_DIRECT_MODELS = Map.of(
            Direction.SOUTH, generatorModel("south"),
            Direction.WEST, generatorModel("west"),
            Direction.EAST, generatorModel("east"),
            Direction.NORTH, generatorModel("north")
    );

    private static ModelResourceLocation generatorModel(String suffix) {
        return ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_generator_connector_" + suffix));
    }

    private static EdgeSpec generatorEdge(Direction a, Direction b, String suffix) {
        return new EdgeSpec(a, b, ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_generator_connector_edge_" + suffix)));
    }

    /** The 12 directly-placed edge mounts for the generator connector, mirroring {@link #CONSUMER_EDGE_SPECS}. */
    private static final List<EdgeSpec> GENERATOR_EDGE_SPECS = List.of(
            generatorEdge(Direction.UP, Direction.SOUTH, "up_south"),
            generatorEdge(Direction.UP, Direction.NORTH, "up_north"),
            generatorEdge(Direction.UP, Direction.EAST, "up_east"),
            generatorEdge(Direction.UP, Direction.WEST, "up_west"),
            generatorEdge(Direction.DOWN, Direction.SOUTH, "down_south"),
            generatorEdge(Direction.DOWN, Direction.NORTH, "down_north"),
            generatorEdge(Direction.DOWN, Direction.EAST, "down_east"),
            generatorEdge(Direction.DOWN, Direction.WEST, "down_west"),
            generatorEdge(Direction.SOUTH, Direction.EAST, "south_east"),
            generatorEdge(Direction.SOUTH, Direction.WEST, "south_west"),
            generatorEdge(Direction.NORTH, Direction.WEST, "north_west"),
            generatorEdge(Direction.NORTH, Direction.EAST, "north_east")
    );

    /** The six storage units: fixed set, get the buffer (output+input) overlay. */
    private static final Set<ResourceLocation> BUFFER_TARGETS = Set.of(
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "lv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "mv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "hv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "ev_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "superconductor_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "creative_storage_unit")
    );

    /**
     * Captured during {@link #onTextureStitch} ({@code TextureAtlasStitchedEvent}, which fires
     * earlier in the same resource-reload pass than {@link #onModifyBakingResult}) — and, unlike that
     * later point, with the atlas's own sprite-by-name lookup already safe to call (probing
     * already-baked quads for this sprite, tried first, turned out unreliable: the decal is
     * apparently only emitted under certain per-block render data, which a bare bake-time probe with
     * no live block doesn't supply).
     */
    @Nullable
    private static TextureAtlasSprite outputBadgeSprite;

    private MiConnectorModels() {}

    @SubscribeEvent
    static void onTextureStitch(TextureAtlasStitchedEvent event) {
        if (!event.getAtlas().location().equals(TextureAtlas.LOCATION_BLOCKS)) return;
        outputBadgeSprite = event.getAtlas().getSprite(OUTPUT_BADGE_ID);
    }

    @SubscribeEvent
    static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(BUFFER_MODEL);
        event.register(CONSUMER_SIDE_MODEL);
        event.register(CONSUMER_TOP_MODEL);
        for (EdgeSpec spec : CONSUMER_EDGE_SPECS) {
            event.register(spec.model());
        }
        for (ModelResourceLocation model : BUFFER_DIRECT_MODELS.values()) {
            event.register(model);
        }
        for (ModelResourceLocation model : GENERATOR_DIRECT_MODELS.values()) {
            event.register(model);
        }
        for (EdgeSpec spec : GENERATOR_EDGE_SPECS) {
            event.register(spec.model());
        }
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

        Map<Set<Direction>, List<BakedQuad>> consumerEdgeQuads = new HashMap<>();
        for (EdgeSpec spec : CONSUMER_EDGE_SPECS) {
            List<BakedQuad> quads = quadsOf(models, spec.model());
            if (quads == null) return; // already warned in quadsOf
            consumerEdgeQuads.put(EnumSet.of(spec.a(), spec.b()), quads);
        }

        Map<Direction, List<BakedQuad>> bufferDirectQuads = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, ModelResourceLocation> entry : BUFFER_DIRECT_MODELS.entrySet()) {
            List<BakedQuad> quads = quadsOf(models, entry.getValue());
            if (quads == null) return; // already warned in quadsOf
            bufferDirectQuads.put(entry.getKey(), quads);
        }

        Map<Direction, List<BakedQuad>> generatorDirectQuads = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, ModelResourceLocation> entry : GENERATOR_DIRECT_MODELS.entrySet()) {
            List<BakedQuad> quads = quadsOf(models, entry.getValue());
            if (quads == null) return; // already warned in quadsOf
            generatorDirectQuads.put(entry.getKey(), quads);
        }

        Map<Set<Direction>, List<BakedQuad>> generatorEdgeQuads = new HashMap<>();
        for (EdgeSpec spec : GENERATOR_EDGE_SPECS) {
            List<BakedQuad> quads = quadsOf(models, spec.model());
            if (quads == null) return; // already warned in quadsOf
            generatorEdgeQuads.put(EnumSet.of(spec.a(), spec.b()), quads);
        }
        // Reused as the canonical UP/DOWN rotation fallback (no directly-authored vertical model
        // exists for a plain, non-edge generator face mount) — see ConnectorModelWrapper's 3-arg
        // single-model constructor.
        List<BakedQuad> generatorCanonicalQuads = generatorDirectQuads.get(Direction.SOUTH);

        Set<ResourceLocation> consumerTargets = findConsumerBlocks();
        Set<ResourceLocation> generatorTargets = MiIntegration.generatorIds();

        // MI's own "this side outputs energy" decal, baked onto whichever face is the block's
        // current output/wrench side. On electrified blocks that's now our connector's job, so it's
        // stripped there — but left alone everywhere else (generators, anything not electrified),
        // since MI's own cable output still works normally on those.
        //
        // Querying the atlas directly here (rather than using outputBadgeSprite, captured earlier at
        // TextureStitchEvent.Post) throws "atlas is not initialized" — the public lookup index isn't
        // populated yet at this point in the reload. An uncaught exception from this handler fails
        // MI's resource reload, which retries — and that retry is what was causing PowerGrid's and
        // MI's own one-time FMLCommonSetupEvent setup to run twice and crash on duplicate registration.
        if (outputBadgeSprite == null) {
            Mir.LOGGER.warn("[mir] output-energy decal sprite wasn't captured at TextureStitchEvent.Post; "
                    + "leaving it un-hidden on electrified blocks");
        }
        TextureAtlasSprite outputBadge = outputBadgeSprite;

        int wrappedBuffer = 0;
        int wrappedGenerator = 0;
        int wrappedConsumer = 0;
        for (ModelResourceLocation key : Set.copyOf(models.keySet())) {
            if (key.variant().equals("inventory")) continue; // block form only
            BakedModel host = models.get(key);
            if (host == null || host instanceof ConnectorModelWrapper) continue;

            if (BUFFER_TARGETS.contains(key.id())) {
                models.put(key, new ConnectorModelWrapper(host, bufferQuads, bufferDirectQuads).hidingSprite(outputBadge));
                wrappedBuffer++;
            } else if (generatorTargets.contains(key.id())) {
                models.put(key, new ConnectorModelWrapper(host, generatorCanonicalQuads, generatorDirectQuads, generatorEdgeQuads)
                        .hidingSprite(outputBadge));
                wrappedGenerator++;
            } else if (consumerTargets.contains(key.id())) {
                models.put(key, new ConnectorModelWrapper(host, consumerSideQuads, consumerTopQuads, consumerEdgeQuads)
                        .hidingSprite(outputBadge));
                wrappedConsumer++;
            }
        }
        Mir.LOGGER.info("[mir] MI connector overlay applied to {} buffer + {} generator + {} consumer block model(s)",
                wrappedBuffer, wrappedGenerator, wrappedConsumer);
    }

    @Nullable
    private static List<BakedQuad> quadsOf(Map<ModelResourceLocation, BakedModel> models, ModelResourceLocation key) {
        BakedModel model = models.get(key);
        if (model == null) {
            Mir.LOGGER.warn("[mir] connector model {} not baked; skipping MI connector overlay", key);
            return null;
        }
        List<BakedQuad> quads = model.getQuads(null, null, RandomSource.create(42L), ModelData.EMPTY, null);
        if (quads.isEmpty()) {
            Mir.LOGGER.warn("[mir] connector model {} produced no quads", key);
            return null;
        }
        return quads;
    }

    /**
     * Every {@code modern_industrialization}-namespaced block whose block entity type can produce
     * an {@code EnergyComponentHolder} instance that isn't {@link MiIntegration#isGeneratorLike} or
     * a storage unit. No world exists yet at bake time, so eligibility is found by constructing one
     * disposable probe per block-entity type (via a state from that type's own {@code
     * validBlocks()}, so the probe is always a state it's actually registered for) and immediately
     * discarding it. MI's machine constructors don't touch the level, so this is safe; any type
     * whose constructor does throw is just skipped.
     *
     * <p>{@code isGeneratorLike} (not a bare {@code instanceof GeneratorMachineBlockEntity}) is what
     * excludes the OUTPUT half of MI's multiblock {@code EnergyHatch} here too — input and output
     * energy hatches share one class, distinguishable only by block id, so a plain instanceof check
     * previously let the output hatch slip in here as a "consumer" and get the input-only connector
     * overlay, on top of the same misclassification in {@link MiIntegration#generatorIds()}.
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
                    ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
                    if (probe instanceof EnergyComponentHolder
                            && !MiIntegration.isGeneratorLike(probe, blockId)
                            && !(probe instanceof AbstractStorageMachineBlockEntity)) {
                        found.add(blockId);
                    }
                } catch (Exception e) {
                    Mir.LOGGER.debug("[mir] couldn't probe {} for EU-consumer status, skipping", typeId, e);
                }
            }
        }
        return found;
    }
}
