// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.gui.GuiLayer;

public class RegisterGuiLayersEvent extends net.neoforged.bus.api.Event {
    private RegisterGuiLayersEvent() {}
    public void registerBelowAll(Identifier id, GuiLayer layer) { throw new UnsupportedOperationException(); }
    public void registerBelow(Identifier other, Identifier id, GuiLayer layer) { throw new UnsupportedOperationException(); }
    public void registerAbove(Identifier other, Identifier id, GuiLayer layer) { throw new UnsupportedOperationException(); }
    public void registerAboveAll(Identifier id, GuiLayer layer) { throw new UnsupportedOperationException(); }
}
