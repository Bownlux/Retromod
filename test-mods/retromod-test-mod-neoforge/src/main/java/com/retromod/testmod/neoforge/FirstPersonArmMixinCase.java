/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/** A first-person arm redirect from 1.21.9 to 26.2 applies, on 26.3 to the split renderer (#390). Client only. */
public final class FirstPersonArmMixinCase {

    private static volatile boolean drawn;
    private static boolean reported;
    private static boolean reportedDrawn;

    private FirstPersonArmMixinCase() {}

    /** Called by LegacyFirstPersonArmMixin each time the first-person arm draws. */
    public static void noteDrawn() {
        drawn = true;
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(FirstPersonArmMixinCase::onClientTick);
    }

    // The first tick reports whether the mixin applied; the first tick after a hand drew in a world reports that it runs.
    private static void onClientTick(ClientTickEvent.Post event) {
        if (!reported) {
            reported = true;
            log("applies", applied());
        }
        if (drawn && !reportedDrawn) {
            reportedDrawn = true;
            log("runs when the hand draws in a world", "success");
        }
    }

    private static String applied() {
        if (loads(String.join(".", "net", "minecraft", "client", "renderer", "SubmitNodeCollector")) == null) {
            return "success (skipped: the host predates 1.21.9)";
        }
        // Joined at run time, so a remapper cannot point these names at another class.
        for (String name : new String[]{String.join(".", "net", "minecraft", "client", "renderer", "FirstPersonHandsAndItemsRenderer"),
                String.join(".", "net", "minecraft", "client", "renderer", "ItemInHandRenderer")}) {
            Class<?> renderer = loads(name);
            if (renderer == null) continue;
            try {
                renderer.getDeclaredField("retromodTest$armRedirect");
                return "success";
            } catch (NoSuchFieldException e) {
                return "fail: the arm redirect mixin did not apply to " + name;
            }
        }
        return "fail: no first-person renderer class loaded";
    }

    private static Class<?> loads(String name) {
        try {
            return Class.forName(name, false, FirstPersonArmMixinCase.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info(
                "[Retromod-Test-NeoForge] A first-person arm redirect from 1.21.9 to 26.2 {}: {}", what, result);
    }
}
