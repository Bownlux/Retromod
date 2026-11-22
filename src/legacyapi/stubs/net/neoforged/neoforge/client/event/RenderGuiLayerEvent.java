// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import net.minecraft.client.DeltaTracker;
import net.minecraft.resources.Identifier;

public abstract class RenderGuiLayerEvent extends net.neoforged.bus.api.Event {
    protected RenderGuiLayerEvent() {}
    public DeltaTracker getPartialTick() { throw new UnsupportedOperationException(); }
    public Identifier getName() { throw new UnsupportedOperationException(); }
}
