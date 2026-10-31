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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #372: Fossils and Archaeology 9.3.4.0 (Forge 1.20.1) defines its advancement triggers with the
 * JSON API that 1.20.2 replaced with codecs, and NeoForge 1.21.1 stopped at
 * {@code NoClassDefFoundError: AbstractCriterionTriggerInstance}.
 */
class LegacyCriterionTriggerBridgeTest {

    private static final String CRITERIA = "net/minecraft/advancements/critereon/";
    private static final String CONTEXT = "L" + CRITERIA + "ContextAwarePredicate;";
    private static final String ID = "Lnet/minecraft/resources/ResourceLocation;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old trigger and instance land on the generated bases, and the removed members on adapters")
    void triggerAndInstanceAreBridged() {
        RetromodTransformer transformer = bridged("1.21.1");

        ClassNode trigger = transform(transformer, trigger(), "mod/EggTrigger");
        ClassNode instance = transform(transformer, instance(), "mod/EggTrigger$Instance");

        assertEquals(LegacyCriterionTriggerBridge.TRIGGER, trigger.superName);
        assertEquals(LegacyCriterionTriggerBridge.INSTANCE, instance.superName);
        List<String> calls = calls(instance);
        assertTrue(calls.contains(LegacyCriterionTriggerBridge.INSTANCE + ".<init>"), calls.toString());
        assertTrue(calls.contains(LegacyCriterionTriggerBridge.CALLS + ".fromJson"), calls.toString());
        assertTrue(calls.contains(LegacyCriterionTriggerBridge.CALLS + ".register"), calls.toString());
        assertTrue(fields(instance).contains(LegacyCriterionTriggerBridge.CALLS) || calls.contains(LegacyCriterionTriggerBridge.CALLS + ".any"),
                "ContextAwarePredicate.ANY reads through the adapter: " + calls);
    }

    @Test
    @DisplayName("On 26.2, where the base moved twice, an old trigger still lands on the generated base")
    void triggerIsBridgedAfterTheBaseMoves() {
        RetromodTransformer transformer = bridged("26.2");
        String criterion = "net/minecraft/advancements/criterion/SimpleCriterionTrigger";
        transformer.registerClassRedirect(LegacyCriterionTriggerBridge.SIMPLE_TRIGGER, criterion);
        transformer.registerClassRedirect(criterion, "net/minecraft/advancements/triggers/SimpleCriterionTrigger");

        ClassNode trigger = transform(transformer, transformer.transformClass(trigger(), "mod/EggTrigger"), "mod/EggTrigger");

        assertEquals(LegacyCriterionTriggerBridge.TRIGGER, trigger.superName,
                "without the generated base the trigger has no codec() and every advancement load fails");
        assertTrue(calls(trigger).contains(LegacyCriterionTriggerBridge.TRIGGER + ".<init>"), calls(trigger).toString());
    }

    @Test
    @DisplayName("A 1.20.1 host keeps its own JSON trigger API")
    void olderHostsAreLeftAlone() {
        RetromodTransformer transformer = bridged("1.20.1");
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyCriterionTriggerBridge.TRIGGER));
    }

    @Test
    @DisplayName("The generated stand-ins verify")
    void generatedClassesVerify() {
        for (byte[] generated : List.of(LegacyCriterionTriggerBridge.generateInstance(), LegacyCriterionTriggerBridge.generateTrigger(),
                LegacyCriterionTriggerBridge.generateCalls(), LegacyCriterionTriggerBridge.emptyClass(LegacyCriterionTriggerBridge.READ_CONTEXT))) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    private static RetromodTransformer bridged(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyCriterionTriggerBridge.register(transformer);
        return transformer;
    }

    /** {@code class EggTrigger extends SimpleCriterionTrigger} with the old no-argument constructor. */
    private static byte[] trigger() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/EggTrigger", null, LegacyCriterionTriggerBridge.SIMPLE_TRIGGER, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, LegacyCriterionTriggerBridge.SIMPLE_TRIGGER, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An instance built with ContextAwarePredicate.ANY, a predicate read by fromJson, and a one-argument register. */
    private static byte[] instance() {
        String gson = String.join("/", "com", "google", "gson") + "/";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/EggTrigger$Instance", null, LegacyCriterionTriggerBridge.OLD_INSTANCE, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + ID + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(GETSTATIC, CRITERIA + "ContextAwarePredicate", "ANY", CONTEXT);
        init.visitMethodInsn(INVOKESPECIAL, LegacyCriterionTriggerBridge.OLD_INSTANCE, "<init>", "(" + ID + CONTEXT + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(L" + gson + "JsonObject;)" + "Ljava/lang/Object;", null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitLdcInsn("entity");
        read.visitInsn(ACONST_NULL);
        read.visitMethodInsn(INVOKESTATIC, CRITERIA + "EntityPredicate", "fromJson",
                "(L" + gson + "JsonObject;Ljava/lang/String;L" + CRITERIA + "DeserializationContext;)" + CONTEXT, false);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(Lnet/minecraft/advancements/CriterionTrigger;)Ljava/lang/Object;", null, null);
        register.visitCode();
        register.visitVarInsn(ALOAD, 0);
        register.visitMethodInsn(INVOKESTATIC, "net/minecraft/advancements/CriteriaTriggers", "register",
                "(Lnet/minecraft/advancements/CriterionTrigger;)Lnet/minecraft/advancements/CriterionTrigger;", false);
        register.visitInsn(ARETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode transform(RetromodTransformer transformer, byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, name)).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static List<String> fields(ClassNode node) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner);
            }
        }
        return fields;
    }
}
