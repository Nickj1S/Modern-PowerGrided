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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Companion to {@link MiCableRemoval} and {@link MiWireItems}: hides MI's own cable items (the
 * {@code <material>_cable} network block item, now stripped from the world on chunk load — see
 * {@link MiCableRemoval}) and its bare {@code <material>_wire} items (now replaced as a crafting
 * ingredient by {@link MiWireItems}'s PowerGrid wires) from MI's own creative tab and, since that
 * tab's entries feed it, the creative-inventory item search. Same reasoning as
 * {@link MiTransformerCreativeTab}: don't invite players to reach for something that's either going
 * to disappear again or no longer serves its old crafting purpose.
 *
 * <p>{@code cupronickel_wire_magnetic} is deliberately NOT in this list &mdash; that item stays
 * exactly as MI made it (just retextured, see the {@code assets/modern_industrialization/textures}
 * override), so it's still meant to be crafted and used normally.
 */
@EventBusSubscriber(modid = Mir.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class MiCableCreativeTab {

    private static final ResourceKey<CreativeModeTab> MI_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("modern_industrialization", "general"));

    private static final String[] MATERIALS = {
            "copper", "tin", "silver", "cupronickel", "electrum",
            "aluminum", "kanthal", "annealed_copper", "platinum", "superconductor"
    };

    private static final Set<ResourceLocation> HIDDEN_IDS = buildHiddenIds();

    private static Set<ResourceLocation> buildHiddenIds() {
        Set<ResourceLocation> ids = new HashSet<>();
        for (String material : MATERIALS) {
            ids.add(id(material + "_cable"));
            ids.add(id(material + "_wire"));
        }
        return Set.copyOf(ids);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("modern_industrialization", path);
    }

    private MiCableCreativeTab() {}

    @SubscribeEvent
    static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (!ModList.get().isLoaded("modern_industrialization")) return;
        if (!event.getTabKey().equals(MI_TAB)) return;

        // Snapshot first: event.remove() mutates the very sets getParentEntries()/getSearchEntries()
        // give a live (if unmodifiable) view over, so removing while iterating either would throw.
        List<ItemStack> toRemove = new ArrayList<>();
        for (ItemStack stack : event.getParentEntries()) {
            if (HIDDEN_IDS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                toRemove.add(stack);
            }
        }
        for (ItemStack stack : event.getSearchEntries()) {
            if (HIDDEN_IDS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                toRemove.add(stack);
            }
        }
        for (ItemStack stack : toRemove) {
            event.remove(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
