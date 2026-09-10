package mirefresh.mir.mi.client;

import mirefresh.mir.Mir;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Visual-only step of the MI connector integration: appends the {@code mir:block/mi_connector}
 * geometry onto the baked models of MI energy-storage blocks, via NeoForge's model-baking hooks.
 * No mixins, no functional wiring yet — just checks the connector renders natively on the host
 * block (world, hand, JEI).
 *
 * <p>The connector's canonical face is +Z (south); this first pass appends it un-rotated, so it
 * shows on the south face of every targeted block. Orientation onto each machine's real energy
 * face comes with the functional bridge.
 */
@EventBusSubscriber(modid = Mir.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MiConnectorModels {

    private static final ModelResourceLocation CONNECTOR_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Mir.MODID, "block/mi_connector"));

    /** MI blocks that get a connector in this test. */
    private static final Set<ResourceLocation> TARGETS = Set.of(
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
        event.register(CONNECTOR_MODEL);
    }

    @SubscribeEvent
    static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ModList.get().isLoaded("modern_industrialization")) return;

        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        BakedModel connector = models.get(CONNECTOR_MODEL);
        if (connector == null) {
            Mir.LOGGER.warn("[mir] connector model {} not baked; skipping MI connector overlay", CONNECTOR_MODEL);
            return;
        }

        List<BakedQuad> quads = connector.getQuads(null, null, RandomSource.create(42L));
        if (quads.isEmpty()) {
            Mir.LOGGER.warn("[mir] connector model produced no quads");
            return;
        }

        int wrapped = 0;
        for (ModelResourceLocation key : Set.copyOf(models.keySet())) {
            if (!TARGETS.contains(key.id())) continue;
            if (key.variant().equals("inventory")) continue; // block form only for now
            BakedModel host = models.get(key);
            if (host == null || host instanceof ConnectorModelWrapper) continue;
            models.put(key, new ConnectorModelWrapper(host, quads));
            wrapped++;
        }
        Mir.LOGGER.info("[mir] MI connector overlay applied to {} block model(s)", wrapped);
    }

    // reserved for the next pass: rotate quads onto an arbitrary face
    @SuppressWarnings("unused")
    private static List<BakedQuad> orient(List<BakedQuad> canonical, Direction face) {
        return canonical;
    }
}
