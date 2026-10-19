/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.12 addon and its core mod dropped into {@code mods/} together are translated in one launch.
 * The core has to be translated first so the addon reuses its stand-in classes, and an addon that
 * was translated before its core arrived is translated again once it does.
 */
class InPlaceLibraryLinkingTest {

    private static final String MATERIAL = "com/retromod/generated/fixture/LegacyMaterial";
    private static final String CORE_BLOCK = "core/blocks/BaseMetaBlock";
    private static final String ADDON_BLOCK = "addon/blocks/MetaIO";
    private static final String CTOR = "(Ljava/lang/String;L" + MATERIAL + ";)V";

    @TempDir
    Path tmp;

    @BeforeEach
    void registerStandIn() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ClassWriter material = new ClassWriter(0);
        material.visit(V17, ACC_PUBLIC | ACC_SUPER, MATERIAL, null, "java/lang/Object", null);
        material.visitEnd();
        transformer.registerSyntheticClass(MATERIAL, material.toByteArray());
    }

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the core is translated before an addon listed ahead of it, and the addon links to it")
    void coreFirstThenAddon() throws IOException {
        Path game = game();
        addon(game.resolve("mods/a-rocks.jar"));
        core(game.resolve("mods/z-core.jar"));
        List<String> order = new ArrayList<>();

        int translated = launchFor(discover(game)).translatePending("1.20.1",
                () -> recording(order));

        assertEquals(2, translated);
        assertEquals(List.of("z-core.jar", "a-rocks.jar"), order);
        assertAddonLinksToCore(discover(game));
    }

    @Test
    @DisplayName("an addon translated before its core arrived is translated again with it")
    void addonIsRelinkedWhenTheCoreArrives() throws IOException {
        Path game = game();
        addon(game.resolve("mods/a-rocks.jar"));
        assertEquals(1, launchFor(discover(game)).translatePending("1.20.1", () -> recording(new ArrayList<>())));

        core(game.resolve("mods/z-core.jar"));
        List<String> order = new ArrayList<>();
        int translated = launchFor(discover(game)).translatePending("1.20.1", () -> recording(order));

        assertEquals(2, translated, "the core, then the addon again");
        assertEquals(List.of("z-core.jar", "a-rocks.jar"), order);
        assertAddonLinksToCore(discover(game));
    }

    private static InPlaceLaunch.LinkingTranslator recording(List<String> order) {
        ForgeModTransformer forge = new ForgeModTransformer("1.20.1");
        return (source, out, libraries) -> {
            order.add(source.getFileName().toString());
            return forge.transformMod(source, out, libraries);
        };
    }

    private static void assertAddonLinksToCore(DiscoveryEngine.Outcome outcome) throws IOException {
        Path core = null;
        Path addon = null;
        try (Stream<Path> served = Files.list(outcome.serveFolder())) {
            for (Path jar : served.toList()) {
                if (jar.getFileName().toString().startsWith("z-core")) core = jar;
                if (jar.getFileName().toString().startsWith("a-rocks")) addon = jar;
            }
        }
        assertNotNull(core, "the core's translation is served");
        assertNotNull(addon, "the addon's translation is served");
        String coreCtor = constructorOf(read(core, CORE_BLOCK));
        assertTrue(coreCtor.contains(SyntheticEmbedder.PREFIX), "the core takes its own embedded Material");
        assertEquals(List.of(CORE_BLOCK + ".<init>" + coreCtor), superCalls(read(addon, ADDON_BLOCK)),
                "the addon calls the constructor the core declares");
    }

    private Path game() throws IOException {
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        return game;
    }

    private static DiscoveryEngine.Outcome discover(Path game) throws IOException {
        List<Path> listing;
        try (Stream<Path> files = Files.list(game.resolve("mods"))) {
            listing = files.sorted().toList();
        }
        return DiscoveryEngine.run(new DiscoveryEngine.Request(game, game.resolve("mods"),
                listing, "forge", "1.20.1", "", ForgeModMetadata.FORGE_TOML,
                DiscoveryEngine.ServeStyle.FOLDER, new Properties(), (level, message) -> { }));
    }

    private static InPlaceLaunch launchFor(DiscoveryEngine.Outcome outcome) {
        Properties properties = new Properties();
        properties.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        properties.setProperty(InPlaceStatus.STATUS_PROPERTY, outcome.statusFile().toString());
        return InPlaceLaunch.decide(outcome.statusFile().getParent(), properties);
    }

    private static void core(Path file) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, CORE_BLOCK, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        modJar(file, "core", CORE_BLOCK, cw.toByteArray());
    }

    private static void addon(Path file) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ADDON_BLOCK, null, CORE_BLOCK, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, CORE_BLOCK, "<init>", CTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        modJar(file, "rocks", ADDON_BLOCK, cw.toByteArray());
    }

    private static void modJar(Path file, String modId, String className, byte[] classBytes)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", TestModJars.toml(modId, "1.0.0", "[1.19.2,1.20)", "forge",
                "[40,)", "mandatory=true").getBytes(StandardCharsets.UTF_8));
        entries.put(className + ".class", classBytes);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static ClassNode read(Path jar, String className) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is in " + jar.getFileName());
            ClassNode node = new ClassNode();
            try (var in = file.getInputStream(entry)) {
                new ClassReader(in.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static String constructorOf(ClassNode node) {
        return node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst()
                .orElseThrow().desc;
    }

    private static List<String> superCalls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL
                        && call.owner.equals(node.superName)) {
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }
}
