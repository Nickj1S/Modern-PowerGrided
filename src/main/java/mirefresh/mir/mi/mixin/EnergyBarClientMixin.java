package mirefresh.mir.mi.mixin;

import aztech.modern_industrialization.MIText;
import aztech.modern_industrialization.client.machines.gui.MachineScreen;
import aztech.modern_industrialization.client.machines.guicomponents.EnergyBarClient;
import mirefresh.mir.mi.MiIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Relabels the energy-bar tooltip from EU to J on MI blocks bridged onto PowerGrid
 * ({@link MiIntegration#ELECTRIFIED}) — their stored amount is meaningfully joules via the grid
 * conversion, unlike an ordinary MI machine's EU, which has no relation to PowerGrid at all and
 * should keep reading "EU".
 *
 * <p>Redirects the {@code MIText.EuMaxed.text(...)} call inside the tooltip renderer (hit for both
 * the shift-held raw-number branch and the k/M/G-suffixed branch) to a {@code mir}-owned
 * translation key with the same {@code "%s / %s %sX"} shape but a J suffix, only while the open
 * screen belongs to one of our electrified blocks.
 */
@Mixin(EnergyBarClient.Renderer.class)
public abstract class EnergyBarClientMixin {

    @Redirect(method = "renderTooltip", at = @At(value = "INVOKE",
            target = "Laztech/modern_industrialization/MIText;text([Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent mir$relabelJoules(MIText text, Object[] args) {
        if (text == MIText.EuMaxed && mir$isElectrifiedScreen()) {
            return Component.translatable("text.mir.eu_maxed_joules", args);
        }
        return text.text(args);
    }

    private static boolean mir$isElectrifiedScreen() {
        return Minecraft.getInstance().screen instanceof MachineScreen ms
                && MiIntegration.ELECTRIFIED.contains(ms.getMenu().guiParams.blockId);
    }
}
