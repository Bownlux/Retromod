// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui.components;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface Renderable {
    void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick);
}
