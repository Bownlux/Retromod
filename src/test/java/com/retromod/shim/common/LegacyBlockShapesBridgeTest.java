/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fence gate and skull shape constants a newer host dropped or hid are read from the host's own blocks. */
class LegacyBlockShapesBridgeTest {

    @Test
    @DisplayName("A constant applies where the host lacks it or keeps it private; offline from 1.21.5")
    void followsTheHost() {
        assertTrue(LegacyBlockShapesBridge.hostDropped(owner(), "Z_SHAPE", "26.3"));
        assertTrue(LegacyBlockShapesBridge.hostDropped(owner(field("SHAPE", Opcodes.ACC_PRIVATE)), "SHAPE", "26.3"));
        assertFalse(LegacyBlockShapesBridge.hostDropped(owner(field("SHAPE", Opcodes.ACC_PROTECTED)), "SHAPE", "26.3"));
        assertFalse(LegacyBlockShapesBridge.hostDropped(null, "Z_SHAPE", "1.21.4"));
        assertTrue(LegacyBlockShapesBridge.hostDropped(null, "Z_SHAPE", "1.21.5"));
    }

    @Test
    @DisplayName("Each getter asks the host's block for its shape instead of holding numbers, and the class verifies")
    void gettersAskTheHost() throws Exception {
        ClassNode shapes = new ClassNode();
        new ClassReader(LegacyBlockShapesBridge.generate()).accept(shapes, 0);
        for (MethodNode method : shapes.methods) new Analyzer<>(new BasicVerifier()).analyze(shapes.name, method);
        for (String getter : List.of("Z_SHAPE", "X_OCCLUSION_SHAPE_LOW", "SkullBlock_SHAPE")) {
            assertTrue(shapes.methods.stream().anyMatch(m -> m.name.equals(getter)), getter);
        }
        MethodNode shape = shapes.methods.stream().filter(m -> m.name.equals("shape")).findFirst().orElseThrow();
        List<String> calls = new java.util.ArrayList<>();
        shape.instructions.forEach(insn -> { if (insn instanceof MethodInsnNode call) calls.add(call.name); });
        assertTrue(calls.containsAll(List.of("defaultBlockState", "setValue", "getShape", "getCollisionShape",
                "getBlockSupportShape", "getOcclusionShape")), calls.toString());
        assertTrue(shapes.methods.stream().noneMatch(m -> m.instructions != null && java.util.stream.StreamSupport
                .stream(m.instructions.spliterator(), false).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("box"))),
                "no box numbers are written down");
    }

    private static FieldNode field(String name, int access) {
        return new FieldNode(access | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name, LegacyBlockShapesBridge.SHAPE, null, null);
    }

    private static ClassNode owner(FieldNode... fields) {
        ClassNode node = new ClassNode();
        node.name = "net/minecraft/world/level/block/SomeBlock";
        node.fields.addAll(List.of(fields));
        return node;
    }
}
