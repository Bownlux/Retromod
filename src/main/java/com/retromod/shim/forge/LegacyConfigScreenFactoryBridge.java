/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

/** Gives Forge mods on NeoForge their config screen button: Forge's screen factory, filed under NeoForge's key. */
final class LegacyConfigScreenFactoryBridge {

    static final String FORGE_FACTORY = "net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory";
    static final String OLD_FORGE_FACTORY = "net/minecraftforge/client/ConfigGuiHandler$ConfigGuiFactory";
    static final String STAND_IN = RebuiltApiClasses.ROOT + "forge/client/gui/LegacyConfigScreenFactory";
    static final String EXTENSION_POINTS = RebuiltApiClasses.ROOT + "forge/fml/LegacyExtensionPoints";
    static final String REGISTER_DESC = "(Ljava/lang/Class;Ljava/util/function/Supplier;)V";

    private LegacyConfigScreenFactoryBridge() {}

    /** Registers the bridge. The caller applies it only on a NeoForge host from 1.21, which has IConfigScreenFactory. */
    static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, STAND_IN);
        RebuiltApiClasses.registerTree(t, EXTENSION_POINTS);
        t.registerClassRedirect(FORGE_FACTORY, STAND_IN);
        // Forge 1.18 called the same factory record ConfigGuiHandler.ConfigGuiFactory.
        t.registerClassRedirect(OLD_FORGE_FACTORY, STAND_IN);
        // The registration names the factory's class, so the key is swapped for NeoForge's on the way through.
        for (String owner : LegacyDisplayTestBridge.OWNERS) {
            t.registerMethodRedirect(owner, "registerExtensionPoint", REGISTER_DESC, EXTENSION_POINTS, "registerExtensionPoint",
                    "(Ljava/lang/Object;" + REGISTER_DESC.substring(1), true);
        }
    }
}
