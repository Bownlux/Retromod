// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui.screens.inventory.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface ClientTooltipComponent {
    int getHeight(Font font);
    int getWidth(Font font);
    default void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) { throw new UnsupportedOperationException(); }
}
