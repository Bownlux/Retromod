// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.cuboid;

import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;

// A record on the host; Forge 26.2 adds a seventh component, which this six-argument constructor leaves empty.
public final class CuboidModel implements UnbakedModel {
    public CuboidModel(UnbakedGeometry geometry, UnbakedModel.GuiLight guiLight, Boolean ambientOcclusion, ItemTransforms transforms,
            TextureSlots.Data textureSlots, Identifier parent) {}
    public static CuboidModel fromStream(java.io.Reader reader) { throw new UnsupportedOperationException(); }
    public UnbakedGeometry geometry() { throw new UnsupportedOperationException(); }
    public UnbakedModel.GuiLight guiLight() { throw new UnsupportedOperationException(); }
    public Boolean ambientOcclusion() { throw new UnsupportedOperationException(); }
    public ItemTransforms transforms() { throw new UnsupportedOperationException(); }
    public TextureSlots.Data textureSlots() { throw new UnsupportedOperationException(); }
    public Identifier parent() { throw new UnsupportedOperationException(); }
    public net.minecraftforge.client.model.ForgeBlockModelData forgeData() { throw new UnsupportedOperationException(); }
}
