// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.internal.Event;

// A record event on the host, posted while the Hud is built.
public final class AddGuiOverlayLayersEvent implements Event {
    public static final EventBus<AddGuiOverlayLayersEvent> BUS = null;

    public ForgeLayeredDraw getLayeredDraw() { throw new UnsupportedOperationException(); }
}
