/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Offline, the CLI and AOT decide each vanilla class move from the game jar they indexed, as the game does. */
class OfflineClassMoveIndexTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @Test
    @DisplayName("A 1.21.11 target takes the moves its game jar has made, and no others")
    void indexDecidesEachMove(@TempDir Path directory) throws Exception {
        // 1.21.11 moved Util into net/minecraft/util; the other 26.1 moves came later.
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = newTransformer();
        transformer.initFuzzyResolver(hostJar(directory, "net/minecraft/util/Util"));

        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals("net/minecraft/util/Util", redirects.get("net/minecraft/Util"),
                "a NeoForge 1.21.1 mod's Util reference must follow the move 1.21.11 made");
        String laterMove = IntermediaryToMojangMapper.getInstance().getClassMoves().entrySet().stream()
                .filter(move -> move.getValue().startsWith("net/minecraft/")
                        && !move.getValue().equals("net/minecraft/util/Util"))
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow();
        assertFalse(redirects.containsKey(laterMove),
                laterMove + " moved after 1.21.11, so a 1.21.11 target must keep its old name");
    }

    @Test
    @DisplayName("Without a game jar, a target before 26.1 takes no vanilla moves")
    void noIndexTakesNoMoves() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = newTransformer();

        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);

        assertNull(transformer.getClassRedirects().get("net/minecraft/Util"));
    }

    private static RetromodTransformer newTransformer() throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** A game jar holding only empty classes with these names. */
    private static Path hostJar(Path directory, String... classes) throws Exception {
        Path jar = directory.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : classes) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
                writer.visitEnd();
                out.putNextEntry(new JarEntry(name + ".class"));
                out.write(writer.toByteArray());
                out.closeEntry();
            }
        }
        return jar;
    }
}
