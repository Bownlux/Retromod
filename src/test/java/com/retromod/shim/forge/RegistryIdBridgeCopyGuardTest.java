/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Farmer's Delight copies wheat's properties for its own crops, and 26.3's wheat map color reads an age they lack. */
class RegistryIdBridgeCopyGuardTest {

    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";

    @Test
    @DisplayName("the NeoForge and Forge copy factories run the copy through the map color guard before stamping it")
    void copiesAreGuarded() throws Exception {
        for (byte[] bridgeBytes : List.of(RegistryIdBridgeSynthetic.generate(), RegistryIdBridgeSynthetic.generateForge())) {
            ClassNode bridge = new ClassNode();
            new ClassReader(bridgeBytes).accept(bridge, 0);
            for (String factory : List.of("blockOfFullCopy", "blockOfLegacyCopy")) {
                MethodNode copy = bridge.methods.stream().filter(m -> m.name.equals(factory)).findFirst().orElseThrow();
                assertEquals(List.of(factory.equals("blockOfFullCopy") ? "ofFullCopy" : "ofLegacyCopy", "guardMapColor",
                        "checkcast " + PROPERTIES, "applyBlock"), steps(copy), bridge.name + "." + factory);
                new Analyzer<>(new BasicVerifier()).analyze(bridge.name, copy);
            }
        }
    }

    private static List<String> steps(MethodNode method) {
        List<String> steps = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) steps.add(call.name);
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST) steps.add("checkcast " + type.desc);
        }
        return steps;
    }
}
