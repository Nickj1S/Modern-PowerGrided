package mirefresh.mir.mi;

import mirefresh.mir.Mir;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Lists the {@link MiWireItems} in PowerGrid's own creative tab, next to its stock wires. */
@EventBusSubscriber(modid = Mir.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class MiWireCreativeTab {

    private static final ResourceKey<CreativeModeTab> POWERGRID_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("powergrid", "main"));

    private MiWireCreativeTab() {}

    @SubscribeEvent
    static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(POWERGRID_TAB)) return;
        CreativeModeTab.TabVisibility both = CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS;
        event.accept(new ItemStack(MiWireItems.TIN_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.SILVER_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.CUPRONICKEL_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.ELECTRUM_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.ALUMINUM_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.KANTHAL_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.ANNEALED_COPPER_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.PLATINUM_WIRE.get()), both);
        event.accept(new ItemStack(MiWireItems.SUPERCONDUCTOR_WIRE.get()), both);
    }
}
