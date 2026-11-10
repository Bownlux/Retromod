// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.gui.overlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface ForgeLayer {
    void extract(GuiGraphicsExtractor graphics, DeltaTracker delta);
}
