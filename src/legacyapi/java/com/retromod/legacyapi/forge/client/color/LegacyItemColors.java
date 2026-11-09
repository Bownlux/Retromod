/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.color;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.legacyapi.minecraft.client.color.LegacyItemColorTable;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Sets up Forge 1.20.1's item colors for each old mod that uses them, on a client only; safe to call on a server. */
public final class LegacyItemColors {

    // Each translated jar carries its own relocated classes, so the slots hold only JDK and host types.
    /** The slots' layout; a copy from a Retromod build with another layout keeps slots of its own. */
    public static final int PROTOCOL = 1;
    // A request is its name, then its arguments: post(String namespace, BlockColors colors), owners() and protocol().
    /** {@code List<Function<Object[], Object>>}: one endpoint per copy, in install order. */
    public static final String ENDPOINTS = "forge.itemColors.endpoints";
    /** {@code AtomicBoolean}: whether a copy added the one listener that posts every copy's item color event. */
    public static final String LISTENER = "forge.itemColors.listener";
    /** The path of the item tint type each mod's namespace gets; Retromod's item definitions name it. */
    public static final String TINT_TYPE = LegacyItemColorTable.TINT_TYPE;

    private static boolean attached;

    private LegacyItemColors() {}

    /** Called first in each old {@code @Mod} constructor of a jar that uses item colors. */
    public static void install() {
        LegacyClientRenderBootstrap.install();
        if (!LegacyClientRenderBootstrap.isClient()) return;
        boolean first;
        synchronized (LegacyItemColors.class) {
            first = !attached;
            attached = true;
        }
        try {
            // Client classes are reached only from here, so a dedicated server never loads them.
            if (first) LegacyItemColorPost.attach();
            // Each mod that installs this copy gets its own tint type, before the first resource reload reads them.
            LegacyItemColorPost.addTintTypes();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old item colors of " + LegacyClientRenderBootstrap.namespaces()
                    + ": " + failure);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Function<Object[], Object>> endpoints() {
        return LegacySharedSlots.get(ENDPOINTS, PROTOCOL, List.class, CopyOnWriteArrayList::new);
    }
}
