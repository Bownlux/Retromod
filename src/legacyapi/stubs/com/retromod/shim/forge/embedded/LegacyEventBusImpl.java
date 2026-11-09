// Compile-only stub of the class ForgeEventBusSynthetics generates for Forge 26.2; never packaged.
package com.retromod.shim.forge.embedded;

import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.BusGroup;

// Each translated mod embeds its own copy, built by ForgeEventBusSynthetics.generateImpl.
public final class LegacyEventBusImpl implements LegacyEventBus {
    public static final LegacyEventBus GAME_BUS = null;

    public LegacyEventBusImpl(BusGroup group) {}

    public static LegacyEventBus of(BusGroup group) { throw new UnsupportedOperationException(); }

    public static void hookBus(BusGroup group, Class<?> type, byte priority, boolean receiveCancelled,
            Consumer<Object> listener) { throw new UnsupportedOperationException(); }

    @Override
    public BusGroup group() { throw new UnsupportedOperationException(); }
}
