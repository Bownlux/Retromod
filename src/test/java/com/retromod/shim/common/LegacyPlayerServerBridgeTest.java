/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_21_5_to_1_21_6;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyPlayerServerBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A mod reading {@code player.server} through the Fabric 1.21.6 shim, in Mojang and intermediary names. */
class LegacyPlayerServerBridgeTest {

    private static final String MOD = "mod/Sync";
    private static final String SERVER_DESC = "L" + SERVER + ";";
    private static final String PLAYER_ID = INTERMEDIARY.player();
    private static final String SERVER_LEVEL_ID = INTERMEDIARY.serverLevel();

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on Fabric 1.21.11 the intermediary read goes to its own helper and the Mojang read is left alone")
    void intermediaryReadOn12111() {
        List<String> reads = reads(transformed("1.21.11"));
        assertEquals(List.of(
                "INVOKESTATIC " + INTERMEDIARY.helper() + ".server" + INTERMEDIARY.read(),
                "GETFIELD " + SERVER_PLAYER + ".server"), reads);
    }

    @Test
    @DisplayName("a host before 1.21.6 keeps the public field, so the read is unchanged")
    void unchangedBefore1216() {
        assertEquals(List.of("GETFIELD " + PLAYER_ID + ".field_13995", "GETFIELD " + SERVER_PLAYER + ".server"),
                reads(transformed("1.21.5")));
    }

    @Test
    @DisplayName("on 26.3 the Mojang read is bridged and the intermediary id is not")
    void mojangReadOn263() {
        assertEquals(List.of(
                "GETFIELD " + PLAYER_ID + ".field_13995",
                "INVOKESTATIC " + HELPER + ".server" + MOJANG.read()), reads(transformed("26.3")));
    }

    @Test
    @DisplayName("the field counts as hidden only when the host declares it non-public")
    void fieldHiddenFollowsTheHost() {
        assertTrue(fieldHidden(player(ACC_PRIVATE | ACC_FINAL), INTERMEDIARY), "the 1.21.11 shape");
        assertFalse(fieldHidden(player(ACC_PUBLIC | ACC_FINAL), INTERMEDIARY), "1.21.5, or a widened host");
        assertFalse(fieldHidden(player(ACC_PRIVATE | ACC_FINAL), MOJANG), "another namespace's field name");
    }

    @Test
    @DisplayName("the intermediary helper reads the server through serverLevel() and verifies")
    void intermediaryHelperShape() throws Exception {
        ClassNode helper = node(generateHelper(INTERMEDIARY));
        MethodNode read = method(helper, "server");
        assertEquals(List.of(PLAYER_ID + ".method_51469()L" + SERVER_LEVEL_ID + ";",
                SERVER_LEVEL_ID + ".method_8503()" + SERVER_DESC), calls(read));
        assertTrue(casts(read).isEmpty(), "serverLevel() already returns a server level");
        verify(helper);

        // The Mojang helper keeps its cast after Entity.level().
        MethodNode mojang = method(node(generateHelper(MOJANG)), "server");
        assertEquals(List.of(ENTITY + ".level()L" + LEVEL + ";", SERVER_LEVEL + ".getServer()" + SERVER_DESC),
                calls(mojang));
        assertEquals(List.of(SERVER_LEVEL), casts(mojang));
    }

    @Test
    @DisplayName("the bridged read returns the player's server where the old read hits a private field")
    void bridgedReadRunsOnTheHostShape() throws Throwable {
        Map<String, byte[]> classes = new HashMap<>(hostStandIns());
        Map<String, byte[]> before = new HashMap<>(classes);
        before.put(MOD, intermediaryMod());
        Throwable old = assertThrows(InvocationTargetException.class, () -> readServer(before)).getCause();
        assertInstanceOf(IllegalAccessError.class, old, "the old read fails on the 1.21.11 shape");

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_5_to_1_21_6().registerRedirects(transformer);
        classes.put(MOD, transformer.transformClass(intermediaryMod(), MOD + ".class"));
        classes.putAll(transformer.getSyntheticClasses());
        assertEquals(SERVER.replace('/', '.'), readServer(classes).getClass().getName());
    }

    /** The mod class with both reads, transformed by the Fabric 1.21.6 shim for a host of {@code target}. */
    private static byte[] transformed(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_5_to_1_21_6().registerRedirects(transformer);
        return transformer.transformClass(bothReads(), MOD + ".class");
    }

    /** {@code player.server} as a 1.21.1 Fabric mod compiled it, then as a Mojang-named mod did. */
    private static byte[] bothReads() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "servers",
                "(L" + PLAYER_ID + ";L" + SERVER_PLAYER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, PLAYER_ID, "field_13995", SERVER_DESC);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETFIELD, SERVER_PLAYER, "server", SERVER_DESC);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code return player.server}, in intermediary names. */
    private static byte[] intermediaryMod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "server", "(L" + PLAYER_ID + ";)" + SERVER_DESC,
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, PLAYER_ID, "field_13995", SERVER_DESC);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.21.11 intermediary shape: a private server field and a level getter that returns a server level. */
    private static Map<String, byte[]> hostStandIns() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(SERVER, emptyClass(SERVER));

        ClassWriter level = classWriter(SERVER_LEVEL_ID);
        level.visitField(ACC_PUBLIC | ACC_FINAL, "server", SERVER_DESC, null, null).visitEnd();
        MethodVisitor init = level.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitTypeInsn(NEW, SERVER);
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, SERVER, "<init>", "()V", false);
        init.visitFieldInsn(PUTFIELD, SERVER_LEVEL_ID, "server", SERVER_DESC);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        getter(level, SERVER_LEVEL_ID, "method_8503", "server", SERVER_DESC);
        level.visitEnd();
        classes.put(SERVER_LEVEL_ID, level.toByteArray());

        ClassWriter player = classWriter(PLAYER_ID);
        player.visitField(ACC_PRIVATE | ACC_FINAL, "field_13995", SERVER_DESC, null, null).visitEnd();
        player.visitField(ACC_PUBLIC | ACC_FINAL, "level", "L" + SERVER_LEVEL_ID + ";", null, null).visitEnd();
        MethodVisitor playerInit = player.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        playerInit.visitCode();
        playerInit.visitVarInsn(ALOAD, 0);
        playerInit.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        playerInit.visitVarInsn(ALOAD, 0);
        playerInit.visitTypeInsn(NEW, SERVER_LEVEL_ID);
        playerInit.visitInsn(DUP);
        playerInit.visitMethodInsn(INVOKESPECIAL, SERVER_LEVEL_ID, "<init>", "()V", false);
        playerInit.visitFieldInsn(PUTFIELD, PLAYER_ID, "level", "L" + SERVER_LEVEL_ID + ";");
        playerInit.visitVarInsn(ALOAD, 0);
        playerInit.visitInsn(ACONST_NULL);
        playerInit.visitFieldInsn(PUTFIELD, PLAYER_ID, "field_13995", SERVER_DESC);
        playerInit.visitInsn(RETURN);
        playerInit.visitMaxs(0, 0);
        playerInit.visitEnd();
        getter(player, PLAYER_ID, "method_51469", "level", "L" + SERVER_LEVEL_ID + ";");
        player.visitEnd();
        classes.put(PLAYER_ID, player.toByteArray());
        return classes;
    }

    private static ClassWriter classWriter(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        return cw;
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = classWriter(name);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void getter(ClassWriter cw, String owner, String name, String field, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, owner, field, desc);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Loads {@code classes} in one loader and runs the mod's {@code server(new class_3222())}. */
    private static Object readServer(Map<String, byte[]> classes) throws Exception {
        ClassLoader loader = new ClassLoader(LegacyPlayerServerBridgeTest.class.getClassLoader()) {
            @Override
            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) return super.loadClass(name, resolve);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> player = loader.loadClass(PLAYER_ID.replace('/', '.'));
        return loader.loadClass(MOD.replace('/', '.')).getMethod("server", player)
                .invoke(null, player.getConstructor().newInstance());
    }

    private static ClassNode player(int access) {
        ClassNode node = new ClassNode();
        node.name = PLAYER_ID;
        node.fields.add(new FieldNode(access, "field_13995", SERVER_DESC, null, null));
        return node;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    /** Each server read in the mod's method: a field read or the static call that replaced it. */
    private static List<String> reads(byte[] bytes) {
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : method(node(bytes), "servers").instructions.toArray()) {
            if (insn instanceof FieldInsnNode field) reads.add("GETFIELD " + field.owner + "." + field.name);
            if (insn instanceof MethodInsnNode call) reads.add("INVOKESTATIC " + call.owner + "." + call.name + call.desc);
        }
        return reads;
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static List<String> casts(MethodNode method) {
        List<String> casts = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == CHECKCAST) casts.add(type.desc);
        }
        return casts;
    }
}
