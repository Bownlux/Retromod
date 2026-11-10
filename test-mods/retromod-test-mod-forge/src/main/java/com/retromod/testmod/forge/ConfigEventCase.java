/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A listener on the ModConfigEvent base reads the config it was posted for, as Lootr's config manager does. */
final class ConfigEventCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String CASE = "[Retromod-Test-Forge] A listener on ModConfigEvent reads getConfig()"
            + " through the base type (config event): {}";
    private static boolean logged;

    private ConfigEventCase() {}

    static void register(IEventBus modBus) {
        modBus.addListener(ConfigEventCase::onConfig);
    }

    // Forge 26.2 made ModConfigEvent an interface, so this call only links in the interface form.
    private static void onConfig(ModConfigEvent event) {
        String result;
        try {
            ModConfig config = event.getConfig();
            if (!"retromod_test_mod_forge".equals(config.getModId())) return;
            result = "success";
        } catch (Throwable t) {
            result = "fail: " + t;
        }
        if (logged) return;
        logged = true;
        LOG.info(CASE, result);
    }
}
