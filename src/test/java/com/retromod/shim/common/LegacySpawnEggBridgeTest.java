/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
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
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Longwings 0.9.5 (Fabric 1.20.1) on 26.3 built its spawn eggs with
 * {@code new SpawnEggItem(type, base, spots, properties)}, which 26.2 replaced with a properties-only
 * constructor and {@code Properties.spawnEgg(type)}. Its item class failed to initialize with
 * {@code NoSuchMethodError}, which stopped the mod's entry point.
 */
class LegacySpawnEggBridgeTest {

    private static final String SPAWN_EGG = "net/minecraft/world/item/SpawnEggItem";
    private static final String PROPS = "net/minecraft/world/item/Item$Properties";
    private static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new SpawnEggItem(type, base, spots, properties) becomes the bridge's factory")
    void rewritesTheOldConstructor() {
        RetromodTransformer transformer = bridged();

        List<String> calls = calls(transformer.transformClass(eggHolder(), "mod/Eggs"), "<clinit>");

        assertTrue(calls.contains(LegacySpawnEggBridge.INTERNAL + ".create"), calls.toString());
        assertFalse(calls.contains(SPAWN_EGG + ".<init>"), "the removed constructor must not survive: " + calls);
    }

    @Test
    @DisplayName("a subclass's super(type, base, spots, properties) passes the properties with the type on them")
    void rewritesASubclassSuperCall() {
        RetromodTransformer transformer = bridged();

        List<String> calls = calls(transformer.transformClass(eggSubclass(), "mod/MothEgg"), "<init>");

        assertEquals(List.of(LegacySpawnEggBridge.INTERNAL + ".properties", SPAWN_EGG + ".<init>"), calls);
        MethodInsnNode superCall = superCall(transformer.transformClass(eggSubclass(), "mod/MothEgg"));
        assertEquals("(L" + PROPS + ";)V", superCall.desc);
    }

    @Test
    @DisplayName("the bridge names the entity on the properties and builds the egg the 26.2 way")
    void generatedBridgeUsesTheCurrentApi() {
        ClassNode bridge = new ClassNode();
        new ClassReader(LegacySpawnEggBridge.generate()).accept(bridge, 0);

        assertEquals(List.of(PROPS + ".spawnEgg"), callsIn(method(bridge, "properties")));
        assertEquals(List.of(LegacySpawnEggBridge.INTERNAL + ".properties", SPAWN_EGG + ".<init>"),
                callsIn(method(bridge, "create")));
    }

    @Test
    @DisplayName("only a 26.2 or newer host gets the bridge, since 26.1 has no Properties.spawnEgg")
    void registeredFrom26Point2() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.fabric.Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacySpawnEggBridge.INTERNAL),
                "the 26.1 shim must not register it");
        Mc26_1To26_2CoreMoves.register(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacySpawnEggBridge.INTERNAL));
    }

    private static RetromodTransformer bridged() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacySpawnEggBridge.register(transformer);
        return transformer;
    }

    /** {@code static Item EGG = new SpawnEggItem(null, 0xECEBEB, 0xFFEC5B, new Item.Properties());} */
    private static byte[] eggHolder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Eggs", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "EGG", "Lnet/minecraft/world/item/Item;", null, null)
                .visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, SPAWN_EGG);
        init.visitInsn(Opcodes.DUP);
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitLdcInsn(0xECEBEB);
        init.visitLdcInsn(0xFFEC5B);
        init.visitTypeInsn(Opcodes.NEW, PROPS);
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, PROPS, "<init>", "()V", false);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, SPAWN_EGG, "<init>", LegacySpawnEggBridge.OLD_CONSTRUCTOR, false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/Eggs", "EGG", "Lnet/minecraft/world/item/Item;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code MothEgg(EntityType type, Item.Properties p) { super(type, 1, 2, p); }} */
    private static byte[] eggSubclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/MothEgg", null, SPAWN_EGG, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(L" + ENTITY_TYPE + ";L" + PROPS + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitInsn(Opcodes.ICONST_1);
        init.visitInsn(Opcodes.ICONST_2);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, SPAWN_EGG, "<init>", LegacySpawnEggBridge.OLD_CONSTRUCTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] classBytes, String methodName) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return callsIn(method(node, methodName)).stream()
                .filter(call -> !call.startsWith(PROPS + ".<init>") && !call.contains("itemProps"))
                .toList();
    }

    private static MethodInsnNode superCall(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (AbstractInsnNode insn : method(node, "<init>").instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(SPAWN_EGG)) return call;
        }
        throw new AssertionError("no super call");
    }

    private static List<String> callsIn(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
