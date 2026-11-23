/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.LegacyItemRendererBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

/** Fabric API's removed builtin item renderer registry is rebuilt in a mod only where the host's Fabric API lacks it. */
class LegacyBuiltinItemRendererBridgeTest {

    private static final String ROOT = LegacyBuiltinItemRendererBridge.ROOT;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the registry, its renderer types and the old item renderer base move to the rebuilt classes")
    void registryMovesToTheRebuiltOne() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyBuiltinItemRendererBridge.register(t, name -> null);

        String old = LegacyBuiltinItemRendererBridge.OLD_REGISTRY;
        assertEquals(LegacyBuiltinItemRendererBridge.REGISTRY, t.getClassRedirects().get(old));
        assertEquals(LegacyBuiltinItemRendererBridge.REGISTRY + "$DynamicItemRenderer", t.getClassRedirects().get(old + "$DynamicItemRenderer"));
        assertEquals(ROOT + "LegacyBuiltinItemRenderer",
                t.getClassRedirects().get(LegacyBuiltinItemRendererBridge.OLD_API + "BuiltinItemRenderer"));
        assertEquals(LegacyItemRendererBridge.RENDERER, t.getClassRedirects().get(LegacyItemRendererBridge.OLD_RENDERER),
                "a BlockEntityWithoutLevelRenderer subclass extends the rebuilt base the registry draws through");
        // The registry's implementation and the item model it wraps baked models in travel with the mod.
        for (String rebuilt : new String[]{LegacyBuiltinItemRendererBridge.REGISTRY, ROOT + "LegacyBuiltinItemRenderers",
                LegacyItemRendererBridge.MODEL, LegacyItemRendererBridge.CALLS}) {
            assertTrue(t.getSyntheticClasses().containsKey(rebuilt), rebuilt);
        }
    }

    @Test
    @DisplayName("a host whose Fabric API still has the registry keeps it")
    void hostWithTheRegistryKeepsIt() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ClassNode registry = new ClassNode();
        registry.name = LegacyBuiltinItemRendererBridge.OLD_REGISTRY;
        LegacyBuiltinItemRendererBridge.register(t, name -> name.equals(LegacyBuiltinItemRendererBridge.OLD_REGISTRY) ? registry : null);

        assertNull(t.getClassRedirects().get(LegacyBuiltinItemRendererBridge.OLD_REGISTRY));
    }
}
