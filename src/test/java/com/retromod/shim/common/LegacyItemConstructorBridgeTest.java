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
import org.objectweb.asm.util.CheckClassAdapter;


import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.21.1 mod registers its sign, wall-mounted, potion and boat items in one method, so the first
 * reshaped constructor stopped everything after it from registering on 26.1.
 */
class LegacyItemConstructorBridgeTest {

    private static final String MOD_CLASS = "test/mod/ModItems";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old sign item constructor becomes the bridge factory on 26.1")
    void signItemGoesThroughTheFactory() {
        LegacyItemConstructorBridge.register(transformer, "26.1.2");

        MethodInsnNode call = constructionCall(transformedMaker(signMaker()));
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyItemConstructorBridge.INTERNAL, call.owner,
                "the sign item must be built by the bridge's factory");
        assertEquals("signItem", call.name);
    }

    @Test
    @DisplayName("an older host keeps the constructor it still has")
    void olderHostIsUntouched() {
        LegacyItemConstructorBridge.register(transformer, "1.21.4");

        MethodInsnNode call = constructionCall(transformedMaker(signMaker()));
        assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode());
        assertEquals(LegacyItemConstructorBridge.SIGN_ITEM, call.owner);
        assertTrue(transformer.getSyntheticClasses().isEmpty());
    }

    @Test
    @DisplayName("the generated factories and boat stand-in are valid classes")
    void generatedClassesVerify() {
        assertVerifies(LegacyItemConstructorBridge.generate());
        assertVerifies(LegacyItemConstructorBridge.generateBoatType());
    }

    /** Structural checks only: Minecraft is not on the test classpath for a data-flow check. */
    private static void assertVerifies(byte[] bytes) {
        assertDoesNotThrow(() -> new ClassReader(bytes).accept(new CheckClassAdapter(null, false), 0),
                "the generated class must be well formed");
    }

    private MethodNode transformedMaker(byte[] bytes) {
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD_CLASS)).accept(out, 0);
        return out.methods.stream().filter(m -> m.name.equals("make")).findFirst().orElseThrow();
    }

    private static MethodInsnNode constructionCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        fail("the method must still build the item");
        return null;
    }

    /** {@code static Object make(Properties p, Block a, Block b) { return new SignItem(p, a, b); }} */
    private static byte[] signMaker() {
        String desc = LegacyItemConstructorBridge.OLD_SIGN_DESC;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                desc.replace(")V", ")Ljava/lang/Object;"), null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, LegacyItemConstructorBridge.SIGN_ITEM);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyItemConstructorBridge.SIGN_ITEM, "<init>",
                desc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
