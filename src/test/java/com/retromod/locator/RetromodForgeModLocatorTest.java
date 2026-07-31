package com.retromod.locator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Host-independent coverage for {@link RetromodForgeModLocator} (#78). Forge's classes
 * aren't on the test classpath, so the reflective delegation can't resolve. The point
 * of these tests is that the locator then <em>soft-fails to an empty list and never
 * throws</em>, so it can't disrupt Forge's mod discovery. (The real jar→IModFile path
 * is exercised in-game on a Forge 26.2 instance.)
 */
class RetromodForgeModLocatorTest {

    @Test
    void scanModsSoftFailsToEmptyWhenForgeAbsent() {
        RetromodForgeModLocator loc = new RetromodForgeModLocator();
        assertDoesNotThrow(() -> {
            var result = loc.scanMods();
            assertNotNull(result, "scanMods must never return null");
            assertTrue(result.isEmpty(), "no Forge on the classpath → nothing located");
        });
    }

    @Test
    void overrideToNonexistentFolderIsEmpty() {
        String prev = System.getProperty(RetromodForgeModLocator.OVERRIDE_PROPERTY);
        System.setProperty(RetromodForgeModLocator.OVERRIDE_PROPERTY, "/nonexistent/retromod-forge-test-xyz");
        try {
            assertTrue(new RetromodForgeModLocator().scanMods().isEmpty());
        } finally {
            if (prev == null) {
                System.clearProperty(RetromodForgeModLocator.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(RetromodForgeModLocator.OVERRIDE_PROPERTY, prev);
            }
        }
    }

    @Test
    void forgeIModLocatorServiceMustNotBeRegistered() {
        // #102 (Forge) + #90 (NeoForge): a mods/ jar declaring a locator SPI gets claimed onto the
        // early-service layer, where it is not scanned as a @Mod. On Forge that stopped Retromod
        // initializing at all (#102); NeoForge 26.2's IModFileCandidateLocator was found to have the
        // SAME trap in-game (#90, RetromodNeoForge never constructed). So NEITHER service is registered
        // in the main jar; the locator classes stay (com/retromod/** uniform for the self-hash), and
        // CF-export loading from mods/Retromod/ moves to a separate stub jar.
        assertNull(getClass().getResourceAsStream(
                        "/META-INF/services/net.minecraftforge.forgespi.locating.IModLocator"),
                "Forge IModLocator service must NOT be registered (it blocks Retromod's @Mod on Forge, #102)");
        assertNull(getClass().getResourceAsStream(
                        "/META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator"),
                "NeoForge IModFileCandidateLocator service must NOT be registered either (same @Mod trap, #90)");
    }

    @Test
    void providerMethodsAreTrivialAndSafe() {
        RetromodForgeModLocator loc = new RetromodForgeModLocator();
        assertEquals("retromod folder locator", loc.name());
        assertTrue(loc.isValid(null));
        assertDoesNotThrow(() -> loc.scanFile(null, p -> { }));
        assertDoesNotThrow(() -> loc.initArguments(java.util.Map.of()));
    }
}
