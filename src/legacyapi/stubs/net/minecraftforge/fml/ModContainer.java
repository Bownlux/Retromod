// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml;

import net.minecraftforge.eventbus.api.bus.BusGroup;

public abstract class ModContainer {
    protected ModContainer() {}

    public final String getModId() { throw new UnsupportedOperationException(); }

    public final String getNamespace() { throw new UnsupportedOperationException(); }

    public abstract boolean matches(Object mod);

    public abstract Object getMod();

    public BusGroup getModBusGroup() { throw new UnsupportedOperationException(); }
}
