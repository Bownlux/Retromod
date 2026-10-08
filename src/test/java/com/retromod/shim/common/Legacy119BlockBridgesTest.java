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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #264 and #277 (1.19.2 MCreator mods on Forge 1.20.1): buttons extended the deleted
 * {@code WoodButtonBlock}, doors and trapdoors called {@code super(properties)} on constructors
 * 1.19.4 gave a {@code BlockSetType}, and entity damage was built from the deleted
 * {@code EntityDamageSource}. These are the generated stand-ins.
 */
class Legacy119BlockBridgesTest {

    private static final String PROPS = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String SET_TYPE = "Lnet/minecraft/world/level/block/state/properties/BlockSetType;";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.20.1 host's button takes properties first; a 1.21 host's takes them last")
    void buttonFollowsTheHostConstructor() {
        assertEquals("(" + PROPS + SET_TYPE + "IZ)V", superCall(
                LegacyButtonBlockBridge.generate("g/B", "OAK", 30, true, false)).desc);
        MethodInsnNode modern = superCall(LegacyButtonBlockBridge.generate("g/B", "OAK", 30, true, true));
        assertEquals("(" + SET_TYPE + "I" + PROPS + ")V", modern.desc);
    }

    @Test
    @DisplayName("every door shape forwards to the host constructor with arguments reordered by type")
    void doorShapesForwardToTheHost() {
        String host = "(" + SET_TYPE + PROPS + ")V";
        byte[] door = LegacyBlockSetTypeBridge.generate("g/D", "net/minecraft/world/level/block/DoorBlock",
                host, "net/minecraft/world/level/block/state/properties/BlockSetType",
                "(" + PROPS + ")V", "(" + PROPS + SET_TYPE + ")V", host);
        new ClassReader(door).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);

        ClassNode cn = new ClassNode();
        new ClassReader(door).accept(cn, 0);
        List<MethodNode> ctors = cn.methods.stream().filter(m -> m.name.equals("<init>")).toList();
        assertEquals(3, ctors.size(), "one constructor per shape");
        for (MethodNode ctor : ctors) {
            MethodInsnNode call = (MethodInsnNode) java.util.Arrays.stream(ctor.instructions.toArray())
                    .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
            assertEquals(host, call.desc, ctor.desc + " must call the host constructor");
        }
        MethodNode oldShape = ctors.stream().filter(m -> m.desc.equals("(" + PROPS + ")V")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(oldShape.instructions.toArray()).anyMatch(i ->
                i instanceof FieldInsnNode f && f.name.equals("OAK")),
                "a shape without a set type gets OAK");
    }

    @Test
    @DisplayName("new EntityDamageSource(id, attacker).bypassArmor() becomes the attacker's own source")
    void entityDamageSourceUsesTheAttackersSources() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        LegacyDamageSourceBridge.register(transformer);
        String entitySource = "net/minecraft/world/damagesource/EntityDamageSource";
        String source = "net/minecraft/world/damagesource/DamageSource";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/Hit", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "hit", "(Lnet/minecraft/world/entity/Entity;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, entitySource);
        mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
        mv.visitLdcInsn("mod.slash");
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, entitySource, "<init>",
                "(Ljava/lang/String;Lnet/minecraft/world/entity/Entity;)V", false);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, entitySource, "bypassArmor",
                "()L" + source + ";", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Hit")).accept(out, 0);
        List<String> calls = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : out.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            assertNotEquals(org.objectweb.asm.Opcodes.NEW, insn.getOpcode(), "no NEW of the deleted class");
        }
        assertEquals(List.of(LegacyDamageSourceBridge.SOURCES + ".entity",
                LegacyDamageSourceBridge.SOURCES + ".unchanged"), calls);
        new ClassReader(LegacyDamageSourceBridge.generate())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("a 1.19.2 armor material gains per-type methods that ask its per-slot ones")
    void armorMaterialGainsPerTypeMethods() {
        String slot = "Lnet/minecraft/world/entity/EquipmentSlot;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/Mat", null,
                "java/lang/Object", new String[]{"net/minecraft/world/item/ArmorMaterial"});
        for (String name : new String[]{"getDurabilityForSlot", "getDefenseForSlot"}) {
            var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, name, "(" + slot + ")I", null, null);
            mv.visitCode();
            mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_3);
            mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();

        byte[] adapted = LegacyArmorBridge.adaptMaterial(cw.toByteArray(), RetromodTransformer.getInstance());
        new ClassReader(adapted).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        ClassNode cn = new ClassNode();
        new ClassReader(adapted).accept(cn, 0);
        String typeDesc = "(Lnet/minecraft/world/item/ArmorItem$Type;)I";
        for (String[] pair : new String[][]{{"getDurabilityForType", "getDurabilityForSlot"},
                {"getDefenseForType", "getDefenseForSlot"}}) {
            MethodNode bridge = cn.methods.stream().filter(m -> m.name.equals(pair[0]) && m.desc.equals(typeDesc))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + pair[0]));
            assertTrue(java.util.Arrays.stream(bridge.instructions.toArray()).anyMatch(i ->
                    i instanceof MethodInsnNode c && c.name.equals(pair[1])), pair[0] + " must call " + pair[1]);
        }
        assertSame(adapted, LegacyArmorBridge.adaptMaterial(adapted, RetromodTransformer.getInstance()),
                "a material that already has the methods is left alone");
    }

    @Test
    @DisplayName("new ArmorItem(material, slot, properties) builds the slot-converting base")
    void armorItemUsesTheGeneratedBase() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        LegacyArmorBridge.register(transformer);
        new ClassReader(LegacyArmorBridge.generateArmorItem())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        String armor = "net/minecraft/world/item/ArmorItem";
        String oldCtor = "(Lnet/minecraft/world/item/ArmorMaterial;Lnet/minecraft/world/entity/EquipmentSlot;"
                + "Lnet/minecraft/world/item/Item$Properties;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/Armor", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "make", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, armor);
        mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
        for (int i = 0; i < 3; i++) mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, armor, "<init>", oldCtor, false);
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Armor")).accept(out, 0);
        MethodInsnNode call = (MethodInsnNode) java.util.Arrays.stream(out.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals(LegacyArmorBridge.ARMOR_ITEM_BASE + ".create", call.owner + "." + call.name);
    }

    private static MethodInsnNode superCall(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return (MethodInsnNode) java.util.Arrays.stream(cn.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
    }
}
