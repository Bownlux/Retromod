/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetromodScreenConfigTest {

    @Test
    void forceTranslateSettingUsesTheBoundedConfigReader(@TempDir Path gameDirectory)
            throws Exception {
        Path config = gameDirectory.resolve("config/retromod/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"force_translate_complex\":true,\"padding\":"
                + "[".repeat(257) + "0" + "]".repeat(257) + "}");
        RetromodScreen screen = new RetromodScreen(null);
        Method method = RetromodScreen.class.getDeclaredMethod(
                "isForceTranslateEnabled", Path.class);
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(screen, gameDirectory));

        Files.writeString(config, "{\"force_translate_complex\":true}");
        assertTrue((boolean) method.invoke(screen, gameDirectory));
    }
}
