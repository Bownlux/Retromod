/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** FireBlock.setFlammable, private on 26.x, is bridged only where the host hides it. */
class LegacyFlammablesBridgeTest {

    @Test
    @DisplayName("A private setFlammable is bridged; a public one, as Fabric 1.21.1 widened it, is left alone")
    void followsTheHost() {
        assertTrue(LegacyFlammablesBridge.hostHidesIt(fire(Opcodes.ACC_PRIVATE)));
        assertFalse(LegacyFlammablesBridge.hostHidesIt(fire(Opcodes.ACC_PUBLIC)));
        assertFalse(LegacyFlammablesBridge.hostHidesIt(new ClassNode()), "a host with no such method is not ours to bridge");
    }

    private static ClassNode fire(int access) {
        ClassNode fire = new ClassNode();
        fire.methods.add(new MethodNode(access, "setFlammable", LegacyFlammablesBridge.DESC, null, null));
        return fire;
    }
}
