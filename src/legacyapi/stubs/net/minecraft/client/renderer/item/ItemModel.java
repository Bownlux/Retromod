// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.item;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public interface ItemModel {
    void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext displayContext,
            ClientLevel level, ItemOwner owner, int seed);

    interface Unbaked extends net.minecraft.client.resources.model.ResolvableModel {
        com.mojang.serialization.MapCodec<? extends Unbaked> type();
        ItemModel bake(BakingContext context, org.joml.Matrix4fc transformation);
    }

    // A record on the host.
    final class BakingContext {
        private BakingContext() {}
        public net.minecraft.client.resources.model.ModelBaker blockModelBaker() { throw new UnsupportedOperationException(); }
    }
}
