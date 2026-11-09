// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.block.dispatch;

import java.util.List;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;

public class SingleVariant implements BlockStateModel {
    public SingleVariant(BlockStateModelPart model) {}
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) { throw new UnsupportedOperationException(); }
    public Material.Baked particleMaterial() { throw new UnsupportedOperationException(); }
    public int materialFlags() { throw new UnsupportedOperationException(); }
}
