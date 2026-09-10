package mirefresh.mir;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * MI-Refresh ("mir") — Modern Industrialization's machine/recipe concept rebuilt on
 * Create PowerGrid's analog electrical simulation. Machines are native PowerGrid devices:
 * they connect with wire terminals, sag under load, and consume integrated joules (W·s)
 * pulled from the grid instead of EU/FE.
 *
 * <p>Hard dependencies: {@code create}, {@code powergrid}. Soft: {@code modern_industrialization}
 * (reused as the source of ingot/dust items for machine recipe I/O).
 */
@Mod(Mir.MODID)
public final class Mir {
    public static final String MODID = "mir";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Registries. Content classes append to these; everything is bound in the constructor below.
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, MODID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_MODE_TABS.register(
            "machines",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MODID + ".machines"))
                    .icon(() -> Registration.ELECTRIC_FURNACE_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> ITEMS.getEntries().forEach(e -> output.accept(e.get())))
                    .build());

    public Mir(IEventBus modBus, ModContainer container) {
        Registration.init(); // force content class-load so its static register(...) calls run

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
        CREATIVE_MODE_TABS.register(modBus);

        modBus.addListener(this::commonSetup);

        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[mir] common setup");
    }
}
