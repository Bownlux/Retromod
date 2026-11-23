/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Old FabricEntityTypeBuilder calls keep their meaning on the vanilla entity type builder. */
class LegacyFabricEntityTypeBuilderBridgeTest {

    @Test
    @DisplayName("dimensions(...) keeps the eye height and hands the whole dimensions to the builder")
    void dimensionsKeepEyeHeight() throws Exception {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyFabricEntityTypeBuilderBridge.generateHelper()).accept(helper, 0);
        for (MethodNode method : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);

        MethodNode dimensions = helper.methods.stream().filter(m -> m.name.equals("dimensions")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        dimensions.instructions.forEach(insn -> { if (insn instanceof MethodInsnNode call) calls.add(call.name); });
        assertTrue(calls.containsAll(List.of("sized", "eyeHeight", "getDeclaredField", "set")), calls.toString());
        assertEquals(1, dimensions.tryCatchBlocks.size(), "a builder without the field keeps the size and eye height");
    }
}
