// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.extensions.common;

import net.minecraft.world.item.ItemStack;

// Forge 26.2 dropped getCustomRenderer, which old mods still declare and call.
public interface IClientItemExtensions {
    static IClientItemExtensions of(ItemStack stack) { throw new UnsupportedOperationException(); }
}
