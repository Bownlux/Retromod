/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The 26.2 worldgen-type bridge intercepts static {@code Registry.register} calls. The bridge is
 * an INTERFACE, so every rewritten call site must carry the InterfaceMethodref flag - including
 * calls from mods compiled against MC &lt;= 1.19.2, where {@code Registry} was still a class and
 * the original flag is {@code false} (the review's empirically-reproduced regression: a plain
 * Methodref against the interface dies {@code IncompatibleClassChangeError} at first execution).
 */
class WorldgenTypeBridgeTest {

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String DESC =
            "(L" + REGISTRY + ";Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)Ljava/lang/Object;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static byte[] fixture(boolean itf) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "com/example/Registers", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "reg",
                "(L" + REGISTRY + ";Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, REGISTRY, "register", DESC, itf);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode findBridgeCall(byte[] out) {
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        for (AbstractInsnNode in : cn.methods.stream().filter(m -> m.name.equals("reg"))
                .findFirst().orElseThrow().instructions.toArray()) {
            if (in instanceof MethodInsnNode mi
                    && mi.owner.equals(WorldgenTypeBridgeSynthetic.INTERNAL)) {
                return mi;
            }
        }
        return null;
    }

    @Test
    @DisplayName("the bridge is generated as an interface and registered for embedding")
    void bridgeIsInterface() {
        ClassNode cn = new ClassNode();
        new ClassReader(WorldgenTypeBridgeSynthetic.generate()).accept(cn, 0);
        assertTrue((cn.access & ACC_INTERFACE) != 0,
                "bridge must be an interface: modern call sites carry the InterfaceMethodref flag");
        assertTrue(cn.methods.stream().anyMatch(m -> m.name.equals("convert")));
    }

    @Test
    @DisplayName("modern (itf=true) call sites redirect with the flag preserved")
    void modernCallKeepsInterfaceFlag() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        WorldgenTypeBridgeSynthetic.register(t);
        MethodInsnNode mi = findBridgeCall(t.transformClass(fixture(true), "com/example/Registers.class"));
        assertNotNull(mi, "call must be redirected to the bridge");
        assertTrue(mi.itf, "InterfaceMethodref flag must be set");
    }

    @Test
    @DisplayName("pre-1.19.3 (itf=false) call sites get the flag FORCED for the interface target")
    void legacyCallGetsInterfaceFlagForced() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        WorldgenTypeBridgeSynthetic.register(t);
        MethodInsnNode mi = findBridgeCall(t.transformClass(fixture(false), "com/example/Registers.class"));
        assertNotNull(mi, "call must be redirected to the bridge");
        assertTrue(mi.itf, "a plain Methodref against the interface bridge dies "
                + "IncompatibleClassChangeError at first registration (pre-1.19.3 mods)");
    }

    @Test
    @DisplayName("a 1.20.1 record codec registered as a chunk generator or biome source is converted on both layers")
    void chunkGeneratorAndBiomeSourceAreConverted() {
        for (boolean layer262 : new boolean[]{false, true}) {
            ClassNode cn = new ClassNode();
            new ClassReader(WorldgenTypeBridgeSynthetic.generate(layer262)).accept(cn, 0);
            java.util.Set<String> registries = new java.util.HashSet<>();
            cn.methods.stream().filter(m -> m.name.equals("convert")).findFirst().orElseThrow().instructions.forEach(in -> {
                if (in instanceof org.objectweb.asm.tree.FieldInsnNode field) registries.add(field.name);
            });
            assertTrue(registries.contains("CHUNK_GENERATOR") && registries.contains("BIOME_SOURCE"),
                    "both registries hold map codecs since 1.20.5: " + registries);
        }
    }
}
