// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;

public interface UnbakedModel {
    default Boolean ambientOcclusion() { throw new UnsupportedOperationException(); }
    default GuiLight guiLight() { throw new UnsupportedOperationException(); }
    default ItemTransforms transforms() { throw new UnsupportedOperationException(); }
    default TextureSlots.Data textureSlots() { throw new UnsupportedOperationException(); }
    default UnbakedGeometry geometry() { throw new UnsupportedOperationException(); }
    default Identifier parent() { throw new UnsupportedOperationException(); }

    enum GuiLight {
        FRONT, SIDE;
        public boolean lightLikeBlock() { throw new UnsupportedOperationException(); }
    }
}
