/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

/** Gives Forge mods on NeoForge the {@code IExtensionPoint.DisplayTest} NeoForge removed, as a rebuilt class that registers nothing. */
final class LegacyDisplayTestBridge {

    static final String FORGE_POINT = "net/minecraftforge/fml/IExtensionPoint";
    static final String FORGE_TEST = FORGE_POINT + "$DisplayTest";
    static final String NEO_POINT = "net/neoforged/fml/IExtensionPoint";
    static final String STAND_IN = RebuiltApiClasses.ROOT + "forge/fml/LegacyDisplayTest";
    private static final String[] OWNERS = {"net/minecraftforge/fml/ModLoadingContext",
            "net/neoforged/fml/ModLoadingContext", "net/minecraftforge/fml/ModContainer", "net/neoforged/fml/ModContainer",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext", ForgeNeoForgeSynthetics.FMLJMLC};

    private LegacyDisplayTestBridge() {}

    /** Registers the bridge. The caller applies it only on a NeoForge host from 1.21, none of which has the class. */
    static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, STAND_IN);
        // A mod still registers its test as an extension point, which NeoForge stores and never reads.
        t.registerClassRedirect(FORGE_TEST, STAND_IN);
        t.registerClassRedirect(FORGE_POINT, NEO_POINT);
        // Forge 1.20.2's registerDisplayTest overloads become static calls that take the receiver first.
        String supplier = "Ljava/util/function/Supplier;";
        String test = "Ljava/util/function/BiPredicate;";
        for (String owner : OWNERS) {
            for (String type : new String[] {FORGE_TEST, STAND_IN}) {
                register(t, owner, "(L" + type + ";)V", "(Ljava/lang/Object;L" + STAND_IN + ";)V");
            }
            register(t, owner, "(" + supplier + ")V", "(Ljava/lang/Object;" + supplier + ")V");
            register(t, owner, "(Ljava/lang/String;" + test + ")V", "(Ljava/lang/Object;Ljava/lang/String;" + test + ")V");
            register(t, owner, "(" + supplier + test + ")V", "(Ljava/lang/Object;" + supplier + test + ")V");
        }
    }

    private static void register(RetromodTransformer t, String owner, String desc, String helperDesc) {
        t.registerMethodRedirect(owner, "registerDisplayTest", desc, STAND_IN, "registerDisplayTest", helperDesc, true);
    }
}
