// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import net.minecraft.resources.Identifier;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.RecordEvent;

// A record on the host.
public final class AddFramePassEvent implements RecordEvent {
    public static final EventBus<AddFramePassEvent> BUS = null;

    public AddFramePassEvent() {}

    public void addPass(Identifier name, FramePassManager.PassDefinition pass) { throw new UnsupportedOperationException(); }
}
