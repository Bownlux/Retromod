/*
 * Retromod Test Native (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testnative.neoforge;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A mod NeoForge 1.21.1 runs as it is, which checks that Retromod left it alone on that host. */
@Mod("retromod_test_native_neoforge")
public class RetromodTestNativeNeoForge {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");
    private static final String CHECK = "[Retromod-Test-NeoForge] native: a jar NeoForge 1.21.1 runs as it is"
            + " is translated only on newer hosts";

    public RetromodTestNativeNeoForge() {
        // The test mod itself cannot run untranslated on 1.21.1 (#87), so this jar reports for that host.
        String host = inPlaceHost();
        if (host == null) {
            LOG.info("{}: skipped (not in-place mode)", CHECK);
            return;
        }
        boolean expected = !host.equals("1.21.1") && !host.equals("1.21");
        boolean translated = translatedByRetromod();
        if (translated == expected) {
            LOG.info("{}: success (host {}, translated {})", CHECK, host, translated);
        } else {
            LOG.warn("{}: fail: translated {} on host {}", CHECK, translated, host);
        }
    }

    /** The Minecraft version the in-place status file names, or null outside in-place mode. */
    private static String inPlaceHost() {
        String status = System.getProperty("retromod.inplace.status");
        if (status == null || !"in-place".equals(System.getProperty("retromod.inplace.mode"))) return null;
        try {
            return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(status)))
                    .getAsJsonObject().get("minecraft").getAsString();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Whether this jar carries Retromod's output stamp; the title check proves the manifest is this jar's own. */
    private static boolean translatedByRetromod() {
        try (java.io.InputStream in = RetromodTestNativeNeoForge.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            java.util.jar.Attributes main = new java.util.jar.Manifest(in).getMainAttributes();
            if (!"retromod-test-native-neoforge".equals(main.getValue("Implementation-Title"))) {
                throw new IllegalStateException("read another jar's manifest: " + main.getValue("Implementation-Title"));
            }
            return "true".equalsIgnoreCase(main.getValue("Retromod-Transformed"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
