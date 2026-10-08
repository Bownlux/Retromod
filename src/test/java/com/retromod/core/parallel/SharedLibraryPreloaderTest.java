/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minepathy's first launch on 26.2 hung: one transform worker held Knot's lock for an ASM analysis
 * class and waited for Mixin's lock, while another worker held Mixin's lock and MixinExtras waited
 * for that same ASM class. Loading the shared ASM packages before the workers start removes the
 * class both sides were racing to load.
 */
class SharedLibraryPreloaderTest {

    /** Records every class requested through it, then delegates to the test class loader. */
    private static final class RecordingLoader extends ClassLoader {
        final Set<String> requested = ConcurrentHashMap.newKeySet();

        RecordingLoader() {
            super(SharedLibraryPreloaderTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            requested.add(name);
            return super.loadClass(name, resolve);
        }
    }

    @Test
    @DisplayName("the analysis classes MixinExtras loads are loaded before any worker runs")
    void preloadsTheAnalysisPackage() {
        RecordingLoader loader = new RecordingLoader();

        int loaded = SharedLibraryPreloader.preloadPackages(loader);

        assertTrue(loaded > 1, "the whole package must be loaded, not just its anchor class");
        for (String name : new String[]{
                "org.objectweb.asm.tree.analysis.SourceInterpreter",
                "org.objectweb.asm.tree.analysis.Frame",
                "org.objectweb.asm.tree.analysis.SourceValue"}) {
            assertTrue(loader.requested.contains(name),
                    name + " is what the deadlocked workers were both trying to load");
        }
    }

    @Test
    @DisplayName("a loader without ASM is a no-op rather than an error")
    void missingAsmIsHarmless() {
        ClassLoader empty = new ClassLoader(null) {};
        assertEquals(0, SharedLibraryPreloader.preloadPackages(empty));
    }
}
