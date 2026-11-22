/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** The block setup bridges follow the host: the state predicate setters and MultifaceBlock's own WATERLOGGED. */
class LegacyBlockSetupBridgesTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("emissiveRendering and hasPostProcess are bridged only where the host lost their StatePredicate form")
    void statePredicateSettersFollowTheHost() {
        ClassNode old = new ClassNode();
        old.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "emissiveRendering", LegacyStatePredicateBridge.OLD_DESC, null, null));
        assertFalse(LegacyStatePredicateBridge.lost(old, "emissiveRendering", "26.2"), "26.1 still has it");
        assertTrue(LegacyStatePredicateBridge.lost(new ClassNode(), "emissiveRendering", "26.2"));
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertTrue(LegacyStatePredicateBridge.lost(null, "hasPostProcess", "26.1"));
        assertFalse(LegacyStatePredicateBridge.lost(null, "emissiveRendering", "26.2"));
    }

    @Test
    @DisplayName("Each host embeds only the setter helpers it links: 26.1 the post-process one, 26.2 adds emissive, never the 26.3 view check")
    void setterHelpersStayOnTheHostsTheyLinkOn() {
        com.retromod.core.RetromodTransformer transformer = com.retromod.core.RetromodTransformer.getInstance();
        try {
            RetromodVersion.TARGET_MC_VERSION = "26.1.2";
            transformer.clearRedirectsForTesting();
            LegacyStatePredicateBridge.register(transformer);
            assertEquals(java.util.Set.of(LegacyStatePredicateBridge.POST_PROCESS_HELPER), rebuilt(transformer), "26.1 has no state-only emissiveRendering");
            assertEquals(java.util.List.of(), hostCalls(transformer, LegacyStatePredicateBridge.POST_PROCESS_HELPER));

            RetromodVersion.TARGET_MC_VERSION = "26.2";
            transformer.clearRedirectsForTesting();
            LegacyStatePredicateBridge.register(transformer);
            LegacyViewBlockingBridge.register(transformer);
            assertEquals(java.util.Set.of(LegacyStatePredicateBridge.POST_PROCESS_HELPER, LegacyStatePredicateBridge.EMISSIVE_HELPER),
                    rebuilt(transformer), "Forge 26.2 has no isViewBlocking(StateArgumentPredicate)");
            assertEquals(java.util.List.of("emissiveRendering"), hostCalls(transformer, LegacyStatePredicateBridge.EMISSIVE_HELPER));

            RetromodVersion.TARGET_MC_VERSION = "26.3";
            transformer.clearRedirectsForTesting();
            LegacyViewBlockingBridge.register(transformer);
            assertEquals(java.util.List.of("isViewBlocking"), hostCalls(transformer, LegacyViewBlockingBridge.HELPER));
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    private static java.util.Set<String> rebuilt(com.retromod.core.RetromodTransformer transformer) {
        java.util.Set<String> names = new java.util.TreeSet<>(transformer.getSyntheticClasses().keySet());
        names.removeIf(name -> !name.startsWith(RebuiltApiClasses.ROOT));
        return names;
    }

    /** The block property setters an embedded helper calls on the host. */
    private static java.util.List<String> hostCalls(com.retromod.core.RetromodTransformer transformer, String helper) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(transformer.getSyntheticClasses().get(helper)).accept(node, 0);
        java.util.List<String> calls = new java.util.ArrayList<>();
        for (MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals(LegacyStatePredicateBridge.PROPERTIES)) {
                    calls.add(call.name);
                }
            }
        }
        return calls;
    }

    @Test
    @DisplayName("The tolerant state builder registers only where MultifaceBlock declares WATERLOGGED itself")
    void stateBuilderFollowsTheHost() {
        ClassNode multiface = new ClassNode();
        assertFalse(LegacyStateDefinitionBridge.hostAddsIt(multiface), "1.21.1");
        multiface.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "WATERLOGGED", "Ljava/lang/Object;", null, null));
        assertTrue(LegacyStateDefinitionBridge.hostAddsIt(multiface), "1.21.2 and newer");
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertFalse(LegacyStateDefinitionBridge.hostAddsIt(null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.2";
        assertTrue(LegacyStateDefinitionBridge.hostAddsIt(null));
    }
}
