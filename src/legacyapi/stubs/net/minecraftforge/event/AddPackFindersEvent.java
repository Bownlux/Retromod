// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.RecordEvent;

// A record on the host, whose second component is the source adder.
public final class AddPackFindersEvent implements RecordEvent {
    public static final EventBus<AddPackFindersEvent> BUS = null;
    private AddPackFindersEvent() {}
    public PackType getPackType() { throw new UnsupportedOperationException(); }
    public void addRepositorySource(RepositorySource source) { throw new UnsupportedOperationException(); }
}
