package mirefresh.mir.mi;

import mirefresh.mir.Mir;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Companion to {@link MiTransformerRemoval}: hides the same MI cable-tier transformer blocks from
 * MI's own creative tab (and, since that tab's entries feed it, the creative-inventory item search)
 * so players aren't invited to place something {@link MiTransformerRemoval} is just going to delete
 * again on chunk load. A separate class because this event is fired on the mod bus, unlike
 * {@code ChunkEvent.Load}, which is on the game bus.
 */
@EventBusSubscriber(modid = Mir.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class MiTransformerCreativeTab {

    private static final ResourceKey<CreativeModeTab> MI_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("modern_industrialization", "general"));

    private MiTransformerCreativeTab() {}

    @SubscribeEvent
    static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (!ModList.get().isLoaded("modern_industrialization")) return;
        if (!event.getTabKey().equals(MI_TAB)) return;

        Set<ResourceLocation> transformers = MiTransformerRemoval.transformerIds();

        // Snapshot first: event.remove() mutates the very sets getParentEntries()/getSearchEntries()
        // give a live (if unmodifiable) view over, so removing while iterating either would throw.
        List<ItemStack> toRemove = new ArrayList<>();
        for (ItemStack stack : event.getParentEntries()) {
            if (transformers.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                toRemove.add(stack);
            }
        }
        for (ItemStack stack : event.getSearchEntries()) {
            if (transformers.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                toRemove.add(stack);
            }
        }
        for (ItemStack stack : toRemove) {
            event.remove(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
