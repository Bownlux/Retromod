/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
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
        MinecraftForge.EVENT_BUS.addListener(RenamedMixinTargetCase::onClientTick);
    }

    // The first client tick comes after the constructor sized the window.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || reported) return;
        reported = true;
        // NeoForge reads the manifest's config through 1.21.8, and Retromod lists it in neoforge.mods.toml from 1.21.9.
        // Forge 1.20.1 to 1.20.4 and NeoForge 1.20.1 run SRG member names and this mixin has no refmap, so it cannot apply there.
        String result = srgHost() ? "skipped: SRG host, the mixin has no refmap"
                : ran ? "success" : "fail: the mixin handler did not run";
        LoggerFactory.getLogger("Retromod-Test-Forge").info(
                "[Retromod-Test-Forge] A mixin on Minecraft.resizeDisplay(), renamed resizeGui() in 26.1, runs (first client tick): {}",
                result);
    }

    // An SRG host has neither the old nor the new Mojang name for the resize method.
    private static boolean srgHost() {
        for (java.lang.reflect.Method method : net.minecraft.client.Minecraft.class.getDeclaredMethods()) {
            String name = method.getName();
            if (name.equals("resizeDisplay") || name.equals("resizeGui")) return false;
        }
        return true;
    }
}
