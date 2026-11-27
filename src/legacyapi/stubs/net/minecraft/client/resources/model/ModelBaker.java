// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;

public interface ModelBaker {
    ResolvedModel getModel(Identifier id);
    BlockStateModelPart missingBlockModelPart();
    MaterialBaker materials();
    Interner interner();
    <T> T compute(SharedOperationKey<T> key);

    interface SharedOperationKey<T> {
        T compute(ModelBaker baker);
    }

    interface Interner {
        org.joml.Vector3fc vector(org.joml.Vector3fc vector);
        net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo materialInfo(
                net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo info);
    }
}
