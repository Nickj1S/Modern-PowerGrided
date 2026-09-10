package mirefresh.mir.client;

import mirefresh.mir.menu.MachineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Bare furnace-style screen: vanilla furnace background, a home-drawn progress bar between the
 * input and output slots, and a two-line readout of watts / volts pulled from the menu's synced
 * {@code ContainerData}. Placeholder art; MI's own GUI textures come in a later pass.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {

    private static final ResourceLocation BG =
            ResourceLocation.withDefaultNamespace("textures/gui/container/furnace.png");

    public MachineScreen(MachineMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        g.blit(BG, x, y, 0, 0, imageWidth, imageHeight);

        // progress bar: 24px wide, between input (x+48) and output (x+112) slots, at slot-row height
        int barX = x + 74;
        int barY = y + 39;
        g.fill(barX, barY, barX + 24, barY + 6, 0xFF3A3A3A);
        int filled = Math.round(menu.progress01() * 24f);
        if (filled > 0) g.fill(barX, barY, barX + filled, barY + 6, 0xFF33CC33);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        g.drawString(font, Component.literal(menu.watts() + " / " + menu.maxWatts() + " W"),
                x + 8, y + 58, 0x404040, false);
        g.drawString(font, Component.literal(menu.voltage() + " V"),
                x + 8, y + 68, 0x404040, false);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
    }
}
