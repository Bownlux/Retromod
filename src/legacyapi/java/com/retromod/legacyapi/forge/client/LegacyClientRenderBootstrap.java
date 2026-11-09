/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;

/** Notes, while each old mod constructs on a client, which mod uses this copy of Retromod's rebuilt client rendering. */
public final class LegacyClientRenderBootstrap {

    // This class names no client class, so a dedicated server can load and run it. The rebuilt parts that
    // draw keep their client code in classes reached only after isClient().
    private static final String CLIENT_CLASS = "net.minecraft.client.Minecraft";

    // Each mod that installed this copy, in install order, with its mod-bus group. An addon that reuses
    // its library's copy installs the same copy under its own namespace.
    private static final Map<String, BusGroup> MODS = new LinkedHashMap<>();
    private static volatile Boolean client;

    private LegacyClientRenderBootstrap() {}

    // Called first in each old @Mod constructor of a jar that uses a rebuilt client-render API, and by each part's
    // own install().
    /** Notes the constructing mod and its mod-bus group, once per mod, on a client only. */
    public static void install() {
        if (!isClient()) return;
        String namespace = null;
        try {
            // Construction is the only time FML names an active mod, so later posts use what is noted here.
            ModLoadingContext context = ModLoadingContext.get();
            namespace = context.getActiveNamespace();
            ModContainer container = context.getContainer();
            if (namespace == null || container == null) return;
            synchronized (MODS) {
                if (!MODS.containsKey(namespace)) MODS.put(namespace, container.getModBusGroup());
            }
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("client render install " + LegacyClientRenderBootstrap.class.getName(), "Retromod did not"
                    + " set up the old client rendering of " + (namespace == null ? "a mod" : namespace) + ": " + failure);
        }
    }

    /** Whether this is a client; asked without loading or starting any client class. */
    public static boolean isClient() {
        Boolean known = client;
        if (known == null) {
            // initialize=false: a probe that ran a game class's static initializer this early broke other mods.
            try {
                Class.forName(CLIENT_CLASS, false, LegacyClientRenderBootstrap.class.getClassLoader());
                known = Boolean.TRUE;
            } catch (ClassNotFoundException | LinkageError absent) {
                known = Boolean.FALSE;
            }
            client = known;
        }
        return known;
    }

    /** The namespaces of the mods that installed this copy, in install order. */
    public static List<String> namespaces() {
        synchronized (MODS) {
            return new ArrayList<>(MODS.keySet());
        }
    }

    /** The mod-bus group of a mod that installed this copy, or null when it did not or has none. */
    public static BusGroup modBusGroup(String namespace) {
        synchronized (MODS) {
            return MODS.get(namespace);
        }
    }
}
