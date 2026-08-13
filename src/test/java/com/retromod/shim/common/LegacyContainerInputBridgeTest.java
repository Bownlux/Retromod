/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.mapping.IntermediaryToMojangMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for the container-input half of the 26.1 click API split (#181). */
class LegacyContainerInputBridgeTest {

    private static final String GAME_MODE =
            "net/minecraft/client/multiplayer/MultiPlayerGameMode";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String OLD_INPUT = "net/minecraft/world/inventory/ClickType";
    private static final String NEW_INPUT = "net/minecraft/world/inventory/ContainerInput";

    @AfterEach
    void clearTransformer() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("#181: the official legacy swap call uses ContainerInput on 26.1")
    void redirectsOfficialSwapCall() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.registerLegacyContainerInput(transformer);

        assertCurrentContainerInput(transformer.transformClass(
                swapCaller(GAME_MODE, PLAYER, OLD_INPUT,
                        "handleInventoryMouseClick", "SWAP"),
                "test/LegacySwapCaller"));
    }

    @Test
    @DisplayName("#181: a distributed Fabric swap call reaches the same ContainerInput API")
    void redirectsIntermediarySwapCall() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.registerLegacyContainerInput(transformer);
        IntermediaryToMojangMapper.applyTo(transformer);

        assertCurrentContainerInput(transformer.transformClass(
                swapCaller(
                        "net/minecraft/class_636", "net/minecraft/class_1657",
                        "net/minecraft/class_1713", "method_2906", "field_7791"),
                "test/IntermediarySwapCaller"));
    }

    private static void assertCurrentContainerInput(byte[] transformed) {
        ClassNode classNode = new ClassNode();
        new ClassReader(transformed).accept(classNode, 0);
        MethodNode method = classNode.methods.stream()
                .filter(candidate -> "swap".equals(candidate.name))
                .findFirst().orElseThrow();

        FieldInsnNode field = java.util.Arrays.stream(method.instructions.toArray())
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .findFirst().orElseThrow();
        assertEquals(NEW_INPUT, field.owner);
        assertEquals("SWAP", field.name);
        assertEquals("L" + NEW_INPUT + ";", field.desc);

        MethodInsnNode call = java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst().orElseThrow();
        assertEquals(GAME_MODE, call.owner);
        assertEquals("handleContainerInput", call.name);
        assertEquals("(IIIL" + NEW_INPUT + ";L" + PLAYER + ";)V", call.desc);
    }

    private static byte[] swapCaller(
            String gameMode, String player, String input, String methodName, String fieldName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacySwapCaller",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "swap",
                "(L" + gameMode + ";L" + player + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitFieldInsn(Opcodes.GETSTATIC, input, fieldName, "L" + input + ";");
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, gameMode, methodName,
                "(IIIL" + input + ";L" + player + ";)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
