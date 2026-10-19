/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Common entry point for the 1.20.5 to 1.21.11 cases, so they run on a dedicated server too. */
public class RetromodTestMod121 implements ModInitializer {

    public static final String MOD_ID = "retromod_test_mod_121";

    @Override
    public void onInitialize() {
        TestRunner.runInit();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> TestRunner.runServerStarted());
    }
}
