// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.item;

public class ItemModelResolver {
    private ItemModelResolver() {}

    public void updateForLiving(ItemStackRenderState state, net.minecraft.world.item.ItemStack stack,
            net.minecraft.world.item.ItemDisplayContext context, net.minecraft.world.entity.LivingEntity entity) {
        throw new UnsupportedOperationException();
    }

    public void updateForTopItem(ItemStackRenderState state, net.minecraft.world.item.ItemStack stack,
            net.minecraft.world.item.ItemDisplayContext context, net.minecraft.world.level.Level level,
            net.minecraft.world.entity.ItemOwner owner, int seed) {
        throw new UnsupportedOperationException();
    }
}
