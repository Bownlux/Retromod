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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.21.2 removed {@code Properties.dropsLike(Block)} and dropped the first argument of
 * {@code BlockState.getOffset}. Bountiful Fares calls both while registering blocks, and each
 * {@code NoSuchMethodError} stopped its block registration on 26.1.2.
 */
class LegacyBlockMethodBridgeTest {

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBlockMethodBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("dropsLike becomes a call to the embedded helper with the receiver first")
    void dropsLikeIsRedirected() {
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(callsDropsLike(), "test/mod/Blocks"))
                .accept(out, 0);

        MethodInsnNode call = null;
        for (MethodNode m : out.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c && c.name.equals("dropsLike")) call = c;
            }
        }
        assertNotNull(call, "the call must survive as a dropsLike call");
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyBlockMethodBridge.INTERNAL, call.owner,
                "Properties has no dropsLike on 1.21.2 and newer");
        assertEquals(LegacyBlockMethodBridge.HELPER_DESC, call.desc);
    }

    @Test
    @DisplayName("the helper copies the source block's loot table through overrideLootTable")
    void helperUsesOverrideLootTable() {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyBlockMethodBridge.generate()).accept(helper, 0);
        MethodNode dropsLike = helper.methods.stream()
                .filter(m -> m.name.equals("dropsLike")).findFirst().orElseThrow();
        boolean overrides = false;
        for (AbstractInsnNode insn : dropsLike.instructions) {
            if (insn instanceof MethodInsnNode c && c.name.equals("overrideLootTable")) {
                overrides = true;
            }
        }
        assertTrue(overrides, "overrideLootTable is the 1.21.2 replacement for dropsLike");
    }

    @Test
    @DisplayName("getOffset(BlockGetter, BlockPos) reaches the one-argument host method")
    void getOffsetDropsTheLeadingGetter() {
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(callsGetOffset(), "test/mod/HangingFruit"))
                .accept(out, 0);
        MethodInsnNode call = null;
        for (MethodNode m : out.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c && c.name.equals("getOffset")) call = c;
            }
        }
        assertNotNull(call);
        assertEquals(LegacyBlockMethodBridge.INTERNAL, call.owner,
                "the dropped argument is the first one, so only a helper can remove it");
        assertEquals(LegacyBlockMethodBridge.OFFSET_HELPER_DESC, call.desc);
    }

    @Test
    @DisplayName("getLightBlock(BlockGetter, BlockPos) reaches the accessor the host declares")
    void lightAccessorFollowsTheHost() {
        assertEquals("getLightBlock", lightCallAfterTransformOn("1.21.4").name,
                "1.21.2 to 1.21.11 kept the name getLightBlock and only dropped the arguments");
        MethodInsnNode onModernHost = lightCallAfterTransformOn("26.1.2");
        assertEquals("getLightDampening", onModernHost.name, "26.1 renamed it getLightDampening");
        assertEquals("()I", onModernHost.desc);
    }

    private MethodInsnNode lightCallAfterTransformOn(String host) {
        transformer.clearRedirectsForTesting();
        LegacyBlockMethodBridge.register(transformer, host);
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(callsGetLightBlock(), "test/mod/GlowBlock"))
                .accept(out, 0);
        for (MethodNode m : out.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c
                        && c.owner.equals(LegacyBlockMethodBridge.STATE)) {
                    return c;
                }
            }
        }
        return fail("the light call on BlockState did not survive the transform");
    }

    private static byte[] callsGetLightBlock() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/GlowBlock", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "light",
                "(L" + LegacyBlockMethodBridge.STATE + ";L" + LegacyBlockMethodBridge.GETTER + ";L"
                        + LegacyBlockMethodBridge.POS + ";)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyBlockMethodBridge.STATE, "getLightBlock",
                "(L" + LegacyBlockMethodBridge.GETTER + ";L" + LegacyBlockMethodBridge.POS + ";)I",
                false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] callsGetOffset() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/HangingFruit", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "offset",
                "(L" + LegacyBlockMethodBridge.STATE + ";L" + LegacyBlockMethodBridge.GETTER + ";L"
                        + LegacyBlockMethodBridge.POS + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyBlockMethodBridge.STATE, "getOffset",
                LegacyBlockMethodBridge.OLD_OFFSET_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] callsDropsLike() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Blocks", null, "java/lang/Object",
                null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "props",
                "(L" + LegacyBlockMethodBridge.PROPS + ";L" + LegacyBlockMethodBridge.BLOCK
                        + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyBlockMethodBridge.PROPS, "dropsLike",
                LegacyBlockMethodBridge.DROPS_LIKE_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
