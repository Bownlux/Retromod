/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two remap gaps found on 26.x Fabric. A field-to-method redirect written for an older field type
 * fired on a newer mod that reads the same field with its current type (Bountiful Fares' read of
 * {@code MobEffects.REGENERATION} as a {@code Holder}). And record component fields, which
 * intermediary names {@code comp_N}, kept that name in a mixin {@code @Shadow} without a refmap
 * (Minepathy's {@code Consumable} mixin).
 */
class FieldTypeAndRecordComponentRemapTest {

    private static final String EFFECTS = "net/minecraft/world/effect/MobEffects";
    private static final String MOB_EFFECT = "Lnet/minecraft/world/effect/MobEffect;";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String LOOKUP = "test/retromod/EffectLookup";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a pre-1.20.5 MobEffect read still goes through the lookup")
    void oldFieldTypeIsRedirected() {
        registerEffectLookup();
        MethodNode read = transformedRead(MOB_EFFECT);

        assertNotNull(firstCall(read, "REGENERATION"),
                "the mod reads the field with the type the redirect was written for");
    }

    @Test
    @DisplayName("a Holder read of the same field stays a field read")
    void newerFieldTypeIsLeftAlone() {
        registerEffectLookup();
        MethodNode read = transformedRead(HOLDER);

        assertNull(firstCall(read, "REGENERATION"),
                "the lookup returns a MobEffect, and the cast to Holder would throw");
        FieldInsnNode field = firstField(read);
        assertNotNull(field);
        assertEquals(HOLDER, field.desc);
    }

    @Test
    @DisplayName("a 1.21.4 Holder read of DIG_SPEED becomes HASTE while the MobEffect read keeps its lookup")
    void holderReadFollowsTheRename() {
        registerEffectLookup("DIG_SPEED");
        com.retromod.shim.common.LegacyMobEffectNames.register(transformer);

        MethodNode holderRead = transformedRead("DIG_SPEED", HOLDER);
        FieldInsnNode field = firstField(holderRead);
        assertNotNull(field, "the Holder read stays a field read");
        assertEquals("HASTE", field.name, "1.21.5 renamed DIG_SPEED to HASTE");

        MethodNode effectRead = transformedRead("DIG_SPEED", MOB_EFFECT);
        assertNotNull(firstCall(effectRead, "DIG_SPEED"),
                "a pre-1.20.5 MobEffect read still resolves through the lookup");
    }

    @Test
    @DisplayName("a shadowed record component field gets its Mojang name")
    void recordComponentShadowIsRenamed() {
        transformer.registerIntermediaryNameMappings(Map.of(),
                Map.of("comp_3089", "onConsumeEffects"));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/mod/ConsumableMixin", null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "comp_3089", "Ljava/util/List;", null, null)
                .visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/ConsumableMixin"))
                .accept(out, 0);

        assertEquals(1, out.fields.size());
        FieldNode shadow = out.fields.get(0);
        assertEquals("onConsumeEffects", shadow.name,
                "Mixin looks the shadow up by this name in the Mojang-named target");
    }

    @Test
    @DisplayName("a record component shadow in a mixin with two targets gets its Mojang name")
    void recordComponentShadowInMultiTargetMixin() {
        transformer.registerIntermediaryNameMappings(Map.of(),
                Map.of("comp_3089", "onConsumeEffects"));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/mod/ConsumableMixin", null,
                "java/lang/Object", null);
        org.objectweb.asm.AnnotationVisitor mixin =
                cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/class_10124"));
        targets.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/class_10192"));
        targets.visitEnd();
        mixin.visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "comp_3089", "Ljava/util/List;", null, null)
                .visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
        cw.visitEnd();

        byte[] out = new com.retromod.mixin.MixinCompatibilityTransformer(transformer)
                .transformMixinClass(cw.toByteArray());
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);

        assertEquals("onConsumeEffects", node.fields.get(0).name,
                "with two targets the shadow has no single owner, so the short name decides");
    }

    private void registerEffectLookup() {
        registerEffectLookup("REGENERATION");
    }

    private void registerEffectLookup(String name) {
        transformer.registerFieldRedirect(EFFECTS, name, MOB_EFFECT,
                LOOKUP, name, "()Ljava/lang/Object;");
    }

    private MethodNode transformedRead(String fieldDesc) {
        return transformedRead("REGENERATION", fieldDesc);
    }

    private MethodNode transformedRead(String fieldName, String fieldDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/mod/Effects", null, "java/lang/Object",
                null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "read", "()Ljava/lang/Object;",
                null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, EFFECTS, fieldName, fieldDesc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Effects"))
                .accept(out, 0);
        return out.methods.stream().filter(m -> m.name.equals("read")).findFirst().orElseThrow();
    }

    private static MethodInsnNode firstCall(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return call;
        }
        return null;
    }

    private static FieldInsnNode firstField(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) return field;
        }
        return null;
    }
}
