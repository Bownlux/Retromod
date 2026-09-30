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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Every path a pre-26.3 key code takes into Minecraft is routed through the translating polyfill,
 * and the two 26.3 input changes that crash outright are bridged.
 */
class SdlInputBridge263Test {

    private static final String POLY = "com/retromod/polyfill/minecraft/RetroKeyMapping";
    private static final String CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
    private static final String TYPE = CONSTANTS + "$Type";
    private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
    private static final String CATEGORY = "Lnet/minecraft/client/KeyMapping$Category;";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.3";
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("literal key codes reach the translating bridge")
    void inputPathsAreBridged() {
        Mc26_2To26_3CoreMoves.register(transformer);
        List<String> out = trace(transformer.transformClass(clientSetup(), "test/mod/Keys"));

        assertTrue(out.contains("field " + TYPE + ".KEYBOARD"),
                "26.3 removed Type.KEYSYM, which crashes the typed constructor: " + out);
        assertTrue(out.contains("call " + POLY + ".isKeyDown"),
                "a literal GLFW code must be translated: " + out);
        assertTrue(out.contains("call " + POLY + ".getOrCreate"), out.toString());
        assertTrue(out.contains("call " + POLY + ".createTypedWithCategory"),
                "the default key code must be translated: " + out);
        assertFalse(out.contains("call " + KEY_MAPPING + ".<init>"),
                "no constructor call may bypass the translation: " + out);
    }

    @Test
    @DisplayName("a code read at runtime is already the host's and is not translated again")
    void runtimeCodesPassThrough() {
        Mc26_2To26_3CoreMoves.register(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "held",
                "(Lcom/mojang/blaze3d/platform/Window;L" + TYPE + ";I)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, TYPE, "getOrCreate", "(I)L" + CONSTANTS + "$Key;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, CONSTANTS, "isKeyDown",
                "(Lcom/mojang/blaze3d/platform/Window;I)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> out = trace(transformer.transformClass(cw.toByteArray(), "test/mod/Keys"));
        assertTrue(out.contains("call " + TYPE + ".getOrCreate"),
                "a mouse event's button 1 is the left button; translating it would pick the right one: "
                + out);
        assertTrue(out.contains("call " + POLY + ".isKeyDownUntranslated"),
                "the window is still dropped, but the code passes through: " + out);
    }

    @Test
    @DisplayName("the Fabric keybind factory translates its code too")
    void fabricKeybindFactoryTranslates() throws Exception {
        // On 26.x Fabric the 4-argument constructor goes through KeyBindingShim, not RetroKeyMapping.
        byte[] shim;
        try (var in = getClass().getClassLoader().getResourceAsStream(
                "com/retromod/shim/fabric/embedded/KeyBindingShim.class")) {
            shim = in.readAllBytes();
        }
        ClassNode cn = new ClassNode();
        new ClassReader(shim).accept(cn, 0);
        MethodNode create = cn.methods.stream().filter(m -> m.name.equals("create"))
                .findFirst().orElseThrow();
        boolean translates = false;
        for (AbstractInsnNode insn : create.instructions) {
            if (insn instanceof MethodInsnNode c && c.owner.equals(POLY) && c.name.equals("hostCode")) {
                translates = true;
            }
        }
        assertTrue(translates, "a Fabric keybind would otherwise bind the wrong key on 26.3");
    }

    @Test
    @DisplayName("an old NeoForge conflict-context keybind is rebuilt on 26.x")
    void conflictContextKeybindIsBridged() {
        Common_1_21_11_to_26_1_ClassMoves.registerKeyMappingBridges26x(transformer);
        String desc = "(Ljava/lang/String;Lnet/neoforged/neoforge/client/settings/IKeyConflictContext;"
                + "L" + TYPE + ";ILjava/lang/String;)V";
        List<String> out = trace(transformer.transformClass(constructs(desc, mv -> {
            mv.visitLdcInsn("key.mod.open");
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitIntInsn(BIPUSH, 82);
            mv.visitLdcInsn("key.categories.mod");
        }), "test/mod/Keys"));

        assertTrue(out.contains("call " + POLY + ".createConflict"),
                "NeoForge removed the String-category overload at 1.21.9: " + out);
    }

    /** A client setup that uses each input path once, the way a 26.2 mod would. */
    private static byte[] clientSetup() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setup",
                "(Lcom/mojang/blaze3d/platform/Window;" + CATEGORY + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, KEY_MAPPING);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("key.mod.open");
        mv.visitFieldInsn(GETSTATIC, TYPE, "KEYSYM", "L" + TYPE + ";");
        mv.visitIntInsn(BIPUSH, 82);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, KEY_MAPPING, "<init>",
                "(Ljava/lang/String;L" + TYPE + ";I" + CATEGORY + ")V", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitIntInsn(SIPUSH, 340);
        mv.visitMethodInsn(INVOKESTATIC, CONSTANTS, "isKeyDown",
                "(Lcom/mojang/blaze3d/platform/Window;I)Z", false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, TYPE, "KEYSYM", "L" + TYPE + ";");
        mv.visitIntInsn(BIPUSH, 82);
        mv.visitMethodInsn(INVOKEVIRTUAL, TYPE, "getOrCreate", "(I)L" + CONSTANTS + "$Key;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A method that runs {@code new KeyMapping(...)} with the given constructor. */
    private static byte[] constructs(String desc, java.util.function.Consumer<MethodVisitor> args) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setup", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, KEY_MAPPING);
        mv.visitInsn(DUP);
        args.accept(mv);
        mv.visitMethodInsn(INVOKESPECIAL, KEY_MAPPING, "<init>", desc, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> trace(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) out.add("call " + c.owner + "." + c.name);
                if (insn instanceof FieldInsnNode f) out.add("field " + f.owner + "." + f.name);
            }
        }
        return out;
    }
}
