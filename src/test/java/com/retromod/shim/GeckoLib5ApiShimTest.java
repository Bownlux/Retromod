/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.api.common.GeckoLib5ApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeckoLib 5 reorganised the library and 5.5 moved its package, so a mod built against 4.x fails on
 * its first animated item with {@code NoClassDefFoundError} naming a class that is simply gone
 * (#223). The table is derived from the two jars, so these tests check it against them.
 */
class GeckoLib5ApiShimTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void clearRedirects() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The class a reported crash named is redirected")
    void reportedCrashClassIsRedirected() {
        assertEquals("com/geckolib/animatable/GeoItem",
                GeckoLib5ApiShim.classMoves().get("software/bernie/geckolib/animatable/GeoItem"),
                "this is the exact class the crash in #223 could not find");
    }

    @Test
    @DisplayName("The whole table registers as class redirects where GeckoLib uses com.geckolib")
    void tableRegisters() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // No GeckoLib is readable here, so the host decides: 26.3's GeckoLib 5.5.7 uses com.geckolib.
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new GeckoLib5ApiShim().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertTrue(GeckoLib5ApiShim.classMoves().size() > 100,
                "saw " + GeckoLib5ApiShim.classMoves().size() + " moves");
        GeckoLib5ApiShim.classMoves().forEach((from, to) ->
                assertEquals(to, redirects.get(from), from));
    }

    @Test
    @DisplayName("It is held back from hosts whose GeckoLib is still 4.x and moves nothing on 1.21.5 to 1.21.11")
    void heldBackBelowTheBoundary() {
        // GeckoLib 4.x tops out at 1.21.4, so those hosts are never offered the shim.
        GeckoLib5ApiShim shim = new GeckoLib5ApiShim();
        for (String host : List.of("1.20.1", "1.21.1", "1.21.4")) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, host),
                    host + " still ships GeckoLib 4, so the rewrite must not apply");
        }
        // GeckoLib 5.0 to 5.4.5 still use software.bernie.geckolib, so the shim is offered there but moves nothing.
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        for (String host : List.of("1.21.5", "1.21.8", "1.21.11")) {
            assertTrue(ShimRegistry.isAvailableOnHost(shim, host), host);
            transformer.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = host;
            shim.registerRedirects(transformer);
            assertNull(transformer.getClassRedirects().get("software/bernie/geckolib/animatable/GeoItem"),
                    host + " ships GeckoLib with software.bernie names, so they must stay");
        }
    }

    @Test
    @DisplayName("Every move starts in GeckoLib 4 and lands in GeckoLib 5")
    void everyMoveIsRealInBothJars() throws Exception {
        Path four = fixture("geckolib-forge-1.20.1-4.8.4.jar");
        Path five = fixture("geckolib-neoforge-26.1.2-5.5.2.jar");
        Assumptions.assumeTrue(four != null && five != null, "geckolib fixtures present");

        Set<String> old = classNames(four);
        Set<String> current = classNames(five);
        GeckoLib5ApiShim.classMoves().forEach((from, to) -> {
            assertTrue(old.contains(from), "GeckoLib 4.8.4 has no " + from);
            assertTrue(current.contains(to), "GeckoLib 5.5.2 has no " + to);
            assertFalse(current.contains(from),
                    "GeckoLib 5.5.2 still ships " + from + ", so redirecting it would be wrong");
        });
    }

    private static Path fixture(String name) {
        Path candidate = Path.of("test-jars-mixin", name);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static Set<String> classNames(Path jar) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(n -> n.endsWith(".class"))
                    .forEach(n -> names.add(n.substring(0, n.length() - 6)));
        }
        return names;
    }
}
