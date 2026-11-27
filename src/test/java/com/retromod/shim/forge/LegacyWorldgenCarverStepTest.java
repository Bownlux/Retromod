/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.testutil.HostJarLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 1.18.2 biome builder's carver goes into the one carver list that 1.21.2 and newer keep. */
class LegacyWorldgenCarverStepTest {

    private static final String PLAIN = "net/minecraft/world/level/biome/BiomeGenerationSettings$PlainBuilder";
    private static final String BUILDER_CLASS = "net/minecraft/world/level/biome/BiomeGenerationSettings$Builder";
    private static final String BUILDER = "L" + BUILDER_CLASS + ";";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String CARVING_CLASS = "net/minecraft/world/level/levelgen/GenerationStep$Carving";
    private static final String CARVING = "L" + CARVING_CLASS + ";";
    private static final String MOD_CLASS = "biomesoplenty/common/biome/ModBiomes";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final ClassLoader previousOfflineHost = ClassResourceInspector.offlineHost();
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        ClassResourceInspector.setOfflineHost(previousOfflineHost);
        transformer.clearRedirectsForTesting();
    }

    @Test
    void aOneListHostDropsTheStepAndCallsItsOwnAdder() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyWorldgenBridge.register(transformer);

        RetromodTransformer.MethodTarget target = transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(
                BUILDER_CLASS, "addCarver", "(" + CARVING + HOLDER + ")" + BUILDER));
        assertEquals("(" + BUILDER + "Ljava/lang/Object;" + HOLDER + ")" + BUILDER, target.desc(),
                "the helper takes the step as any object, since the class is gone on the host");
        assertEquals(List.of(PLAIN + ".addCarver(" + HOLDER + ")L" + PLAIN + ";"), carverCalls(generated()));
    }

    @Test
    void aOneListHostTakesTheOldStepFromTheStandIn() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyWorldgenBridge.register(transformer);

        ClassNode transformed = transformed(bopShapedCall());
        assertEquals(List.of(LegacyWorldgenBridge.LEGACY_CARVING + ".AIR:L" + LegacyWorldgenBridge.LEGACY_CARVING + ";"),
                fieldReads(transformed), "Carving.AIR is read from the stand-in, since 1.21.2 removed the class");
        assertEquals(List.of(LegacyWorldgenBridge.GENERATED + ".addCarver(" + BUILDER + "Ljava/lang/Object;" + HOLDER + ")"
                + BUILDER), calls(transformed), "the adder still reaches the generated helper once the step is renamed");
        assertNotNull(transformer.getSyntheticClasses().get(LegacyWorldgenBridge.LEGACY_CARVING),
                "the stand-in travels with the mod that reads it");
    }

    @Test
    void aTwoStepHostKeepsTheStep() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        LegacyWorldgenBridge.register(transformer);

        assertEquals(List.of(PLAIN + ".addCarver(" + CARVING + HOLDER + ")L" + PLAIN + ";"), carverCalls(generated()));
        assertFalse(transformer.getClassRedirects().containsKey(CARVING_CLASS),
                "a host that still has the carving step keeps the mod's own step");
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyWorldgenBridge.LEGACY_CARVING));
    }

    @Test
    void realHostsKeepOneCarverList() {
        for (String relative : List.of(
                "net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar",
                "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
                "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar")) {
            Path jar = HostJarLinks.prism(relative);
            Assumptions.assumeTrue(jar != null, relative + " present");
            RetromodVersion.TARGET_MC_VERSION = "1.20.1";
            assertTrue(LegacyWorldgenBridge.hostCarvesInOneStep(HostJarLinks.read(List.of(jar), PLAIN)),
                    relative + " is read as a one-list host whatever the target says");
        }
    }

    @Test
    void aBiomesOPlentyCarverCallLinksOn1_21_11() throws Exception {
        assertBopCallLinks("net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar", "1.21.11");
    }

    @Test
    void aBiomesOPlentyCarverCallLinksOn26_3() throws Exception {
        assertBopCallLinks("net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
                "26.3");
    }

    // The bridge reads the real host jar, and every game member the mod and the helper then name exists in it.
    private void assertBopCallLinks(String relative, String version) throws Exception {
        Path jar = HostJarLinks.prism(relative);
        Assumptions.assumeTrue(jar != null, relative + " present");
        RetromodVersion.TARGET_MC_VERSION = version;
        try (URLClassLoader host = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            ClassResourceInspector.setOfflineHost(host);
            transformer.clearRedirectsForTesting();
            LegacyWorldgenBridge.register(transformer);

            byte[] transformed = transformer.transformClass(bopShapedCall(), MOD_CLASS);
            assertFalse(new String(transformed, StandardCharsets.ISO_8859_1).contains(CARVING_CLASS),
                    relative + ": the mod no longer names the removed carving step");
            assertTrue(HostJarLinks.unresolved(transformed, List.of(jar), "net/minecraft/").isEmpty(), relative);
            assertTrue(HostJarLinks.unresolvedIn(generated(), "addCarver", List.of(jar), "net/minecraft/").isEmpty(),
                    relative + " has the one-list adder the helper calls");
            ClassNode standIn = node(transformer.getSyntheticClasses().get(LegacyWorldgenBridge.LEGACY_CARVING));
            for (String read : fieldReads(node(transformed))) {
                String name = read.substring(read.indexOf('.') + 1, read.indexOf(':'));
                assertTrue(standIn.fields.stream().anyMatch(field -> field.name.equals(name)), read + " is in the stand-in");
            }
        }
    }

    // BiomesOPlenty 1.18.2 and 1.19.2 pass GenerationStep.Carving.AIR to every addCarver call.
    private static byte[] bopShapedCall() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(" + BUILDER + HOLDER + ")V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, CARVING_CLASS, "AIR", CARVING);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER_CLASS, "addCarver", "(" + CARVING + HOLDER + ")" + BUILDER, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transformed(byte[] original) {
        return node(transformer.transformClass(original, MOD_CLASS));
    }

    private byte[] generated() {
        return transformer.getSyntheticClasses().get(LegacyWorldgenBridge.GENERATED);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode run(ClassNode mod) {
        return mod.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
    }

    private static List<String> fieldReads(ClassNode mod) {
        List<String> reads = new ArrayList<>();
        run(mod).instructions.forEach(insn -> {
            if (insn instanceof FieldInsnNode field) reads.add(field.owner + "." + field.name + ":" + field.desc);
        });
        return reads;
    }

    private static List<String> calls(ClassNode mod) {
        List<String> calls = new ArrayList<>();
        run(mod).instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        });
        return calls;
    }

    private static List<String> carverCalls(byte[] generated) {
        ClassNode node = node(generated);
        List<String> calls = new ArrayList<>();
        node.methods.stream().filter(method -> method.name.equals("addCarver")).forEach(method ->
                method.instructions.forEach(insn -> {
                    if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
                }));
        return calls;
    }
}
