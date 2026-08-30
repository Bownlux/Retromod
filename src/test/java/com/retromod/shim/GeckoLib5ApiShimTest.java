/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
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
 * GeckoLib 5 renamed its package and reorganised the library at once, so a mod built against 4.x
 * fails on its first animated item with {@code NoClassDefFoundError} naming a class that is simply
 * gone (#223). The table is derived from the two jars, so these tests check it against them.
 */
class GeckoLib5ApiShimTest {

    @AfterEach
    void clearRedirects() {
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
    @DisplayName("The whole table registers as class redirects")
    void tableRegisters() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new GeckoLib5ApiShim().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertTrue(GeckoLib5ApiShim.classMoves().size() > 100,
                "saw " + GeckoLib5ApiShim.classMoves().size() + " moves");
        GeckoLib5ApiShim.classMoves().forEach((from, to) ->
                assertEquals(to, redirects.get(from), from));
    }

    @Test
    @DisplayName("It is held back from hosts whose GeckoLib is still 4.x")
    void heldBackBelowTheBoundary() {
        // GeckoLib 4.x tops out at 1.21.4 and 5.x starts at 1.21.5. Rewriting below that would
        // point a mod at packages the host's own GeckoLib does not ship.
        GeckoLib5ApiShim shim = new GeckoLib5ApiShim();
        for (String host : List.of("1.20.1", "1.21.1", "1.21.4")) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, host),
                    host + " still ships GeckoLib 4, so the rewrite must not apply");
        }
        for (String host : List.of("1.21.5", "1.21.11", "26.1.2", "26.2")) {
            assertTrue(ShimRegistry.isAvailableOnHost(shim, host),
                    host + " ships GeckoLib 5, so the rewrite applies");
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
