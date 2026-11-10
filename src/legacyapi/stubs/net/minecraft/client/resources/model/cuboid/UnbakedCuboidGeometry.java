// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.cuboid;

import java.util.List;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.TextureSlots;

// A record on the host.
public final class UnbakedCuboidGeometry implements UnbakedGeometry {
    public UnbakedCuboidGeometry(List<CuboidModelElement> elements) {}
    public List<CuboidModelElement> elements() { throw new UnsupportedOperationException(); }
    public QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name) { throw new UnsupportedOperationException(); }
}
