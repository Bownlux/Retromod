/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #394: Villager Recruits (Forge 1.20.1) reads its saved equipment with the static {@code Mob.getEquipmentSlotForItem},
 * which 1.20.5 made an instance method, so every recruit failed to load with {@code IncompatibleClassChangeError}.
 */
class Legacy1205EquipmentSlotTest {

    private static final String DESC = Legacy1205MemberBridge.SLOT_FOR_ITEM;
    private static final String HELPER = Legacy1205MemberBridge.EQUIPMENT_SLOTS;

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        transformer.setModSourceVersion(null);
        transformer.clearJarClassBytesProvider();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old static call asks the helper, which reads Equipable on 1.21.1 and the equippable component on 26.3")
    void staticCallReachesTheHelper() {
        for (String host : List.of("1.21.1", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = host;
            transformer.clearRedirectsForTesting();
            Legacy1205MemberBridge.registerStaticEquipmentSlot(transformer);
            transformer.setModSourceVersion("1.20.1");

            List<String> calls = calls(transformer.transformClass(inventoryEntity(), "mod/InventoryEntity"));
            assertTrue(calls.contains("static " + HELPER + ".getEquipmentSlotForItem" + DESC), host + ": " + calls);

            String helper = new String(transformer.getSyntheticClasses().get(HELPER), StandardCharsets.ISO_8859_1);
            boolean component = host.equals("26.3");
            assertEquals(component, helper.contains("net/minecraft/world/item/equipment/Equippable"), host);
            assertEquals(!component, helper.contains("net/minecraft/world/item/Equipable"), host);
            new ClassReader(transformer.getSyntheticClasses().get(HELPER))
                    .accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    @Test
    @DisplayName("a mod built for 1.20.5 or newer keeps its own static call")
    void newerModsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Legacy1205MemberBridge.registerStaticEquipmentSlot(transformer);
        transformer.setModSourceVersion("1.21.1");
        List<String> calls = calls(transformer.transformClass(inventoryEntity(), "mod/InventoryEntity"));
        assertTrue(calls.contains("static " + Legacy1205MemberBridge.MOB + ".getEquipmentSlotForItem" + DESC), calls.toString());
    }

    @Test
    @DisplayName("an instance call keeps the entity's own method, even from a mod whose version is unknown")
    void instanceCallsKeepTheHostMethod() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Legacy1205MemberBridge.registerStaticEquipmentSlot(transformer);
        // A Fabric mod with "minecraft": "*" gives no version, and 26.x maps its intermediary call to this name.
        transformer.setModSourceVersion(null);
        Map<String, byte[]> jar = Map.of("mod/GuardEntity", mobSubclass("mod/GuardEntity", INVOKEVIRTUAL));
        transformer.setJarClassBytesProvider(jar::get);
        List<String> calls = calls(transformer.transformClass(jar.get("mod/GuardEntity"), "mod/GuardEntity"));
        assertEquals(List.of(Legacy1205MemberBridge.LIVING + ".getEquipmentSlotForItem" + DESC,
                "mod/GuardEntity.getEquipmentSlotForItem" + DESC), calls);
    }

    @Test
    @DisplayName("an unqualified old static call in a mob subclass reaches the helper too")
    void inheritedStaticCallReachesTheHelper() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Legacy1205MemberBridge.registerStaticEquipmentSlot(transformer);
        transformer.setModSourceVersion("1.20.1");
        Map<String, byte[]> jar = Map.of("mod/GuardEntity", mobSubclass("mod/GuardEntity", INVOKESTATIC));
        transformer.setJarClassBytesProvider(jar::get);
        List<String> calls = calls(transformer.transformClass(jar.get("mod/GuardEntity"), "mod/GuardEntity"));
        assertEquals(List.of("static " + HELPER + ".getEquipmentSlotForItem" + DESC,
                "static " + HELPER + ".getEquipmentSlotForItem" + DESC), calls);
    }

    @Test
    @DisplayName("where the loader extends the stack, the helper asks stack.getEquipmentSlot() first")
    void loaderAnswerComesFirst() {
        ClassNode helper = new ClassNode();
        new ClassReader(Legacy1205MemberBridge.generateEquipmentSlots(false, true)).accept(helper, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : helper.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(List.of("net/minecraft/world/item/ItemStack.getEquipmentSlot", "net/minecraft/world/item/Equipable.get",
                "net/minecraft/world/item/Equipable.getEquipmentSlot"), calls);
        new ClassReader(Legacy1205MemberBridge.generateEquipmentSlots(false, true))
                .accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    /** A mob subclass that reads a slot on an entity and then unqualified on itself, with {@code opcode} for the second call. */
    private static byte[] mobSubclass(String name, int opcode) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, Legacy1205MemberBridge.MOB, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "slots", "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V",
                null, null);
        mv.visitCode();
        if (opcode == INVOKESTATIC) {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, Legacy1205MemberBridge.MOB, "getEquipmentSlotForItem", DESC, false);
        } else {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, Legacy1205MemberBridge.LIVING, "getEquipmentSlotForItem", DESC, false);
        }
        mv.visitInsn(POP);
        if (opcode == INVOKEVIRTUAL) mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(opcode, name, "getEquipmentSlotForItem", DESC, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Reads a saved stack's slot through the old static call, as Recruits' inventory entity does. */
    private static byte[] inventoryEntity() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/InventoryEntity", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "slotOf", "(Lnet/minecraft/world/item/ItemStack;)Ljava/lang/Object;",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, Legacy1205MemberBridge.MOB, "getEquipmentSlotForItem", DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    calls.add((call.getOpcode() == INVOKESTATIC ? "static " : "") + call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }
}
