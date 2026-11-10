// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.block.dispatch;

import java.util.List;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;

public interface BlockStateModelPart {
    List<BakedQuad> getQuads(Direction side);
    boolean useAmbientOcclusion();
    Material.Baked particleMaterial();
    int materialFlags();
}
