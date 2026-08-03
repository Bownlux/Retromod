/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The background AOT pass names each cached file after a jar entry. An entry name is untrusted,
 * so one that climbs out of the cache directory would have Retromod write a mod's bytes to a path
 * the mod chose.
 */
class AotCacheNameSafetyTest {

    @Test
    @DisplayName("An ordinary class name is cacheable")
    void acceptsNormalClassNames() {
        assertTrue(HybridTransformationEngine.isCacheableClassName("com/example/mod/Thing"));
        assertTrue(HybridTransformationEngine.isCacheableClassName("Thing"));
        assertTrue(HybridTransformationEngine.isCacheableClassName("a/b/C$Inner"));
    }

    @Test
    @DisplayName("A name that climbs out of the cache directory is refused")
    void rejectsTraversal() {
        assertFalse(HybridTransformationEngine.isCacheableClassName("../../../../mods/evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("com/../../evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("/etc/passwd"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("./evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("com//evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName(""));
    }

    @Test
    @DisplayName("A Windows-style or drive-qualified name is refused")
    void rejectsPlatformSpecificEscapes() {
        assertFalse(HybridTransformationEngine.isCacheableClassName("..\\..\\mods\\evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("C:/windows/evil"));
    }

    @Test
    @DisplayName("The write itself also refuses a path outside the cache")
    void writeSiteRefusesTraversal() {
        Path cache = Path.of("config/retromod/aot-cache");
        assertThrows(IOException.class,
                () -> ZipSecurity.safeResolve(cache, "../../../../mods/evil.class"),
                "the second guard at the write must reject it too");
        assertDoesNotThrow(() -> ZipSecurity.safeResolve(cache, "com/example/Thing.class"));
    }
}
