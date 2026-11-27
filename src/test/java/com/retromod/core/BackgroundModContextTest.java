/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The background pass reads its own mod's version, never the mod the foreground translation runs beside it. */
class BackgroundModContextTest {

    // A 1.14 MCP-era name that 1.12.2 used for another class, so only mods from 1.14 on take the move.
    private static final String OLD = "net/minecraft/entity/OldThing";
    private static final String NEW = "net/minecraft/world/entity/NewThing";
    private static final String HOLDER = "oldmod/content/Holder";

    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @TempDir
    Path directory;

    @BeforeEach
    void freshTransformer() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        transformer = constructor.newInstance();
        transformer.registerClassRedirect(OLD, NEW);
        transformer.limitClassRedirectToModsFrom(OLD, "1.14");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("A thread's own mod wins over the shared one, and the shared one comes back when its scope closes")
    void threadContextWinsOverSharedMod() throws Exception {
        // The foreground translates a 1.12.2 mod, which keeps the old name.
        transformer.setModSourceVersion("1.12.2");
        assertEquals("L" + OLD + ";", translatedFieldType());

        String background = CompletableFuture.supplyAsync(() -> {
            try (var scope = transformer.pushThreadModContext(RetromodTransformer.ModContext.of("1.16.5", null))) {
                return translatedFieldType();
            }
        }).get();
        assertEquals("L" + NEW + ";", background, "the 1.16.5 mod on the background thread takes the move");

        try (var scope = transformer.pushThreadModContext(RetromodTransformer.ModContext.of("1.16.5", null))) {
            assertEquals("L" + NEW + ";", translatedFieldType());
        }
        assertEquals("L" + OLD + ";", translatedFieldType(),
                "closing the scope gives the thread the shared mod again");
        transformer.setModSourceVersion(null);
    }

    @Test
    @DisplayName("The background pass and a launch-time class load translate a 1.16.5 mod as 1.16.5 while a 1.12.2 mod runs")
    void backgroundPassReadsItsOwnMod() throws Exception {
        Path mods = Files.createDirectories(directory.resolve("mods"));
        writeMod(mods.resolve("oldmod-1.16.5.jar"));
        transformer.setModSourceVersion("1.12.2");

        HybridTransformationEngine engine = new HybridTransformationEngine(transformer);
        try {
            engine.initialize(mods, "26.3");
            long deadline = System.currentTimeMillis() + 30_000;
            while (!engine.isAotComplete() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue(engine.isAotComplete(), "the background pass finishes");

            assertEquals("L" + NEW + ";", fieldType(engine.transform(HOLDER, holder(), null)),
                    "the cached class was prepared as the 1.16.5 mod it belongs to");
            // Other bytes under a name of the same package miss the cache and go through the launch-time path.
            assertEquals("L" + NEW + ";", fieldType(engine.transform("oldmod/content/Other", holder("oldmod/content/Other"), null)));
        } finally {
            engine.shutdown();
            transformer.setModSourceVersion(null);
        }
    }

    @Test
    @DisplayName("A launch-time class load does not yield to a library that the foreground translation's jar ships")
    void launchTimeLoadReadsNoForegroundJar() throws Exception {
        String libraryOld = "software/bernie/geckolib/core/Thing";
        String libraryNew = "com/geckolib/core/Thing";
        String other = "othermod/client/Renderer";
        transformer.registerClassRedirect(libraryOld, libraryNew);
        // The foreground translates a jar that ships the old library, so its own classes keep the old name.
        transformer.setJarClassBytesProvider(name -> name.equals(libraryOld) ? holder(libraryOld) : null);
        HybridTransformationEngine engine = new HybridTransformationEngine(transformer);
        try {
            assertEquals("L" + libraryOld + ";", fieldType(transformer.transformClass(holder(other, libraryOld), other)));
            engine.initialize(Files.createDirectories(directory.resolve("mods")), "26.3");

            assertEquals("L" + libraryNew + ";", fieldType(engine.transform(other, holder(other, libraryOld), null)),
                    "another mod's class loaded at launch takes the move");
        } finally {
            engine.shutdown();
            transformer.clearJarClassBytesProvider();
        }
    }

    private static void writeMod(Path jar) throws Exception {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[36,)\"\n[[mods]]\nmodId=\"oldmod\"\n"
                + "[[dependencies.oldmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.16.5,1.17)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(HOLDER + ".class"));
            out.write(holder());
            out.closeEntry();
        }
    }

    private static byte[] holder() {
        return holder(HOLDER);
    }

    private static byte[] holder(String name) {
        return holder(name, OLD);
    }

    private static byte[] holder(String name, String fieldType) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "thing", "L" + fieldType + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private String translatedFieldType() {
        byte[] translated = transformer.transformClass(holder(), HOLDER);
        return fieldType(translated != null ? translated : holder());
    }

    private static String fieldType(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.fields.get(0).desc;
    }
}
