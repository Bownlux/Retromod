/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A batched Forge addon that passes its own Tier to two tool libraries links to both on 26.2. */
class CliBatchStandInAdapterTest {

    private static final String TIER = "net/minecraft/world/item/Tier";
    private static final String SPEED = "(L" + TIER + ";)F";
    private static final String TIER_STAND_IN = "com/retromod/generated/LegacyEquipmentTier";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "--aot={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("each tool library keeps its own Tier, and the addon converts its Tier where it calls the other")
    void addonPassesItsTierToTwoLibraries(boolean aot, @TempDir Path dir) throws Exception {
        Path in = Files.createDirectories(dir.resolve("in"));
        library(in.resolve("aaa-tools.jar"), "aaatools");
        library(in.resolve("zzz-tools.jar"), "zzztools");
        addon(in.resolve("addon.jar"));
        McReflect.setForceForge(true);
        ModVersionDetector.setDefaultPreferredLoader("forge");

        Path out = dir.resolve("out");
        if (aot) {
            CliHarness.batch(in, out, "26.2", "--aot");
        } else {
            CliHarness.batch(in, out, "26.2");
        }

        String suffix = aot ? "-aot.jar" : ".jar";
        String first = speedParameter(out.resolve("aaa-tools" + suffix), "aaatools/Api");
        String second = speedParameter(out.resolve("zzz-tools" + suffix), "zzztools/Api");
        assertTrue(first.startsWith(SyntheticEmbedder.PREFIX) && first.endsWith(TIER_STAND_IN), first);
        assertNotEquals(first, second, "each library keeps its own Tier copy");
        Path addon = out.resolve("addon" + suffix);
        assertEquals(List.of("aaatools/Api.speed(L" + first + ";)F", "zzztools/Api.speed(L" + second + ";)F"),
                apiCalls(addon), "the addon calls each library with the Tier that library declares");
        assertTrue(adaptsBefore(addon, "zzztools/Api", second), "the addon converts its Tier before the second call");
        String links = linksOf(addon);
        assertTrue(links.contains("crosses " + second), "the addon lists the copy it converts to: " + links);
    }

    /** Whether the call into {@code owner} is fed by an adapter returning that library's copy. */
    private static boolean adaptsBefore(Path jar, String owner, String copy) throws IOException {
        for (MethodNode method : read(jar, "addon/Uses").methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                        && call.getPrevious() instanceof MethodInsnNode adapt && adapt.name.equals("adapt")
                        && adapt.owner.contains("/retromod/adapt/") && adapt.desc.endsWith("L" + copy + ";")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String linksOf(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry("META-INF/retromod-synthetic-links.txt");
            assertNotNull(entry, "the addon has a link file");
            return new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** {@code public class Api { public static float speed(Tier tier) { return tier.getSpeed(); } }} */
    private static void library(Path file, String pkg) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, pkg + "/Api", null, "java/lang/Object", null);
        MethodVisitor speed = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "speed", SPEED, null, null);
        speed.visitCode();
        speed.visitVarInsn(ALOAD, 0);
        // Tier.getSpeed() by its Forge 1.20.1 SRG id.
        speed.visitMethodInsn(INVOKEINTERFACE, TIER, "m_6624_", "()F", true);
        speed.visitInsn(FRETURN);
        speed.visitMaxs(0, 0);
        speed.visitEnd();
        cw.visitEnd();
        modJar(file, pkg, Map.of(pkg + "/Api", cw.toByteArray()));
    }

    /** An addon tier, as the test mod's LibraryTier, passed to both libraries. */
    private static void addon(Path file) throws IOException {
        ClassWriter tier = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        tier.visit(V17, ACC_PUBLIC | ACC_SUPER, "addon/OwnTier", null, "java/lang/Object", new String[]{TIER});
        MethodVisitor init = tier.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        tier.visitEnd();

        ClassWriter uses = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        uses.visit(V17, ACC_PUBLIC | ACC_SUPER, "addon/Uses", null, "java/lang/Object", null);
        MethodVisitor run = uses.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()F", null, null);
        run.visitCode();
        run.visitTypeInsn(NEW, "addon/OwnTier");
        run.visitInsn(DUP);
        run.visitMethodInsn(INVOKESPECIAL, "addon/OwnTier", "<init>", "()V", false);
        run.visitVarInsn(ASTORE, 0);
        run.visitVarInsn(ALOAD, 0);
        run.visitMethodInsn(INVOKESTATIC, "aaatools/Api", "speed", SPEED, false);
        run.visitVarInsn(ALOAD, 0);
        run.visitMethodInsn(INVOKESTATIC, "zzztools/Api", "speed", SPEED, false);
        run.visitInsn(FADD);
        run.visitInsn(FRETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        uses.visitEnd();

        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("addon/OwnTier", tier.toByteArray());
        classes.put("addon/Uses", uses.toByteArray());
        modJar(file, "addon", classes);
    }

    private static void modJar(Path file, String modId, Map<String, byte[]> classes) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                    + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static String speedParameter(Path jar, String owner) throws IOException {
        ClassNode node = read(jar, owner);
        MethodNode speed = node.methods.stream().filter(m -> m.name.equals("speed")).findFirst().orElseThrow();
        return Type.getArgumentTypes(speed.desc)[0].getInternalName();
    }

    private static List<String> apiCalls(Path jar) throws IOException {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : read(jar, "addon/Uses").methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("speed")) {
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }

    private static ClassNode read(Path jar, String className) throws IOException {
        assertTrue(Files.isRegularFile(jar), "the batch wrote " + jar.getFileName());
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is in " + jar.getFileName());
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
            return node;
        }
    }
}
