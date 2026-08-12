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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Transform-level coverage for the linked vanilla calls used by Revamped Phantoms. */
class RevampedCompatibilityBridgeTest {

    private static final String BRIDGE = IsOverloadBridgeSynthetic.INTERNAL;
    private static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String PHANTOM = "net/minecraft/world/entity/monster/Phantom";
    private static final String DAMAGE = "net/minecraft/world/damagesource/DamageSource";
    private static final String CONDITIONS =
            "net/minecraft/world/entity/ai/targeting/TargetingConditions";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String AABB = "net/minecraft/world/phys/AABB";
    private static final String DISPATCHER =
            "net/minecraft/client/renderer/entity/EntityRenderDispatcher";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Revamped's removed calls redirect through the 26.1 common shim")
    void revampedCallsRedirectAtCorrectEpoch() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);

        byte[] transformed = transformer.transformClass(fixture(), "test/RevampedCalls.class");
        ClassNode node = read(transformed);
        MethodNode calls = method(node, "calls");

        Set<String> bridged = new HashSet<>();
        boolean builderBridged = false;
        for (AbstractInsnNode instruction : calls.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(BRIDGE)) {
                assertEquals(INVOKESTATIC, call.getOpcode(), call.name + " must be devirtualized");
                bridged.add(call.name);
            }
            if (call.owner.equals("com/retromod/polyfill/minecraft/RetroEntityTypeBuild")
                    && call.name.equals("buildOfficial")) {
                assertEquals(INVOKESTATIC, call.getOpcode());
                assertEquals("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", call.desc);
                builderBridged = true;
            }
            assertFalse(isRemovedCall(call),
                    () -> "removed call survived transform: " + call.owner + "." + call.name + call.desc);
        }

        assertEquals(Set.of("entityTypeIsTag", "livingEntityHurtClient", "phantomCanAttack",
                "targetingConditionsSelector", "levelGetNearbyEntities", "cameraOrientation"), bridged);
        assertTrue(builderBridged, "EntityType.Builder.build(String) must be covered on 26.1");
        assertTrue(transformer.getSyntheticClasses().containsKey(BRIDGE));
        assertTrue(transformer.getSyntheticClasses().containsKey(
                IsOverloadBridgeSynthetic.PREDICATE_SELECTOR_INTERNAL));
        assertTrue(transformer.getSyntheticClasses().containsKey(
                "com/retromod/polyfill/minecraft/RetroEntityTypeBuild"));
    }

    @Test
    @DisplayName("generated helpers delegate only to methods present on 26.1 and 26.2")
    void generatedHelpersUseModernApiShapes() {
        ClassNode bridge = read(IsOverloadBridgeSynthetic.generate());

        assertCall(method(bridge, "entityTypeIsTag"), INVOKEVIRTUAL, ENTITY_TYPE,
                "builtInRegistryHolder", "()Lnet/minecraft/core/Holder$Reference;");
        assertCall(method(bridge, "entityTypeIsTag"), INVOKEINTERFACE,
                "net/minecraft/core/Holder", "is", "(Lnet/minecraft/tags/TagKey;)Z");

        MethodNode hurt = method(bridge, "livingEntityHurtClient");
        assertCall(hurt, INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "hurtOrSimulate",
                "(L" + DAMAGE + ";F)Z");
        assertTrue(hurt.instructions.iterator().hasNext());

        MethodNode attack = method(bridge, "phantomCanAttack");
        assertTypeInsn(attack, INSTANCEOF, "net/minecraft/server/level/ServerLevel");
        assertCall(attack, INVOKEVIRTUAL, CONDITIONS, "test",
                "(Lnet/minecraft/server/level/ServerLevel;L" + LIVING + ";L" + LIVING + ";)Z");

        MethodNode selector = method(bridge, "targetingConditionsSelector");
        assertTypeInsn(selector, NEW, IsOverloadBridgeSynthetic.PREDICATE_SELECTOR_INTERNAL);
        assertCall(selector, INVOKEVIRTUAL, CONDITIONS, "selector",
                "(L" + CONDITIONS + "$Selector;)L" + CONDITIONS + ";");

        MethodNode nearby = method(bridge, "levelGetNearbyEntities");
        assertTypeInsn(nearby, INSTANCEOF, "net/minecraft/server/level/ServerEntityGetter");
        assertCall(nearby, INVOKEINTERFACE, "net/minecraft/server/level/ServerEntityGetter",
                "getNearbyEntities", "(Ljava/lang/Class;L" + CONDITIONS + ";L" + LIVING
                        + ";L" + AABB + ";)Ljava/util/List;");

        assertCall(method(bridge, "cameraOrientation"), INVOKEVIRTUAL,
                "net/minecraft/client/Camera", "rotation", "()Lorg/joml/Quaternionf;");

        ClassNode adapter = read(IsOverloadBridgeSynthetic.generatePredicateSelector());
        assertTrue(adapter.interfaces.contains(CONDITIONS + "$Selector"));
        MethodNode test = method(adapter, "test");
        assertEquals("(L" + LIVING + ";Lnet/minecraft/server/level/ServerLevel;)Z", test.desc);
        assertCall(test, INVOKEINTERFACE, "java/util/function/Predicate", "test",
                "(Ljava/lang/Object;)Z");
    }

    private static boolean isRemovedCall(MethodInsnNode call) {
        return (call.owner.equals(ENTITY_TYPE) && call.name.equals("is")
                        && call.desc.equals("(Lnet/minecraft/tags/TagKey;)Z"))
                || (call.owner.equals(LIVING) && call.name.equals("hurtClient")
                        && call.desc.equals("(L" + DAMAGE + ";F)Z"))
                || (call.owner.equals(PHANTOM) && call.name.equals("canAttack")
                        && call.desc.equals("(L" + LIVING + ";L" + CONDITIONS + ";)Z"))
                || (call.owner.equals(CONDITIONS) && call.name.equals("selector")
                        && call.desc.startsWith("(Ljava/util/function/Predicate;)"))
                || (call.owner.equals(LEVEL) && call.name.equals("getNearbyEntities"))
                || (call.owner.equals(DISPATCHER) && call.name.equals("cameraOrientation"))
                || (call.owner.equals(ENTITY_TYPE + "$Builder") && call.name.equals("build")
                        && call.desc.startsWith("(Ljava/lang/String;)"));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, ACC_PUBLIC, "test/RevampedCalls", null, "java/lang/Object", null);
        String descriptor = "(L" + ENTITY_TYPE + ";L" + LIVING + ";L" + DAMAGE
                + ";FL" + PHANTOM + ";L" + LIVING + ";L" + CONDITIONS + ";L" + LEVEL
                + ";Ljava/lang/Class;L" + AABB + ";Ljava/util/function/Predicate;L"
                + ENTITY_TYPE + "$Builder;L" + DISPATCHER + ";)V";
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "calls", descriptor, null, null);
        method.visitCode();

        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ACONST_NULL);
        method.visitTypeInsn(CHECKCAST, "net/minecraft/tags/TagKey");
        method.visitMethodInsn(INVOKEVIRTUAL, ENTITY_TYPE, "is",
                "(Lnet/minecraft/tags/TagKey;)Z", false);
        method.visitInsn(POP);

        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ALOAD, 2);
        method.visitVarInsn(FLOAD, 3);
        method.visitMethodInsn(INVOKEVIRTUAL, LIVING, "hurtClient", "(L" + DAMAGE + ";F)Z", false);
        method.visitInsn(POP);

        method.visitVarInsn(ALOAD, 4);
        method.visitVarInsn(ALOAD, 5);
        method.visitVarInsn(ALOAD, 6);
        method.visitMethodInsn(INVOKEVIRTUAL, PHANTOM, "canAttack",
                "(L" + LIVING + ";L" + CONDITIONS + ";)Z", false);
        method.visitInsn(POP);

        method.visitVarInsn(ALOAD, 6);
        method.visitVarInsn(ALOAD, 10);
        method.visitMethodInsn(INVOKEVIRTUAL, CONDITIONS, "selector",
                "(Ljava/util/function/Predicate;)L" + CONDITIONS + ";", false);
        method.visitInsn(POP);

        method.visitVarInsn(ALOAD, 7);
        method.visitVarInsn(ALOAD, 8);
        method.visitVarInsn(ALOAD, 6);
        method.visitVarInsn(ALOAD, 5);
        method.visitVarInsn(ALOAD, 9);
        method.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "getNearbyEntities",
                "(Ljava/lang/Class;L" + CONDITIONS + ";L" + LIVING + ";L" + AABB
                        + ";)Ljava/util/List;", false);
        method.visitInsn(POP);

        method.visitVarInsn(ALOAD, 11);
        method.visitLdcInsn("revamped_phantoms:shockwave");
        method.visitMethodInsn(INVOKEVIRTUAL, ENTITY_TYPE + "$Builder", "build",
                "(Ljava/lang/String;)L" + ENTITY_TYPE + ";", false);
        method.visitInsn(POP);

        method.visitVarInsn(ALOAD, 12);
        method.visitMethodInsn(INVOKEVIRTUAL, DISPATCHER, "cameraOrientation",
                "()Lorg/joml/Quaternionf;", false);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(5, 13);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static void assertCall(MethodNode method, int opcode, String owner, String name, String desc) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
                return;
            }
        }
        fail("missing call " + owner + "." + name + desc + " in " + method.name);
    }

    private static void assertTypeInsn(MethodNode method, int opcode, String type) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode typed && typed.getOpcode() == opcode
                    && typed.desc.equals(type)) {
                return;
            }
        }
        fail("missing type instruction " + opcode + " " + type + " in " + method.name);
    }
}
