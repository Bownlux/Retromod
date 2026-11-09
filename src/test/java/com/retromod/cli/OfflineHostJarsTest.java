/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.neoforge.NeoForgeMovedClasses;
import com.retromod.loaderapi.LoaderApiFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The CLI reads NeoForge's own jars beside a patched game jar, so bridges that check the loader see it offline. */
class OfflineHostJarsTest {

    private static final String VERSION = "26.3.0.43-beta";

    @TempDir
    Path libraries;

    @AfterEach
    void reset() {
        ClassResourceInspector.setOfflineHost(null);
    }

    @Test
    @DisplayName("A patched client finds its NeoForge universal jar and the newest loader of the 26.3 line")
    void findsTheLoaderJarsBesideThePatchedClient() throws Exception {
        Path client = jar("minecraft-client-patched/" + VERSION + "/minecraft-client-patched-" + VERSION + ".jar");
        Path universal = jar("neoforge/" + VERSION + "/neoforge-" + VERSION + "-universal.jar");
        jar("fancymodloader/loader/11.0.13/loader-11.0.13.jar");
        jar("fancymodloader/loader/12.0.0/loader-12.0.0.jar");
        Path loader = jar("fancymodloader/loader/12.0.8/loader-12.0.8.jar");

        assertEquals(List.of(universal, loader), OfflineHostJars.nextTo(client));
    }

    @Test
    @DisplayName("A server install names its loader in its launch arguments, and that one wins")
    void launchArgumentsNameTheLoader() throws Exception {
        Path server = jar("minecraft-server-patched/" + VERSION + "/minecraft-server-patched-" + VERSION + ".jar");
        Path universal = jar("neoforge/" + VERSION + "/neoforge-" + VERSION + "-universal.jar");
        Path named = jar("fancymodloader/loader/12.0.0/loader-12.0.0.jar");
        jar("fancymodloader/loader/12.0.8/loader-12.0.8.jar");
        Files.writeString(libraries.resolve("net/neoforged/neoforge/" + VERSION + "/unix_args.txt"),
                "-p libraries/net/neoforged/fancymodloader/loader/12.0.0/loader-12.0.0.jar\n");

        assertEquals(List.of(universal, named), OfflineHostJars.nextTo(server));
    }

    @Test
    @DisplayName("A vanilla game jar finds nothing")
    void vanillaJarFindsNothing() throws Exception {
        Path vanilla = jar("../com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
        assertEquals(List.of(), OfflineHostJars.nextTo(vanilla));
    }

    @Test
    @DisplayName("A class only the universal jar beside --mc-jar holds is read as the host's")
    void inspectorReadsTheJarsBesideTheGameJar() throws Exception {
        String name = "net/neoforged/neoforge/common/world/BiomeModifier";
        Path client = jar("minecraft-client-patched/" + VERSION + "/minecraft-client-patched-" + VERSION + ".jar");
        Path universal = libraries.resolve("net/neoforged/neoforge/" + VERSION + "/neoforge-" + VERSION + "-universal.jar");
        Files.createDirectories(universal.getParent());
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null,
                "java/lang/Object", null);
        cw.visitEnd();
        Files.write(universal, LoaderApiFixtures.jar(Map.of(name + ".class", cw.toByteArray())));

        assertNull(ClassResourceInspector.read(name), "no game runs here");
        OfflineHostJars.useNextToGameJar(new String[]{"transform", "mod.jar", "--mc-jar", client.toString()});
        assertNotNull(ClassResourceInspector.read(name));
    }

    @Test
    @DisplayName("The patched game jar is read too, so NeoForge's TriState still moves to Minecraft's offline")
    void theGameJarIsReadWithTheLoaderJars() throws Exception {
        String triState = "net/minecraft/util/TriState";
        String neoForge = "net/neoforged/neoforge/common/NeoForge";
        Path client = libraries.resolve("net/neoforged/minecraft-client-patched/" + VERSION
                + "/minecraft-client-patched-" + VERSION + ".jar");
        Path universal = libraries.resolve("net/neoforged/neoforge/" + VERSION + "/neoforge-" + VERSION + "-universal.jar");
        Files.createDirectories(client.getParent());
        Files.createDirectories(universal.getParent());
        Files.write(client, LoaderApiFixtures.jar(Map.of(triState + ".class", emptyClass(triState))));
        Files.write(universal, LoaderApiFixtures.jar(Map.of(neoForge + ".class", emptyClass(neoForge))));
        jar("fancymodloader/loader/12.0.8/loader-12.0.8.jar");

        OfflineHostJars.useNextToGameJar(new String[]{"batch", "in", "--mc-jar", client.toString()});
        assertNotNull(ClassResourceInspector.read(triState), "the game's own classes are the host's too");

        // A 1.21.1 mod's NeoForge TriState return type becomes Minecraft's, as in the game.
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            NeoForgeMovedClasses.register(transformer);
            ClassNode node = new ClassNode();
            new ClassReader(transformer.transformClass(triStateUser(), "mod/PickUp")).accept(node, 0);
            assertEquals("()L" + triState + ";", node.methods.get(0).desc);
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A method declared as returning NeoForge 1.21.1's TriState, as PickUpNotifier's canPickup is. */
    private static byte[] triStateUser() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/PickUp", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "canPickup",
                "()Lnet/neoforged/neoforge/common/util/TriState;", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An empty jar at a path under {@code libraries/net/neoforged}. */
    private Path jar(String relative) throws Exception {
        Path jar = libraries.resolve("net/neoforged").resolve(relative).normalize();
        Files.createDirectories(jar.getParent());
        Files.write(jar, LoaderApiFixtures.jar(Map.of("placeholder.txt", new byte[]{1})));
        return jar;
    }
}
