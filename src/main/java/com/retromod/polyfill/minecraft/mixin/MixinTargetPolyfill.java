/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for removed Minecraft classes that are commonly used as mixin targets.
 *
 * When a removed class survives in a Mixin target or superclass, the Mixin framework fails
 * validation before a handler can run. These placeholders keep the type reference resolvable
 * until the targeted compatibility pass can either rebase a proven-safe shape or skip it.
 *
 * Known removed classes:
 * - class_5500 (SimpleOptionsSubScreen): removed, broke No Chat Reports
 */
public class MixinTargetPolyfill implements PolyfillProvider {

    private static boolean active() {
        return RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION);
    }

    @Override
    public String getName() {
        return "Mixin Target Stubs";
    }

    @Override
    public String getCategory() {
        return "mixin_targets";
    }

    @Override
    public String[] getRemovedClasses() {
        if (!active()) return new String[0];
        return new String[]{
            "net/minecraft/class_5500"   // SimpleOptionsSubScreen
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{
            "com.retromod.polyfill.minecraft.mixin.embedded.ChatOptionsScreenStub"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // class_5500 is a live Minecraft class on intermediary-named hosts. Replacing it there
        // would break the exact legacy hierarchy this provider is meant to preserve.
        if (!active()) return;

        // Redirect the removed SimpleOptionsSubScreen to the legacy placeholder.
        transformer.registerClassRedirect(
            "net/minecraft/class_5500",
            "com/retromod/polyfill/minecraft/mixin/embedded/ChatOptionsScreenStub"
        );

        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }
    }
}
