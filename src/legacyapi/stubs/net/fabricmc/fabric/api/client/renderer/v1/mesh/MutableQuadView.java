// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.mesh;

import net.minecraft.client.resources.model.sprite.Material;

public interface MutableQuadView {
    MutableQuadView materialBake(Material.Baked material, int bakeFlags);
}
