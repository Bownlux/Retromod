/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural and semantic guard for the exact client interaction replacements used by #180. */
class LegacyClientInteractionSyntheticTest {

    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String GAME_MODE = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
    private static final String RESULT = "net/minecraft/world/InteractionResult";

    @Test
    void generatedClassIsStructurallyValid() {
        byte[] bytes = LegacyClientInteractionSynthetic.generate();
        assertDoesNotThrow(() -> new ClassReader(bytes)
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0));
    }

    @Test
    void messageBooleanPreservesOverlayAndSystemBranches() {
        MethodNode method = method("displayClientMessage");
        assertTrue(calls(method).stream().anyMatch(call -> PLAYER.equals(call.owner)
                && "sendOverlayMessage".equals(call.name)));
        assertTrue(calls(method).stream().anyMatch(call -> PLAYER.equals(call.owner)
                && "sendSystemMessage".equals(call.name)));
    }

    @Test
    void entityFallbackUsesTheCurrentLocationBearingInteraction() {
        MethodNode method = method("interact");
        assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(instruction ->
                instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && "net/minecraft/world/phys/EntityHitResult".equals(type.desc)));
        assertTrue(calls(method).stream().anyMatch(call -> GAME_MODE.equals(call.owner)
                && "interact".equals(call.name)
                && call.desc.contains("Lnet/minecraft/world/phys/EntityHitResult;")));
    }

    @Test
    void creativeCheckUsesPlayerMaterialsAndNeverSurvivalCombatState() {
        MethodNode method = method("hasInfiniteItems");
        assertTrue(calls(method).stream().anyMatch(call -> PLAYER.equals(call.owner)
                && "hasInfiniteMaterials".equals(call.name) && "()Z".equals(call.desc)));
        assertFalse(calls(method).stream().anyMatch(call -> "canHurtPlayer".equals(call.name)),
                "canHurtPlayer is approximately the opposite of the old creative inventory test");
    }

    @Test
    void swingDecisionUsesClientSuccessSourceAndNeverConsumesAction() {
        MethodNode method = method("shouldSwing");
        assertTrue(calls(method).stream().anyMatch(call ->
                (RESULT + "$Success").equals(call.owner)
                        && "swingSource".equals(call.name)));
        assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(instruction ->
                instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && (RESULT + "$SwingSource").equals(field.owner)
                        && "CLIENT".equals(field.name)));
        assertFalse(calls(method).stream().anyMatch(call -> "consumesAction".equals(call.name)),
                "a consumed action does not necessarily request a client swing");
    }

    @Test
    void swingDecisionReadsTheRenamedSourceOn263() {
        ClassNode source262 = new ClassNode();
        source262.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CLIENT", "L" + RESULT + "$SwingSource;", null, null));
        ClassNode source263 = new ClassNode();
        source263.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "PREDICTED", "L" + RESULT + "$SwingSource;", null, null));
        assertEquals("CLIENT", LegacyClientInteractionSynthetic.clientSwingName(source262, "26.3"));
        assertEquals("PREDICTED", LegacyClientInteractionSynthetic.clientSwingName(source263, "26.2"));
        assertEquals("PREDICTED", LegacyClientInteractionSynthetic.clientSwingName(null, "26.3"),
                "offline, a 26.3 target has no CLIENT source to read");
        assertEquals("CLIENT", LegacyClientInteractionSynthetic.clientSwingName(null, "26.2"));
        MethodNode method = method(LegacyClientInteractionSynthetic.generate("PREDICTED"), "shouldSwing");
        assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(instruction ->
                instruction instanceof FieldInsnNode field && "PREDICTED".equals(field.name)));
    }

    private static MethodNode method(String name) {
        return method(LegacyClientInteractionSynthetic.generate("CLIENT"), name);
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(method -> name.equals(method.name))
                .findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        return Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .toList();
    }
}
