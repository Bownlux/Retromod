// Compile-only stub of the interface ForgeEventBusSynthetics generates for Forge 26.2; never packaged.
package com.retromod.shim.forge.embedded;

import net.minecraftforge.eventbus.api.bus.BusGroup;

// Each translated mod embeds its own copy, built by ForgeEventBusSynthetics.generateInterface.
public interface LegacyEventBus {
    BusGroup group();

    default void register(Object listener) { throw new UnsupportedOperationException(); }
}
