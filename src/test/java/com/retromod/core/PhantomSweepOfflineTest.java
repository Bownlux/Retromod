/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Offline, as in the CLI, the phantom sweep keeps a shipped class whose supertype only the game supplies. */
class PhantomSweepOfflineTest {

    private static final Path CLASSES = Path.of("target/classes");
    private static final String PLUGIN = "com/llamalad7/mixinextras/MixinExtrasPlugin";
    private static final String PLUGIN_COMPAT =
            "com/retromod/shim/api/common/embedded/MixinExtrasShim$PluginCompat";
    private static final String GHOST = "com/retromod/ghost/OfflineMissing";

    @Test
    @DisplayName("the CLI keeps a redirect to a shipped class that needs Mixin to link, and drops a missing one")
    void shippedClassNeedingMixinIsNotAPhantomOffline() throws Exception {
        try (URLClassLoader offline = cliLikeLoader()) {
            // Every loader supplies Mixin in game, but the CLI jar does not carry it.
            assertThrows(NoClassDefFoundError.class,
                    () -> Class.forName(PLUGIN_COMPAT.replace('/', '.'), false, offline),
                    "this loader must mirror the CLI, where PluginCompat cannot link");

            Class<?> type = Class.forName(RetromodTransformer.class.getName(), true, offline);
            Object transformer = type.getMethod("getInstance").invoke(null);
            type.getMethod("resetOfflineRegistrations").invoke(transformer);
            var redirect = type.getMethod("registerClassRedirect", String.class, String.class);
            redirect.invoke(transformer, PLUGIN, PLUGIN_COMPAT);
            redirect.invoke(transformer, "net/example/GhostSource", GHOST);

            assertEquals(Set.of(GHOST),
                    type.getMethod("collectPhantomComRetromodTargets").invoke(transformer),
                    "only the class Retromod does not ship is a phantom");
            byte[] out = (byte[]) type.getMethod("transformClass", byte[].class, String.class)
                    .invoke(transformer, pluginUser(), "test/PluginUser");
            assertTrue(new String(out, StandardCharsets.ISO_8859_1).contains(PLUGIN_COMPAT),
                    "the CLI must rename MixinExtrasPlugin to PluginCompat, as the game does");
        }
    }

    /** Retromod's classes plus the libraries the CLI jar bundles, and nothing a loader provides. */
    private static URLClassLoader cliLikeLoader() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(CLASSES.toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.contains("/org/ow2/asm/") || entry.contains("/org/slf4j/")
                    || entry.contains("/com/google/code/gson/") || entry.contains("/com/moandjiezana/")) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /** A mod class that holds a MixinExtrasPlugin, as a mixin config plugin field would. */
    private static byte[] pluginUser() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "test/PluginUser", null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "plugin", "L" + PLUGIN + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
