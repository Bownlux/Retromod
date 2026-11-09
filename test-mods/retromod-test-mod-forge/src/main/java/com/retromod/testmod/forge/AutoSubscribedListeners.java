/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.retromod.testlib.forge.LibAutoSubscribedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.LoggerFactory;

/**
 * An old subscriber class with a static listener on a library's own event beside one on a host
 * event, as DynamicTrees and tacz ship. Forge 26.2 subscribes only the host one, so Retromod
 * subscribes the class when the mod's constructor finishes. The library event is posted once the
 * server starts, when Forge 1.20.1's game bus delivered it, so the result is in the log.
 */
@Mod.EventBusSubscriber(modid = "retromod_test_mod_forge")
public final class AutoSubscribedListeners {

    private static boolean heard;

    private AutoSubscribedListeners() {}

    @SubscribeEvent
    public static void onLibraryEvent(LibAutoSubscribedEvent event) {
        heard = true;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        String result;
        try {
            MinecraftForge.EVENT_BUS.post(new LibAutoSubscribedEvent());
            result = heard ? "success" : "fail: the listener on the library's event did not run";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] A @Mod.EventBusSubscriber class's "
                + "static listener on a library's own event runs beside a host-event listener (server started): {}", result);
    }
}
