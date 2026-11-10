/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.LoggerFactory;

/**
 * An old subscriber class with one static handler, as Farmer's Delight and TerraBlender ship.
 * Forge 26.2 used to lose the handler, so its line was missing from the log. The server starts
 * after the summary, so the result is in the log rather than the summary.
 */
@Mod.EventBusSubscriber(modid = "retromod_test_mod_forge")
public final class OneHandlerSubscriber {

    private OneHandlerSubscriber() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] A @Mod.EventBusSubscriber class "
                + "with one static handler runs it (server started): success");
    }
}
