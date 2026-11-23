/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static org.junit.jupiter.api.Assertions.*;

/** A v2 loot callback runs as a v3 one, without the registries v3 added. */
class FabricLootV2EventsBridgeTest {

    @Test
    @DisplayName("The renamed pool builder methods are redirected on the injected pool builder as well as on the interface")
    void poolBuilderRenamesOnBothOwners() {
        com.retromod.core.RetromodTransformer t = com.retromod.core.RetromodTransformer.getInstance();
        try {
            FabricLootV2EventsBridge.registerRedirects(t);
            String builder = "net/minecraft/world/level/storage/loot/LootPool$Builder";
            String condition = "(Lnet/minecraft/world/level/storage/loot/predicates/LootItemCondition;)L" + builder + ";";
            for (String owner : new String[]{builder, "net/fabricmc/fabric/api/loot/v3/FabricLootPoolBuilder"}) {
                assertTrue(t.getMethodRedirects().keySet().stream().anyMatch(k -> k.owner().equals(owner)
                        && k.name().equals("conditionally") && k.desc().equals(condition)), owner);
            }
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("Each adapter implements the v3 callback with the registries last and verifies")
    void adaptersImplementV3() throws Exception {
        for (FabricLootV2EventsBridge.Callback callback : FabricLootV2EventsBridge.CALLBACKS) {
            assertTrue(callback.newDesc().endsWith("Lnet/minecraft/core/HolderLookup$Provider;)" + callback.returnDesc()));
            ClassNode adapter = new ClassNode();
            new ClassReader(FabricLootV2EventsBridge.generateAdapter(callback)).accept(adapter, 0);
            assertEquals(java.util.List.of(callback.newIface()), adapter.interfaces);
            assertTrue(adapter.methods.stream().anyMatch(m -> m.name.equals(callback.sam()) && m.desc.equals(callback.newDesc())));
            for (MethodNode method : adapter.methods) new Analyzer<>(new BasicVerifier()).analyze(adapter.name, method);
        }
    }
}
