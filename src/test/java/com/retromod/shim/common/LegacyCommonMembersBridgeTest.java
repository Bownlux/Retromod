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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old boats, rule tests and the other reshaped members keep working where the host shows each change. */
class LegacyCommonMembersBridgeTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Every helper verifies when every change is on")
    void helpersVerify() throws Exception {
        LegacyCommonMembersBridge.Shape all = new LegacyCommonMembersBridge.Shape(true, true, true, true, true, true, true, true, true, true, true, true);
        ClassNode helper = read(LegacyCommonMembersBridge.generate(all));
        for (MethodNode m : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, m);
        assertEquals(15, helper.methods.size(), "one helper per old member");
    }

    @Test
    @DisplayName("A rule only registers where the host shows its change, and an unreadable host leaves all off")
    void followsTheHost() {
        assertFalse(LegacyCommonMembersBridge.shape(name -> null).any());
        ClassNode boat = new ClassNode();
        boat.name = LegacyCommonMembersBridge.BOAT;
        boat.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", LegacyCommonMembersBridge.NEW_BOAT, null, null));
        LegacyCommonMembersBridge.Shape shape = LegacyCommonMembersBridge.shape(Map.of(boat.name, boat)::get);
        assertTrue(shape.boats());
        assertFalse(shape.ruleTests());
    }

    @Test
    @DisplayName("An old boat's super call asks the boat's own drop item, and an old rule test override is reached through the current test")
    void boatsAndRuleTests() throws Exception {
        ClassNode boat = new ClassNode();
        boat.name = LegacyCommonMembersBridge.BOAT;
        boat.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", LegacyCommonMembersBridge.NEW_BOAT, null, null));
        ClassNode rule = new ClassNode();
        rule.name = LegacyCommonMembersBridge.RULE_TEST;
        rule.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "test", LegacyCommonMembersBridge.NEW_RULE, null, null));
        LegacyCommonMembersBridge.register(transformer, Map.of(boat.name, boat, rule.name, rule)::get);

        ClassNode myBoat = read(LegacyCommonMembersBridge.apply(subclass("mod/MyBoat", LegacyCommonMembersBridge.BOAT, true)));
        List<String> boatCalls = calls(myBoat, "<init>").stream().map(c -> c.owner + "." + c.name).toList();
        assertEquals(List.of("java/util/concurrent/atomic/AtomicReference.<init>", LegacyCommonMembersBridge.BOAT_DROPS + ".of",
                LegacyCommonMembersBridge.BOAT + ".<init>", "java/util/concurrent/atomic/AtomicReference.set"), boatCalls,
                "the supplier holds the boat once its super call returns");
        assertEquals(LegacyCommonMembersBridge.NEW_BOAT, calls(myBoat, "<init>").get(2).desc);
        for (MethodNode method : myBoat.methods) new Analyzer<>(new BasicVerifier()).analyze(myBoat.name, method);

        ClassNode myRule = read(LegacyCommonMembersBridge.apply(subclass("mod/MyRule", LegacyCommonMembersBridge.RULE_TEST, false)));
        MethodNode bridge = myRule.methods.stream().filter(m -> m.desc.equals(LegacyCommonMembersBridge.NEW_RULE)).findFirst().orElseThrow();
        new Analyzer<>(new BasicVerifier()).analyze(myRule.name, bridge);
        assertEquals(LegacyCommonMembersBridge.OLD_RULE, calls(myRule, "test").stream()
                .filter(c -> c.owner.equals("mod/MyRule")).findFirst().orElseThrow().desc);
    }

    @Test
    @DisplayName("A mod's rule test that extends another mod's rule test gets the current test too")
    void ruleTestSubclassOfAModRuleTest() {
        ClassNode rule = new ClassNode();
        rule.name = LegacyCommonMembersBridge.RULE_TEST;
        rule.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "test", LegacyCommonMembersBridge.NEW_RULE, null, null));
        LegacyCommonMembersBridge.register(transformer, Map.of(rule.name, rule)::get);
        byte[] parent = subclass("mod/BaseRule", LegacyCommonMembersBridge.RULE_TEST, false);
        byte[] child = subclass("mod/ChildRule", "mod/BaseRule", false);
        transformer.setJarClassBytesProvider(Map.of("mod/BaseRule", parent, "mod/ChildRule", child)::get);
        try {
            ClassNode fixed = read(LegacyCommonMembersBridge.apply(child));
            assertTrue(fixed.methods.stream().anyMatch(m -> m.desc.equals(LegacyCommonMembersBridge.NEW_RULE)));
        } finally {
            transformer.setJarClassBytesProvider(null);
        }
    }

    @Test
    @DisplayName("An old armor call moved onto Retromod's generated armor base reads the slot from the item's component")
    void armorSlotCallsReachTheHelper() {
        ClassNode item = new ClassNode();
        item.name = "net/minecraft/world/item/Item";
        LegacyCommonMembersBridge.register(transformer, Map.of(item.name, item)::get);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/ArmorUser", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "slot",
                "(Lcom/retromod/generated/LegacyArmorItem;)" + LegacyCommonMembersBridge.SLOT_DESC.substring(2), null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/retromod/generated/LegacyArmorItem", "getEquipmentSlot", LegacyCommonMembersBridge.SLOT_DESC, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        MethodInsnNode call = calls(read(LegacyCommonMembersBridge.apply(cw.toByteArray())), "slot").get(0);
        assertEquals(LegacyCommonMembersBridge.HELPER + ".equipmentSlot", call.owner + "." + call.name);
    }

    @Test
    @DisplayName("The boat's drop supplier answers the boat's own renamed getDropItem")
    void boatDropAsksTheBoat() {
        java.util.concurrent.atomic.AtomicReference<Object> holder = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Supplier<Object> drop = com.retromod.shim.common.embedded.LegacyBoatDrops.of(holder, "OAK_BOAT");
        holder.set(new RenamedBoat());
        assertEquals("birch boat", drop.get());
    }

    /** What HostFinalOverrideAdapter leaves of an old boat's getDropItem override. */
    public static final class RenamedBoat {
        @SuppressWarnings("unused")
        private Object retromod$final$getDropItem() {
            return "birch boat";
        }
    }

    /** A boat whose constructor calls the old two-argument super, or a rule test with only the old test. */
    private static byte[] subclass(String name, String superName, boolean boat) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        if (boat) {
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", LegacyCommonMembersBridge.OLD_BOAT, null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, 1);
            init.visitVarInsn(Opcodes.ALOAD, 2);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", LegacyCommonMembersBridge.OLD_BOAT, false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        } else {
            MethodVisitor test = cw.visitMethod(Opcodes.ACC_PUBLIC, "test", LegacyCommonMembersBridge.OLD_RULE, null, null);
            test.visitCode();
            test.visitInsn(Opcodes.ICONST_1);
            test.visitInsn(Opcodes.IRETURN);
            test.visitMaxs(0, 0);
            test.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node, String method) {
        List<MethodInsnNode> calls = new java.util.ArrayList<>();
        node.methods.stream().filter(m -> m.name.equals(method)).forEach(m -> m.instructions.forEach(i -> {
            if (i instanceof MethodInsnNode c) calls.add(c);
        }));
        return calls;
    }
}
