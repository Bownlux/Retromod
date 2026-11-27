/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.LoggerFactory;

/** #410: an MCreator HUD handler that reads the window and the partial tick from RenderGuiEvent.Pre; client only. */
public final class HudEventMembersCase {

    private static boolean logged;

    private HudEventMembersCase() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(HudEventMembersCase::onHud);
    }

    // MCreator overlays size themselves from the window and NeoForge 1.21 dropped both getters.
    private static void onHud(RenderGuiEvent.Pre event) {
        if (logged) return;
        logged = true;
        String result;
        try {
            int width = event.getWindow().getGuiScaledWidth();
            float partialTick = event.getPartialTick();
            result = width > 0 && partialTick >= 0 && partialTick <= 1 ? "success"
                    : "fail: width " + width + ", partial tick " + partialTick;
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] #410 RenderGuiEvent.Pre getWindow() and the"
                + " float getPartialTick() (first HUD frame): {}", result);
    }
}
