/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyReplacedBlockBridge.Replacement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Old dirt path and roots blocks build on the classes that replaced them, behaving as before. */
class LegacyReplacedBlockBridgeTest {

    private static final List<Replacement> ALL = List.of(LegacyReplacedBlockBridge.DIRT_PATH, LegacyReplacedBlockBridge.NETHER_ROOTS);
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("Each generated base extends the replacement, keeps the old constructor, passes the old behavior, and verifies")
    void generatedBases() throws Exception {
        for (Replacement r : ALL) {
            ClassNode base = new ClassNode();
            new ClassReader(LegacyReplacedBlockBridge.generate(r)).accept(base, 0);
            assertEquals(r.replacement(), base.superName);
            MethodNode init = base.methods.stream()
                    .filter(m -> m.name.equals("<init>") && m.desc.equals(LegacyReplacedBlockBridge.OLD_CTOR)).findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(init.instructions.spliterator(), false)
                    .anyMatch(i -> i instanceof FieldInsnNode f && f.owner.equals(r.argOwner()) && f.name.equals(r.argName())),
                    r.base() + " passes " + r.argName());
            for (MethodNode method : base.methods) new Analyzer<>(new BasicVerifier()).analyze(base.name, method);
        }
    }

    @Test
    @DisplayName("It registers where the host has the replacement and not the old class; offline the target decides")
    void followsTheHost() {
        Replacement path = LegacyReplacedBlockBridge.DIRT_PATH;
        Replacement roots = LegacyReplacedBlockBridge.NETHER_ROOTS;
        assertTrue(LegacyReplacedBlockBridge.hostReplacedIt(path, true, false));
        assertFalse(LegacyReplacedBlockBridge.hostReplacedIt(path, false, true));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(LegacyReplacedBlockBridge.hostReplacedIt(path, false, false));
        assertTrue(LegacyReplacedBlockBridge.hostReplacedIt(roots, false, false));
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(LegacyReplacedBlockBridge.hostReplacedIt(roots, false, false));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyReplacedBlockBridge.hostReplacedIt(path, false, false));
        assertTrue(LegacyReplacedBlockBridge.hostReplacedIt(roots, false, false));
    }
}
