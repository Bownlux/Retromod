// Compile-only stub of a NeoForge 1.21.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.fml;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

public abstract class ModContainer {
    public final String getModId() { throw new UnsupportedOperationException(); }
    public final String getNamespace() { throw new UnsupportedOperationException(); }
    public void registerConfig(ModConfig.Type type, IConfigSpec configSpec, String fileName) { throw new UnsupportedOperationException(); }
    public abstract IEventBus getEventBus();
}
