// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.gui.overlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

// Parameter names as the host's LocalVariableTable gives them; the new layer comes before the one it goes next to.
public final class ForgeLayeredDraw implements ForgeLayer {
    public ForgeLayeredDraw add(Identifier targetStack, Identifier name, ForgeLayer layer) { throw new UnsupportedOperationException(); }
    public ForgeLayeredDraw addAbove(Identifier expectedStack, Identifier newLayer, Identifier otherLayer, ForgeLayer layer) { throw new UnsupportedOperationException(); }
    public ForgeLayeredDraw addBelow(Identifier expectedStack, Identifier newLayer, Identifier otherLayer, ForgeLayer layer) { throw new UnsupportedOperationException(); }
    public ForgeLayeredDraw replace(Identifier expectedLocation, Identifier targetLayer, ForgeLayer replacementRenderer) { throw new UnsupportedOperationException(); }
    public ForgeLayeredDraw getChild(Identifier childName) { throw new UnsupportedOperationException(); }
    public ForgeLayer getLayer(Identifier layerName) { throw new UnsupportedOperationException(); }
    @Override
    public void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) { throw new UnsupportedOperationException(); }
}
