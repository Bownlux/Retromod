// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.extensions.common;

import net.minecraft.world.item.Item;

public class RegisterClientExtensionsEvent extends net.neoforged.bus.api.Event implements net.neoforged.fml.event.IModBusEvent {
    public boolean isItemRegistered(Item item) { throw new UnsupportedOperationException(); }
    public void registerItem(IClientItemExtensions extensions, Item... items) { throw new UnsupportedOperationException(); }
}
