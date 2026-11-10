// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.geometry;

import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.sprite.TextureSlots;

// Forge patches in a default bake that also takes an IGeometryBakingContext; vanilla 26.3 has only this one.
public interface UnbakedGeometry {
    QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name);
}
