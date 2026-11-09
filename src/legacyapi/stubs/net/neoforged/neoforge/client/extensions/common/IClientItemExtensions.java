// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.extensions.common;

import net.minecraft.world.item.ItemStack;

// NeoForge 1.21.4 dropped getCustomRenderer, which old mods still declare and call.
public interface IClientItemExtensions {
    static IClientItemExtensions of(ItemStack stack) { throw new UnsupportedOperationException(); }
}
