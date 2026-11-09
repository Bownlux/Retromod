/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/** A mixin on Minecraft.resizeDisplay(), which 26.1 renamed resizeGui(), still runs. Client only. */
public final class RenamedMixinTargetCase {

    private static volatile boolean ran;
    private static boolean reported;

    private RenamedMixinTargetCase() {}

    /** Called by LegacyResizeDisplayMixin, which Minecraft's constructor reaches when it sizes the window. */
    public static void noteRan() {
        ran = true;
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(RenamedMixinTargetCase::onClientTick);
    }

    // The first client tick comes after the constructor sized the window.
    private static void onClientTick(ClientTickEvent.Post event) {
        if (reported) return;
        reported = true;
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info(
                "[Retromod-Test-NeoForge] A mixin on Minecraft.resizeDisplay(), renamed resizeGui() in 26.1, runs (first client tick): {}",
                ran ? "success" : "fail: the mixin handler did not run");
    }
}
