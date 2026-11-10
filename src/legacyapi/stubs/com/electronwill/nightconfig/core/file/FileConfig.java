// Compile-only stub of the NightConfig type NeoForge 1.21.1 to 26.3 ship, for src/legacyapi; never packaged.
package com.electronwill.nightconfig.core.file;

import java.nio.file.Path;

public interface FileConfig {
    void save();

    Path getNioPath();
}
