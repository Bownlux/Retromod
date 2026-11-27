// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.Event;

public final class RegisterDebugEntriesEvent extends Event {
    public void register(Identifier id, DebugScreenEntry entry) { throw new UnsupportedOperationException(); }
}
