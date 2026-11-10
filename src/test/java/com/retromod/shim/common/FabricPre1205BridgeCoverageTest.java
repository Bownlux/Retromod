/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.fabric.FabricLegacyRegistrationApiShim;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.20.1 Fabric mod on 26.x gets the Mojang-named pre-1.20.5 bridges NeoForge and Forge get. */
class FabricPre1205BridgeCoverageTest {

    private static final String MOB_TYPE = "net/minecraft/world/entity/MobType";
    private static final String TREE_GROWER = "net/minecraft/world/level/block/grower/AbstractTreeGrower";
    private static final String BLOCK_ENTITY_BUILDER =
            "net/minecraft/world/level/block/entity/BlockEntityType$Builder";

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
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("Fabric 26.1 registers the MobType, entity data, tree grower, food and codec bridges")
    void fabric261RegistersTheBridges() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);

        Map<String, String> classes = transformer.getClassRedirects();
        assertEquals(LegacyMobTypeBridge.BRIDGE, classes.get(MOB_TYPE), "MobType is gone from 1.20.5");
        assertEquals(LegacyTreeGrowerBridge.GROWER, classes.get(TREE_GROWER), "AbstractTreeGrower is gone");
        assertSynthetics(LegacySynchedDataBridge.HELPER, LegacyFoodPropertiesBridge.HELPER,
                LegacyCodecTypeBridge.HELPER);
    }

    @Test
    @DisplayName("Fabric 26.3 keeps them, including the codec helper")
    void fabric263RegistersTheBridges() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);

        Map<String, String> classes = transformer.getClassRedirects();
        assertEquals(LegacyMobTypeBridge.BRIDGE, classes.get(MOB_TYPE));
        assertEquals(LegacyTreeGrowerBridge.GROWER, classes.get(TREE_GROWER));
        assertSynthetics(LegacySynchedDataBridge.HELPER, LegacyFoodPropertiesBridge.HELPER,
                LegacyCodecTypeBridge.HELPER);
    }

    @Test
    @DisplayName("an intermediary-named Fabric host gets none of them")
    void intermediaryHostRegistersNone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);

        Map<String, String> classes = transformer.getClassRedirects();
        assertFalse(classes.containsKey(MOB_TYPE), "a 1.21.4 Fabric mod never names MobType");
        assertFalse(classes.containsKey(TREE_GROWER), classes.toString());
        for (String helper : List.of(LegacySynchedDataBridge.HELPER, LegacyFoodPropertiesBridge.HELPER,
                LegacyCodecTypeBridge.HELPER)) {
            assertFalse(transformer.getSyntheticClasses().containsKey(helper), helper);
        }
    }

    @Test
    @DisplayName("an intermediary meat() call and MobType.UNDEAD read reach the bridges on 26.1")
    void intermediaryCallsReachTheBridges() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        IntermediaryToMojangMapper.applyTo(transformer);

        List<String> out = trace(transformer.transformClass(foodAndMobType(), "test/mod/Content"));
        assertTrue(out.contains("call " + LegacyFoodPropertiesBridge.HELPER + ".meat"),
                "1.20.5 removed FoodProperties.Builder.meat(): " + out);
        assertTrue(out.contains("field " + LegacyMobTypeBridge.BRIDGE + ".UNDEAD"),
                "1.20.5 removed MobType: " + out);
    }

    @Test
    @DisplayName("BlockEntityType.Builder still goes to Fabric API's builder on 26.3")
    void blockEntityBuilderStaysOnFabricApi() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        new FabricLegacyRegistrationApiShim().registerRedirects(transformer);

        assertEquals("net/fabricmc/fabric/api/object/builder/v1/block/entity/FabricBlockEntityTypeBuilder",
                transformer.getClassRedirects().get(BLOCK_ENTITY_BUILDER));
    }

    private void assertSynthetics(String... helpers) {
        for (String helper : helpers) {
            assertTrue(transformer.getSyntheticClasses().containsKey(helper), helper + " was not registered");
        }
    }

    /** A 1.20.1 Fabric mod's food builder call and undead check, in intermediary names. */
    private static byte[] foodAndMobType() {
        String builder = "net/minecraft/class_4174$class_4175";
        String mobType = "net/minecraft/class_1310";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Content", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build",
                "(L" + builder + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "method_19236", "()L" + builder + ";", false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, mobType, "field_6289", "L" + mobType + ";");
        mv.visitInsn(ARETURN);
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
