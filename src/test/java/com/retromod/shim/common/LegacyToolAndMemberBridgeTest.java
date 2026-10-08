/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mod's tools, entities and level reads against the 1.20.5 shapes. */
class LegacyToolAndMemberBridgeTest {

    private static final String TIER = "Lnet/minecraft/world/item/Tier;";
    private static final String PROPS = "Lnet/minecraft/world/item/Item$Properties;";
    private static final String SWORD = "net/minecraft/world/item/SwordItem";
    private static final String DIGGER = "net/minecraft/world/item/DiggerItem";
    private static final String TAG = "Lnet/minecraft/tags/TagKey;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void aNewSwordWithDamageAndSpeedGoesThroughTheFactory() {
        RetromodTransformer transformer = tools();

        List<String> calls = calls(transformer.transformClass(swordFactory(), "test/ModItems.class"), "sword");

        assertTrue(calls.contains(LegacyToolItemBridge.HELPER + ".newSwordItem(" + TIER + "IF" + PROPS + ")L" + SWORD + ";"),
                "the old sword constructor must go through the factory: " + calls);
        assertFalse(calls.contains(SWORD + ".<init>(" + TIER + "IF" + PROPS + ")V"));
    }

    @Test
    void aModToolSubclassCallsTheModernSuperConstructor() {
        RetromodTransformer transformer = tools();

        ClassNode tool = node(transformer.transformClass(diggerSubclass(), "test/ModTool.class"));
        List<String> calls = calls(tool, "<init>");

        assertTrue(calls.contains(LegacyToolItemBridge.HELPER + ".stashDiggerItem(FF" + TIER + TAG + PROPS + ")V"),
                "the old arguments are converted before the super call: " + calls);
        assertTrue(calls.contains(DIGGER + ".<init>(" + TIER + TAG + PROPS + ")V"),
                "the super call uses the 1.20.5 constructor: " + calls);
    }

    @Test
    void generatedToolHelperIsStructurallyValid() throws Exception {
        verify(LegacyToolItemBridge.generateHelper(LegacyToolItemBridge.candidates()));
    }

    @Test
    void removedEntityAndLevelMembersReachTheHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1205MemberBridge.registerRedirects(transformer, Legacy1205MemberBridge.MEMBERS,
                List.of(Legacy1205MemberBridge.CLASS_RENAMES), true);

        ClassNode node = node(transformer.transformClass(entityCaller(), "test/ModMob.class"));
        List<String> calls = calls(node, "setup");

        String helper = Legacy1205MemberBridge.HELPER;
        assertTrue(calls.contains(helper + ".setMaxUpStep(Lnet/minecraft/world/entity/Entity;F)V"), calls.toString());
        assertTrue(calls.contains(helper + ".setSecondsOnFire(Lnet/minecraft/world/entity/Entity;I)V"), calls.toString());
        assertTrue(calls.contains(helper + ".getXSpawn(Lnet/minecraft/world/level/storage/LevelData;)I"), calls.toString());
        boolean turtleScute = false;
        for (AbstractInsnNode insn : method(node, "setup").instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(Legacy1205MemberBridge.ITEMS)) {
                turtleScute = field.name.equals("TURTLE_SCUTE");
            }
        }
        assertTrue(turtleScute, "Items.SCUTE must read TURTLE_SCUTE");
    }

    @Test
    void bufferReadersAndWritersBecomeStreamCodecsWithTheirMethods() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1205MemberBridge.registerRedirects(transformer, List.of(),
                List.of(Legacy1205MemberBridge.CLASS_RENAMES), false);

        assertEquals("decode", transformer.registeredMethodRename("net/minecraft/network/FriendlyByteBuf$Reader", "apply"));
        assertEquals("encode", transformer.registeredMethodRename("net/minecraft/network/FriendlyByteBuf$Writer", "accept"));
    }

    @Test
    void generatedMemberHelperIsStructurallyValid() throws Exception {
        verify(Legacy1205MemberBridge.generateHelper(Legacy1205MemberBridge.MEMBERS));
    }

    private static RetromodTransformer tools() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyToolItemBridge.registerRedirects(transformer, LegacyToolItemBridge.candidates());
        return transformer;
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = node(bytes);
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static byte[] swordFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModItems", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sword",
                "(" + TIER + PROPS + ")Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SWORD);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitLdcInsn(-2.4f);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SWORD, "<init>", "(" + TIER + "IF" + PROPS + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] diggerSubclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModTool", null, DIGGER, null);
        String desc = "(FF" + TIER + TAG + PROPS + ")V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.FLOAD, 1);
        mv.visitVarInsn(Opcodes.FLOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, DIGGER, "<init>", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] entityCaller() {
        String entity = Legacy1205MemberBridge.ENTITY;
        String levelData = Legacy1205MemberBridge.LEVEL_DATA;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModMob", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setup",
                "(L" + entity + ";L" + levelData + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(1.0f);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, entity, "setMaxUpStep", "(F)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, entity, "setSecondsOnFire", "(I)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, levelData, "getXSpawn", "()I", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, Legacy1205MemberBridge.ITEMS, "SCUTE", "Lnet/minecraft/world/item/Item;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        return calls(node(bytes), methodName);
    }

    private static List<String> calls(ClassNode node, String methodName) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, methodName).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
