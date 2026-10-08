/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.21.5 made {@code LeavesBlock} abstract with a {@code (float, Properties)} constructor.
 *
 * <p>Bountiful Fares' fruit leaves extend it and call {@code super(props)}, which died with
 * {@code NoSuchMethodError} during block registration on 26.1.2 and took every later block with it.
 */
class LegacyLeavesBlockBridgeTest {

    private static final String MOD_LEAVES = "test/mod/FruitLeavesBlock";
    private static final String MOD_REGISTRAR = "test/mod/Registrar";
    private static final String PROPS_DESC = "L" + LegacyLeavesBlockBridge.PROPS + ";";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyLeavesBlockBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a leaves subclass calling super(Properties) lands on a constructor that exists")
    void rebasesLegacySubclass() {
        ClassNode out = transform(leavesSubclass(LegacyLeavesBlockBridge.LEGACY_CTOR), MOD_LEAVES);

        assertEquals(LegacyLeavesBlockBridge.INTERNAL, out.superName,
                "the subclass must extend the generated base, because LeavesBlock is abstract and "
                + "has no (Properties) constructor on 1.21.5 and newer");
        MethodInsnNode superCall = calls(out, "<init>").get(0);
        assertEquals(LegacyLeavesBlockBridge.INTERNAL, superCall.owner);
        assertEquals(LegacyLeavesBlockBridge.LEGACY_CTOR, superCall.desc);
    }

    @Test
    @DisplayName("a leaves subclass already on the modern constructor keeps its particle chance")
    void modernSubclassPassesThrough() {
        ClassNode out = transform(leavesSubclass(LegacyLeavesBlockBridge.MODERN_CTOR), MOD_LEAVES);

        MethodInsnNode superCall = calls(out, "<init>").get(0);
        assertEquals(LegacyLeavesBlockBridge.MODERN_CTOR, superCall.desc,
                "a mod built for 1.21.5 or newer chose its own particle chance; keep it");
        ClassNode base = generatedBase();
        assertTrue(base.methods.stream().anyMatch(m -> m.name.equals("<init>")
                        && m.desc.equals(LegacyLeavesBlockBridge.MODERN_CTOR)),
                "the generated base must accept the modern (float, Properties) super call");
    }

    @Test
    @DisplayName("new LeavesBlock(props) becomes a factory call, since LeavesBlock is abstract")
    void directConstructionUsesFactory() {
        ClassNode out = transform(directConstruction(), MOD_REGISTRAR);

        assertTrue(calls(out, "<init>").stream()
                        .noneMatch(c -> c.owner.equals(LegacyLeavesBlockBridge.LEAVES)),
                "instantiating the abstract LeavesBlock fails with InstantiationError");
        MethodInsnNode factory = calls(out, "create").get(0);
        assertEquals(LegacyLeavesBlockBridge.INTERNAL, factory.owner);
        assertEquals(LegacyLeavesBlockBridge.FACTORY_DESC, factory.desc);
    }

    @Test
    @DisplayName("the generated base extends the tinted vanilla leaves")
    void generatedBaseExtendsTintedLeaves() {
        ClassNode base = generatedBase();
        assertEquals(LegacyLeavesBlockBridge.TINTED, base.superName,
                "TintedParticleLeavesBlock implements both abstract methods LeavesBlock gained");
    }

    private ClassNode transform(byte[] input, String name) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(input, name)).accept(cn, 0);
        return cn;
    }

    private ClassNode generatedBase() {
        byte[] bytes = transformer.getSyntheticClasses().get(LegacyLeavesBlockBridge.INTERNAL);
        assertNotNull(bytes, "the base must be registered as a synthetic so it gets embedded");
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static List<MethodInsnNode> calls(ClassNode cn, String name) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals(name)) found.add(call);
            }
        }
        return found;
    }

    /** {@code class FruitLeavesBlock extends LeavesBlock { FruitLeavesBlock(Properties p) { super(...); } }} */
    private static byte[] leavesSubclass(String superDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_LEAVES, null,
                LegacyLeavesBlockBridge.LEAVES, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + PROPS_DESC + ")V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (superDesc.equals(LegacyLeavesBlockBridge.MODERN_CTOR)) mv.visitLdcInsn(0.02f);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyLeavesBlockBridge.LEAVES, "<init>",
                superDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Object make(Properties p) { return new LeavesBlock(p); }} */
    private static byte[] directConstruction() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_REGISTRAR, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "(" + PROPS_DESC + ")Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, LegacyLeavesBlockBridge.LEAVES);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyLeavesBlockBridge.LEAVES, "<init>",
                LegacyLeavesBlockBridge.LEGACY_CTOR, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
