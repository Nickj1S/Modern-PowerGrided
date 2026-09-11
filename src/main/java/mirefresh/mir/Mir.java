package mirefresh.mir;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * MI-Refresh ("mir") — bridges Modern Industrialization's EU machines onto Create PowerGrid's
 * analog electrical simulation: MI storage units and energy-consuming machines get native
 * PowerGrid wire terminals and exchange integrated joules (W·s) with the grid instead of EU/FE.
 *
 * <p>Hard dependencies: {@code create}, {@code powergrid}. Soft: {@code modern_industrialization}
 * — the bridge is inert (and the whole mod a no-op) when MI isn't present.
 */
@Mod(Mir.MODID)
public final class Mir {
    public static final String MODID = "mir";
    public static final Logger LOGGER = LogUtils.getLogger();

    // The MI bridge's hidden companion block entity type is the only thing mir registers itself.
    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public Mir(IEventBus modBus, ModContainer container) {
        Registration.init(); // force content class-load so its static register(...) calls run

        BLOCK_ENTITIES.register(modBus);

        modBus.addListener(this::commonSetup);

        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[mir] common setup");
    }
}
