/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.retromod.shim.common.LegacyChunkGeneratorAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

/** A mod's chunk generator written for 1.20.1 or 1.21 gets the steps 1.21 to 26.2 and 26.3 hosts call. */
class LegacyChunkGeneratorAdapterTest {

    // The test jar has no classes of its own, so each generator's parent is the host class it names.
    private static final Function<String, byte[]> NO_JAR_CLASSES = name -> null;

    @Test
    @DisplayName("on 26.3 a 1.20.1 generator gets a terrain step running its noise, surface and nothing it cannot call")
    void terrainStepFrom1201() throws Exception {
        ClassNode node = adapt(generator(NOISE_1201, true, false), HostShape.TERRAIN_STEP);
        List<String> calls = calls(method(node, "buildTerrain", TERRAIN));
        assertTrue(calls.contains("mod/VoidGenerator.fillFromNoise" + NOISE_1201), calls.toString());
        assertTrue(calls.contains("mod/VoidGenerator.buildSurface" + SURFACE), calls.toString());
        assertNotNull(method(node, "addDebugScreenInfo", DEBUG), "26.3's debug lines take a sampler context too");
        verify(node);
    }

    @Test
    @DisplayName("on 26.3 a 1.21 generator's carvers run after its surface")
    void terrainStepFrom121() throws Exception {
        ClassNode node = adapt(generator(NOISE, true, true), HostShape.TERRAIN_STEP);
        List<String> calls = calls(method(node, "buildTerrain", TERRAIN));
        assertTrue(calls.contains("mod/VoidGenerator.fillFromNoise" + NOISE), calls.toString());
        assertTrue(calls.contains("mod/VoidGenerator.applyCarvers" + CARVERS), calls.toString());
        verify(node);
    }

    @Test
    @DisplayName("on 1.21 to 26.2 a 1.20.1 generator gets the four-argument noise step")
    void noiseStepFrom1201() throws Exception {
        ClassNode node = adapt(generator(NOISE_1201, false, false), HostShape.SEPARATE_STEPS);
        assertTrue(calls(method(node, "fillFromNoise", NOISE)).contains("mod/VoidGenerator.fillFromNoise" + NOISE_1201));
        assertNull(method(node, "buildTerrain", TERRAIN), "a host before 26.3 has no terrain step");
        verify(node);
    }

    @Test
    @DisplayName("a generator built on a vanilla generator, and a mixin, are left alone")
    void vanillaBasedAndMixinGeneratorsAreLeftAlone() {
        byte[] noiseBased = generator("net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator", false, NOISE, true, true);
        assertSame(noiseBased, LegacyChunkGeneratorAdapter.apply(noiseBased, NO_JAR_CLASSES, HostShape.TERRAIN_STEP),
                "the noise generator already has the host's terrain step");
        byte[] mixin = generator(GENERATOR, true, NOISE, true, true);
        assertSame(mixin, LegacyChunkGeneratorAdapter.apply(mixin, NO_JAR_CLASSES, HostShape.TERRAIN_STEP));
    }

    @Test
    @DisplayName("a generator whose parent is the mod's own class on the abstract base is adapted")
    void jarParentOnTheAbstractBaseIsAdapted() {
        byte[] parent = generator(GENERATOR, false, NOISE, false, false);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/SkyGenerator", null, "mod/VoidGenerator", null);
        empty(cw, "fillFromNoise", NOISE);
        cw.visitEnd();
        byte[] child = cw.toByteArray();
        assertNotSame(child, LegacyChunkGeneratorAdapter.apply(child,
                name -> name.equals("mod/VoidGenerator") ? parent : null, HostShape.TERRAIN_STEP));
    }

    /** A generator declaring the noise step with {@code noiseDesc}, and optionally its surface and carvers. */
    private static byte[] generator(String noiseDesc, boolean surface, boolean carvers) {
        return generator(GENERATOR, false, noiseDesc, surface, carvers);
    }

    private static byte[] generator(String parent, boolean mixin, String noiseDesc, boolean surface, boolean carvers) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/VoidGenerator", null, parent, null);
        if (mixin) cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        empty(cw, "fillFromNoise", noiseDesc);
        if (surface) empty(cw, "buildSurface", SURFACE);
        if (carvers) empty(cw, "applyCarvers", CARVERS);
        empty(cw, "addDebugScreenInfo", OLD_DEBUG);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void empty(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        if (desc.endsWith(")V")) {
            mv.visitInsn(Opcodes.RETURN);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassNode adapt(byte[] bytes, HostShape shape) {
        byte[] out = LegacyChunkGeneratorAdapter.apply(bytes, NO_JAR_CLASSES, shape);
        assertNotSame(bytes, out, "the generator should have been given the host's step");
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        method.instructions.forEach(insn -> { if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc); });
        return calls;
    }

    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) new Analyzer<>(new BasicInterpreter()).analyze(node.name, method);
    }
}
