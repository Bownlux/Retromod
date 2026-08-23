/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression for Liberty's Villagers reading stale GameRules constant names on 26.1.2. */
class GameRulesFieldRenameTest {

    private static final String INTERMEDIARY_OWNER = "net/minecraft/class_1928";
    private static final String INTERMEDIARY_RULE_DESC = "Lnet/minecraft/class_12279;";
    private static final String TARGET_OWNER = "net/minecraft/world/level/gamerules/GameRules";
    private static final String TARGET_RULE_DESC =
            "Lnet/minecraft/world/level/gamerules/GameRule;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void clearRedirects() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("#234: intermediary GameRules fields use their 26.1 names")
    void intermediaryGameRulesFieldsUseCurrentNames() {
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        IntermediaryToMojangMapper.applyTo(transformer);

        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(fixture(), "probe/LegacyGameRules"))
                .accept(output, 0);

        List<FieldInsnNode> fields = output.methods.stream()
                .filter(method -> method.name.equals("read"))
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .toList();

        assertEquals(List.of(
                "MOB_GRIEFING",
                "KEEP_INVENTORY",
                "SPAWN_MOBS",
                "SHOW_ADVANCEMENT_MESSAGES"),
                fields.stream().map(field -> field.name).toList());
        fields.forEach(field -> {
            assertEquals(TARGET_OWNER, field.owner);
            assertEquals(TARGET_RULE_DESC, field.desc);
        });
    }

    @Test
    @DisplayName("rules with changed boolean meaning are refused without value adapters")
    void semanticRuleChangesAreNotRenamed() {
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        for (String field : new String[]{
                "RULE_DOFIRETICK",
                "RULE_DISABLE_ELYTRA_MOVEMENT_CHECK",
                "RULE_DISABLE_PLAYER_MOVEMENT_CHECK",
                "RULE_DISABLE_RAIDS"}) {
            assertFalse(transformer.getFieldRedirects().containsKey(
                    new RetromodTransformer.FieldKey(TARGET_OWNER, field)), field);
        }
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/LegacyGameRules", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()V", null, null);
        method.visitCode();
        for (String field : new String[]{
                "field_19388", "field_19389", "field_19390", "field_19409"}) {
            method.visitFieldInsn(
                    Opcodes.GETSTATIC, INTERMEDIARY_OWNER, field, INTERMEDIARY_RULE_DESC);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
