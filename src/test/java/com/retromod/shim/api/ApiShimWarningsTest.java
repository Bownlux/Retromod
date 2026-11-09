/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api;

import com.retromod.shim.api.fabric.embedded.FabricItemGroupBuilderShim;
import com.retromod.shim.api.forge.embedded.CapabilityProviderShim;
import com.retromod.shim.api.forge.embedded.CuriosShim;
import com.retromod.shim.api.forge.embedded.JeiShim;
import com.retromod.shim.api.forge.embedded.LazyOptionalShim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A third-party API shim that gives up on an old mod's call says so once, naming the API, the call,
 * the mod class and the mod's own exception. Each test uses its own fake receiver class, because a
 * warning is remembered for the whole run.
 */
public class ApiShimWarningsTest {

    @Test
    @DisplayName("a capability provider that throws gets one warning naming it and its own exception")
    void capabilityProviderFailureWarnsOnce() throws Exception {
        String output = captureErr(() -> {
            LazyOptionalShim<?> first = CapabilityProviderShim.getCapability(new ThrowingProvider(), "energy", null);
            assertFalse(first.isPresent(), "the shim still answers with an empty capability");
            CapabilityProviderShim.getCapability(new ThrowingProvider(), "energy", null);
        });

        assertEquals(1, count(output, ThrowingProvider.class.getName()), "one warning per provider class: " + output);
        assertTrue(output.contains("java.lang.IllegalStateException: boom"), "the mod's own exception: " + output);
        assertFalse(output.contains("InvocationTargetException"), "the reflection wrapper is unwrapped: " + output);
    }

    @Test
    @DisplayName("a JEI registration that throws while adding recipes is named once")
    void jeiAddRecipesFailureWarnsOnce() throws Exception {
        String output = captureErr(() -> {
            JeiShim.addRecipesLegacy(new ThrowingRegistration(), List.of("recipe"), "oldmod:uid");
            JeiShim.addRecipesLegacy(new ThrowingRegistration(), List.of("recipe"), "oldmod:uid");
        });

        String warning = "call to addRecipes on " + ThrowingRegistration.class.getName();
        assertEquals(1, count(output, warning), "one warning per registration class: " + output);
        assertTrue(output.contains("java.lang.UnsupportedOperationException: no recipes here"), output);
    }

    @Test
    @DisplayName("a Curios slot preset that throws returns unknown and warns once; a null preset does not throw")
    void curiosSlotIdentifierFailureWarnsOnce() throws Exception {
        String output = captureErr(() -> {
            assertEquals("unknown", CuriosShim.getSlotIdentifier(new ThrowingPreset()));
            assertEquals("unknown", CuriosShim.getSlotIdentifier(new ThrowingPreset()));
            assertEquals("unknown", CuriosShim.getSlotIdentifier(null));
        });

        assertEquals(1, count(output, "getSlotIdentifier on " + ThrowingPreset.class.getName()), output);
        assertTrue(output.contains("java.lang.IllegalArgumentException: bad slot"), output);
    }

    @Test
    @DisplayName("a JEI focus answered by its older getValue stays quiet")
    void workingFallbackStaysQuiet() throws Exception {
        Object[] value = new Object[1];
        String output = captureErr(() -> value[0] = JeiShim.getFocusValue(new OldFocus()));

        assertEquals("diamond", value[0]);
        assertFalse(output.contains(OldFocus.class.getName()), "a fallback that works is not a failure: " + output);
    }

    @Test
    @DisplayName("an old item group built without Fabric API returns null and warns once")
    void itemGroupWithoutFabricApiWarnsOnce() throws Exception {
        String output = captureErr(() -> {
            assertNull(FabricItemGroupBuilderShim.build(null, null));
            assertNull(FabricItemGroupBuilderShim.build(null, null));
        });

        assertEquals(1, count(output, "Fabric item group compatibility: an old mod's call to build failed"), output);
    }

    // --- fake mod classes ---------------------------------------------------------------------

    public static final class ThrowingProvider {
        public Object getCapability(Object capability, Object direction) {
            throw new IllegalStateException("boom");
        }
    }

    public static final class ThrowingRegistration {
        public void addRecipes(Object type, Collection<?> recipes) {
            throw new UnsupportedOperationException("no recipes here");
        }
    }

    public static final class ThrowingPreset {
        public String getIdentifier() {
            throw new IllegalArgumentException("bad slot");
        }
    }

    public static final class OldFocus {
        public Object getValue() {
            return "diamond";
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String captureErr(ThrowingRunnable action) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
