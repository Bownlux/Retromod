// Compile-only stub of a NeoForge 1.21.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common;

import net.neoforged.fml.config.IConfigSpec;

public class ModConfigSpec implements IConfigSpec {
    @Override
    public void acceptConfig(ILoadedConfig config) { throw new UnsupportedOperationException(); }

    public static class ConfigValue<T> {
        public T get() { throw new UnsupportedOperationException(); }

        public T getDefault() { throw new UnsupportedOperationException(); }
    }
}
