/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The unusable-base error names the class moves behind a base, and never guesses Minecraft's history. */
class UnusableBaseOriginTest {

    private static final String WEIGHTED_LIST = "net/minecraft/util/random/WeightedList";
    private static final String WEIGHTED_RANDOM_LIST = "net/minecraft/util/random/WeightedRandomList";
    private static final String SIMPLE_WEIGHTED_RANDOM_LIST = "net/minecraft/util/random/SimpleWeightedRandomList";
    private static final String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";

    /** The 26.1 rows that fold both weighted lists into one, listed unsorted, plus an unrelated row. */
    private static Map<String, String> moves() {
        Map<String, String> moves = new LinkedHashMap<>();
        moves.put(WEIGHTED_RANDOM_LIST, WEIGHTED_LIST);
        moves.put("net/minecraft/world/level/block/OldBlock", "net/minecraft/world/level/block/NewBlock");
        moves.put(SIMPLE_WEIGHTED_RANDOM_LIST, WEIGHTED_LIST);
        return moves;
    }

    @Test
    @DisplayName("lists every class move that produces a name, sorted, and ignores the others")
    void redirectSourcesOfListsEveryMoveSorted() {
        assertEquals(List.of(), RetromodTransformer.redirectSourcesOf(moves(), MODEL_PART),
                "a name no row produces has no source");
        assertEquals(List.of("net/minecraft/world/level/block/OldBlock"),
                RetromodTransformer.redirectSourcesOf(moves(), "net/minecraft/world/level/block/NewBlock"));
        assertEquals(List.of(SIMPLE_WEIGHTED_RANDOM_LIST, WEIGHTED_RANDOM_LIST),
                RetromodTransformer.redirectSourcesOf(moves(), WEIGHTED_LIST),
                "both weighted lists produce WeightedList, in a stable order");
    }

    @Test
    @DisplayName("names the table, the one move, or every move, and never says Minecraft removed or changed it")
    void unusableBaseOriginStatesOnlyRetromodsTable() {
        String none = RetromodTransformer.unusableBaseOrigin(MODEL_PART, List.of());
        String one = RetromodTransformer.unusableBaseOrigin(WEIGHTED_LIST, List.of(WEIGHTED_RANDOM_LIST));
        String several = RetromodTransformer.unusableBaseOrigin(WEIGHTED_LIST,
                List.of(SIMPLE_WEIGHTED_RANDOM_LIST, WEIGHTED_RANDOM_LIST));

        assertEquals("No class move in Retromod's tables produces this name.", none);
        assertEquals("It came from the class move " + WEIGHTED_RANDOM_LIST + " -> " + WEIGHTED_LIST + ".", one);
        assertEquals("Several class moves produce this name: " + SIMPLE_WEIGHTED_RANDOM_LIST + ", "
                + WEIGHTED_RANDOM_LIST + ".", several);
        // An interface, final, or sealed verdict proves the class exists on the host.
        for (String origin : List.of(none, one, several)) {
            assertFalse(origin.contains("removed"), origin);
            assertFalse(origin.contains("changed"), origin);
        }
    }

    @Test
    @DisplayName("the logged error for an interface base and for a base two moves produce cites the table")
    void loggedErrorCitesTheTable(@TempDir Path dir) throws Exception {
        String placementModifier = "net/minecraft/world/level/levelgen/placement/PlacementModifier";
        Path host = dir.resolve("host-26.3.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(host))) {
            put(jar, placementModifier, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE);
            put(jar, WEIGHTED_LIST, ACC_PUBLIC | ACC_FINAL);
        }
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        try {
            // A fresh transformer, so the per-base report has not fired for these names yet.
            Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            RetromodTransformer transformer = constructor.newInstance();
            moves().forEach(transformer::registerClassRedirect);
            transformer.initFuzzyResolver(host);

            String log = LoaderApiTestSupport.captureErr(() -> {
                transformer.transformClass(subclass("mod/RarityFilter", placementModifier), "mod/RarityFilter");
                transformer.transformClass(subclass("mod/Loot", SIMPLE_WEIGHTED_RANDOM_LIST), "mod/Loot");
            });

            assertTrue(errorLine(log, "mod/RarityFilter")
                    .contains("No class move in Retromod's tables produces this name."), log);
            assertTrue(errorLine(log, "mod/Loot").contains("Several class moves produce this name: "
                    + SIMPLE_WEIGHTED_RANDOM_LIST + ", " + WEIGHTED_RANDOM_LIST + "."), log);
            assertFalse(log.contains("removed it outright"), "both classes exist on the host:\n" + log);
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }
    }

    private static void put(JarOutputStream jar, String name, int access) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, access, name, null, "java/lang/Object", null);
        cw.visitEnd();
        jar.putNextEntry(new JarEntry(name + ".class"));
        jar.write(cw.toByteArray());
        jar.closeEntry();
    }

    private static byte[] subclass(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String errorLine(String log, String className) {
        return log.lines().filter(line -> line.contains("WARN") && line.contains(className + " extends"))
                .findFirst().orElseThrow(() -> new AssertionError("no warning for " + className + ":\n" + log));
    }
}
