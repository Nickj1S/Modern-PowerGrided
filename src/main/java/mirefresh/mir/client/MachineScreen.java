package mirefresh.mir.client;

import mirefresh.mir.menu.MachineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Furnace-style screen. Background is vanilla furnace.png; slot boxes, the progress bar and the
 * watt/volt readout are all hand-drawn so the layout does not depend on where furnace.png happens
 * to put its own slot graphics. Placeholder art — MI's GUI textures come later.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {

    private static final ResourceLocation BG =
            ResourceLocation.withDefaultNamespace("textures/gui/container/furnace.png");

    public MachineScreen(MachineMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.titleLabelY = 5;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        g.blit(BG, x, y, 0, 0, imageWidth, imageHeight);

        // slot boxes: input @ (56,35), output @ (112,35)
        drawSlot(g, x + 56, y + 35);
        drawSlot(g, x + 112, y + 35);

        // progress bar between the slots
        int barX = x + 78, barY = y + 38, barW = 24, barH = 10;
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF373737);
        g.fill(barX, barY, barX + barW, barY + barH, 0xFF1A1A1A);
        int filled = Math.round(menu.progress01() * barW);
        if (filled > 0) g.fill(barX, barY, barX + filled, barY + barH, 0xFF35C035);
    }

    private static void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
        g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);

        g.drawString(font, Component.literal(menu.watts() + " / " + menu.maxWatts() + " W"), 8, 20, 0x404040, false);
        g.drawString(font, Component.literal(menu.voltage() + " V"), 8, 30, 0x404040, false);
    }
}
