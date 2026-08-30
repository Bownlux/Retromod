/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackTextureMappingInvariantTest {

    private static final Pattern SAFE_BASENAME = Pattern.compile("[a-z0-9._-]+");

    @Test
    void textureMappingTablesContainSafeOneToOneBasenames() throws Exception {
        var mappingFields = Arrays.stream(ResourcePackTransformer.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> Map.class.isAssignableFrom(field.getType()))
                .filter(field -> field.getName().startsWith("TEXTURE_RENAMES"))
                .toList();

        assertFalse(mappingFields.isEmpty(), "expected at least one texture mapping table");
        for (var field : mappingFields) {
            field.setAccessible(true);
            Map<?, ?> mappings = (Map<?, ?>) field.get(null);
            assertFalse(mappings.isEmpty(), field.getName() + " must not be empty");

            Set<String> destinations = new HashSet<>();
            for (var entry : mappings.entrySet()) {
                assertTrue(entry.getKey() instanceof String,
                        field.getName() + " contains a non-string source");
                assertTrue(entry.getValue() instanceof String,
                        field.getName() + " contains a non-string destination");
                String source = (String) entry.getKey();
                String destination = (String) entry.getValue();
                assertTrue(SAFE_BASENAME.matcher(source).matches(),
                        () -> field.getName() + " has an unsafe source basename: " + source);
                assertTrue(SAFE_BASENAME.matcher(destination).matches(),
                        () -> field.getName() + " has an unsafe destination basename: "
                                + destination);
                assertNotEquals(source, destination,
                        () -> field.getName() + " has a no-op mapping: " + source);
                assertTrue(destinations.add(destination),
                        () -> field.getName() + " maps multiple sources to: " + destination);
            }
        }
    }

    @Test
    void legacyTexturePathMappingsAreSafeAndOneToOne() {
        Pattern safePath = Pattern.compile("[a-z0-9._/-]+");
        Set<String> destinations = new HashSet<>();
        for (var entry : LegacyTexturePathMappings.mappings().entrySet()) {
            String source = entry.getKey();
            String destination = entry.getValue();
            assertTrue(safePath.matcher(source).matches(), "unsafe source path: " + source);
            assertTrue(safePath.matcher(destination).matches(),
                "unsafe destination path: " + destination);
            assertFalse(source.startsWith("/") || source.contains("../"),
                "source path escapes the texture root: " + source);
            assertFalse(destination.startsWith("/") || destination.contains("../"),
                "destination path escapes the texture root: " + destination);
            assertNotEquals(source, destination, "no-op texture path mapping: " + source);
            assertTrue(destinations.add(destination),
                "multiple texture paths map to: " + destination);
        }
    }
}
