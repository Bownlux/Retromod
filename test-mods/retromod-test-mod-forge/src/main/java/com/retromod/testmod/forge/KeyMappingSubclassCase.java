/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.LoggerFactory;

/** A mod's own KeyMapping subclass with a String category, as Carry On's keybinds are, which 1.21.9 replaced. Client only. */
final class KeyMappingSubclassCase {

    private static final String CASE = "A KeyMapping subclass built with the old String category registers (key mappings event)";

    private KeyMappingSubclassCase() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(KeyMappingSubclassCase::onRegisterKeys);
        } catch (Throwable t) {
            log("fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        try {
            event.register(new OwnKey("key.retromod_test_mod_forge.own", 74, "key.categories.retromod_test_mod_forge"));
            event.register(new OwnKey("key.retromod_test_mod_forge.own_mouse", InputConstants.Type.MOUSE, 3, "key.categories.misc"));
            log("success");
        } catch (Throwable t) {
            log("fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", CASE, result);
    }

    static final class OwnKey extends KeyMapping {
        OwnKey(String name, int code, String category) {
            super(name, code, category);
        }

        OwnKey(String name, InputConstants.Type type, int code, String category) {
            super(name, type, code, category);
        }
    }
}
