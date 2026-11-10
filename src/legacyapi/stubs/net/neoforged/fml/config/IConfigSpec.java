// Compile-only stub of a NeoForge 1.21.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.fml.config;

import com.electronwill.nightconfig.core.CommentedConfig;

public interface IConfigSpec {
    void acceptConfig(ILoadedConfig config);

    interface ILoadedConfig {
        CommentedConfig config();

        void save();
    }
}
