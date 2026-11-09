// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.item;

import java.util.List;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3fc;

public class CuboidItemModelWrapper implements ItemModel {
    private CuboidItemModelWrapper() {}
    public static Vector3fc[] computeExtents(List<BakedQuad> quads) { throw new UnsupportedOperationException(); }
    public void update(ItemStackRenderState renderState, net.minecraft.world.item.ItemStack stack, ItemModelResolver resolver,
            net.minecraft.world.item.ItemDisplayContext displayContext, net.minecraft.client.multiplayer.ClientLevel level,
            net.minecraft.world.entity.ItemOwner owner, int seed) { throw new UnsupportedOperationException(); }
}
