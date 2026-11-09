// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;

public interface ModelBaker {
    ResolvedModel getModel(Identifier id);
    BlockStateModelPart missingBlockModelPart();
    MaterialBaker materials();
}
