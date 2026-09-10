package mirefresh.mir.client;

import mirefresh.mir.Mir;
import mirefresh.mir.Registration;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Mir.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MirClient {

    private MirClient() {}

    @SubscribeEvent
    static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.ELECTRIC_FURNACE_MENU.get(), MachineScreen::new);
    }
}
