/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.LoggerFactory;

/** Jade's overlay reads {@code Minecraft.getDeltaFrameTime()}, which 1.21 removed, on the render thread. Client only. */
final class FrameDeltaCase {

    private FrameDeltaCase() {}

    static void register(IEventBus modBus) {
        modBus.addListener(FrameDeltaCase::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        String result;
        try {
            float delta = Minecraft.getInstance().getDeltaFrameTime();
            result = delta >= 0 && !Float.isNaN(delta) ? "success" : "fail: read " + delta;
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LoggerFactory.getLogger("Retromod-Test-Forge").info(
                "[Retromod-Test-Forge] Minecraft.getDeltaFrameTime() reads the frame's tick delta (client setup): {}", result);
    }
}
