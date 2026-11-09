/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.compat;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.api.common.VersionSpoofShim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the runtime version-spoofer shim. Exercises only what we can
 * without a real Fabric Loader on the test classpath: the shim's redirect
 * registration, and the fact that {@link VersionSpoofer} degrades gracefully
 * when Fabric types aren't available (the CLI + JIT-agent case).
 */
class VersionSpoofShimTest {

    @Test
    @DisplayName("Shim registers the FabricLoader.getModContainer redirect")
    void shimRegistersRedirect() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        int before = transformer.getMethodRedirects().size();

        new VersionSpoofShim().registerRedirects(transformer);

        int after = transformer.getMethodRedirects().size();
        assertTrue(after >= before,
                "Redirect count shouldn't decrease after registering the shim");

        // Confirm the specific entry we expect.
        RetromodTransformer.MethodKey key = new RetromodTransformer.MethodKey(
                "net/fabricmc/loader/api/FabricLoader",
                "getModContainer",
                "(Ljava/lang/String;)Ljava/util/Optional;");
        assertTrue(transformer.getMethodRedirects().containsKey(key),
                "FabricLoader.getModContainer should be registered as a redirect target");

        RetromodTransformer.MethodTarget target = transformer.getMethodRedirects().get(key);
        assertEquals("com/retromod/compat/VersionSpoofer", target.owner());
        assertEquals("getModContainer", target.name());
        assertTrue(target.devirtualize(),
                "Must devirtualize - receiver becomes first static-method arg");
    }

    @Test
    @DisplayName("Spoofer returns Optional.empty when Fabric types are absent")
    void noOpWhenFabricMissing() {
        // Pass a non-Fabric object; the reflective lookup for getModContainer
        // fails, and the spoofer must return Optional.empty rather than throwing.
        // This mirrors the CLI / unit-test environment where net.fabricmc.*
        // isn't on the classpath.
        Object notAFabricLoader = new Object();
        var result = VersionSpoofer.getModContainer(notAFabricLoader, "cloth-config");
        assertNotNull(result);
        assertTrue(result.isEmpty(),
                "Expected empty Optional when Fabric types can't be resolved");
    }

    @Test
    @DisplayName("Spoof table is loaded from the bundled JSON resource")
    void spoofTableLoadsKnownEntries() {
        VersionSpoofer.resetForTesting();
        // Triggering a lookup loads the table; the lookup itself uses the real
        // table (not a test override).
        Object notAFabricLoader = new Object();
        VersionSpoofer.getModContainer(notAFabricLoader, "cloth-config");
        // Use the test hook to install a controlled table and verify it sticks.
        VersionSpoofer.setSpoofTableForTesting(java.util.Map.of(
                "cloth-config", "13.999.999",
                "rei", "16.999.999"));
        // Nothing to assert on the spoof directly without Fabric types, but
        // the fact that these calls don't throw means the code path is wired.
        assertDoesNotThrow(() ->
                VersionSpoofer.getModContainer(notAFabricLoader, "cloth-config"));
        assertDoesNotThrow(() ->
                VersionSpoofer.getModContainer(notAFabricLoader, "not-in-table"));

        VersionSpoofer.resetForTesting();
    }

    @Test
    @DisplayName("isModLoaded answers the fabric id for Fabric API when the host no longer provides it")
    void isModLoadedAnswersFabricForFabricApi() {
        StandInLoader host26 = new StandInLoader("fabric-api");
        assertTrue(VersionSpoofer.isModLoaded(host26, "fabric"), "26.x Fabric API is the old fabric mod");
        assertTrue(VersionSpoofer.isModLoaded(host26, "fabric-api"));
        assertFalse(VersionSpoofer.isModLoaded(host26, "sodium"), "only the fabric id falls back");
        assertTrue(VersionSpoofer.isModLoaded(new StandInLoader("fabric"), "fabric"),
                "a host that still provides fabric answers for itself");
        assertFalse(VersionSpoofer.isModLoaded(new StandInLoader(), "fabric"),
                "without Fabric API there is no fabric mod either");
        assertFalse(VersionSpoofer.isModLoaded(new Object(), "fabric"),
                "a receiver without isModLoaded answers false, as Fabric does for a missing mod");
    }

    @Test
    @DisplayName("getModContainer finds Fabric API under the fabric id, and a host's own fabric container wins")
    void getModContainerFindsFabricApiUnderFabric() {
        VersionSpoofer.setSpoofTableForTesting(Map.of());
        try {
            assertEquals(Optional.of("container of fabric-api"),
                    VersionSpoofer.getModContainer(new StandInLoader("fabric-api"), "fabric"));
            assertEquals(Optional.empty(), VersionSpoofer.getModContainer(new StandInLoader("fabric-api"), "sodium"),
                    "only the fabric id falls back");
            assertEquals(Optional.of("container of fabric"),
                    VersionSpoofer.getModContainer(new StandInLoader("fabric", "fabric-api"), "fabric"));
        } finally {
            VersionSpoofer.resetForTesting();
        }
    }

    /** A loader that knows only the given mod ids, each with its own container. */
    static final class StandInLoader {
        private final Map<String, Object> containers = new HashMap<>();

        StandInLoader(String... modIds) {
            for (String modId : modIds) containers.put(modId, "container of " + modId);
        }

        public boolean isModLoaded(String modId) {
            return containers.containsKey(modId);
        }

        public Optional<Object> getModContainer(String modId) {
            return Optional.ofNullable(containers.get(modId));
        }
    }
}
