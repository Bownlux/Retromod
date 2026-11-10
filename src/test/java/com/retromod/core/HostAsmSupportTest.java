/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** HostAsmSupport names the loader's ASM release and the Minecraft class files it cannot read. */
class HostAsmSupportTest {

    private static final int JAVA_21 = 65;
    private static final int JAVA_25 = 69;

    @Test
    @DisplayName("Minecraft's class version comes from its main class header")
    void readsTheMainClassHeader() {
        assertEquals(JAVA_25, HostAsmSupport.minecraftClassVersion(gameWithMainClass(JAVA_25), "1.21.1"));
        assertEquals(JAVA_21, HostAsmSupport.minecraftClassVersion(gameWithMainClass(JAVA_21), "26.1"));
    }

    @Test
    @DisplayName("without a readable main class, the release number decides the class version")
    void fallsBackToTheRelease() {
        ClassLoader empty = new ClassLoader(null) {};
        assertEquals(JAVA_25, HostAsmSupport.minecraftClassVersion(empty, "26.1.2"));
        assertEquals(JAVA_21, HostAsmSupport.minecraftClassVersion(empty, "1.21.11"));
        assertEquals(JAVA_21, HostAsmSupport.minecraftClassVersion(null, "unknown"));
    }

    @Test
    @DisplayName("the ASM Retromod compiles against reads Java 25 and reports no problem")
    void currentAsmHasNoProblem() {
        assertTrue(HostAsmSupport.reads(JAVA_25));
        assertFalse(HostAsmSupport.reads(200), "No ASM release reads class version 200");
        assertNull(HostAsmSupport.problem(gameWithMainClass(JAVA_25), "26.1"));
    }

    @Test
    @DisplayName("on the oldest loader ASM, a Java 25 Minecraft gets a message naming ASM 9.5 and what it reads")
    void floorAsmNamesTheGap() throws Exception {
        try (URLClassLoader floor = floorAsmLoader()) {
            Method problem = floor.loadClass(HostAsmSupport.class.getName())
                    .getDeclaredMethod("problem", ClassLoader.class, String.class);
            String message = (String) problem.invoke(null, gameWithMainClass(JAVA_25), "26.1");
            assertEquals("Minecraft 26.1 uses Java 25 class files, and ASM 9.5 from this loader reads Java 21 "
                    + "class files at most.", message);
            assertNull(problem.invoke(null, gameWithMainClass(JAVA_21), "1.21.1"),
                    "ASM 9.5 reads Minecraft 1.21.1's Java 21 classes");
        }
    }

    /** Loads HostAsmSupport against the floor ASM jars the pom copies into target/asm-floor. */
    private static URLClassLoader floorAsmLoader() throws Exception {
        Path classes = Path.of(HostAsmSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<URL> urls = new ArrayList<>();
        try (Stream<Path> jars = Files.list(classes.getParent().resolve("asm-floor"))) {
            for (Path jar : jars.filter(p -> p.getFileName().toString().startsWith("asm-9")).toList()) {
                urls.add(jar.toUri().toURL());
            }
        }
        assertFalse(urls.isEmpty(), "The pom copies the floor ASM jars to target/asm-floor before the tests");
        urls.add(classes.toUri().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    /** A game class loader whose Minecraft main class has the given class-file version. */
    private static ClassLoader gameWithMainClass(int classVersion) {
        byte[] header = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, (byte) classVersion};
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "net/minecraft/server/Main.class".equals(name) ? new ByteArrayInputStream(header) : null;
            }
        };
    }
}
