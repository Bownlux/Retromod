/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Forge's folder pack bridge applies on NeoForge only where the vanilla pack has the static folder helpers it calls. */
class LegacyPathPackResourcesNeoForgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("a host pack with both static helpers takes the bridge; one without them, as NeoForge 1.21.11, does not")
    void hostHelpersDecide() {
        ClassNode withHelpers = new ClassNode();
        withHelpers.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getNamespaces",
                "(Ljava/nio/file/Path;)Ljava/util/Set;", null, null));
        withHelpers.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "listResources",
                "(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V",
                null, null));
        assertTrue(LegacyPathPackResourcesBridge.neoForgeHostHasFolderHelpers(withHelpers));
        assertFalse(LegacyPathPackResourcesBridge.neoForgeHostHasFolderHelpers(new ClassNode()));
    }

    @Test
    @DisplayName("offline, a 26.1 or newer target takes the bridge and 1.21.11 does not")
    void offlineTargetDecides() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyPathPackResourcesBridge.neoForgeHostHasFolderHelpers(null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(LegacyPathPackResourcesBridge.neoForgeHostHasFolderHelpers(null));
    }
}
