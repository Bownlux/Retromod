// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.cuboid;

import net.minecraft.world.item.ItemDisplayContext;

// A record on the host.
public final class ItemTransforms {
    public static final ItemTransforms NO_TRANSFORMS = null;
    public ItemTransforms(ItemTransform thirdPersonLeftHand, ItemTransform thirdPersonRightHand, ItemTransform firstPersonLeftHand,
            ItemTransform firstPersonRightHand, ItemTransform head, ItemTransform gui, ItemTransform ground, ItemTransform fixed,
            ItemTransform fixedFromBottom) {}
    public ItemTransform getTransform(ItemDisplayContext context) { throw new UnsupportedOperationException(); }
}
