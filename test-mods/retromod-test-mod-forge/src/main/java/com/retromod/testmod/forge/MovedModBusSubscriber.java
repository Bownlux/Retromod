/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An old mod-bus subscriber class with a key-mapping listener beside a client-setup one, as Lootr and Patchouli
 * ship. Forge 26.2 moved the key-mapping event off the mod bus, so FML refused the whole class and stopped the
 * mod. Retromod subscribes it from the mod's constructor instead. Both events fire before a world opens, so the
 * results are in the log once the server starts.
 */
@Mod.EventBusSubscriber(modid = "retromod_test_mod_forge", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MovedModBusSubscriber {

    private static volatile boolean keyMappingsRan;
    private static volatile boolean clientSetupRan;

    private MovedModBusSubscriber() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        keyMappingsRan = event != null;
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        clientSetupRan = event != null;
    }

    /** Logs whether each listener ran; only a client loads this class. */
    static void report() {
        Logger log = LoggerFactory.getLogger("Retromod-Test-Forge");
        log.info("[Retromod-Test-Forge] A mod-bus subscriber class's RegisterKeyMappingsEvent listener runs "
                + "(server started): {}", keyMappingsRan ? "success" : "fail: the listener did not run");
        log.info("[Retromod-Test-Forge] The FMLClientSetupEvent listener beside it runs (server started): {}",
                clientSetupRan ? "success" : "fail: the listener did not run");
    }
}
