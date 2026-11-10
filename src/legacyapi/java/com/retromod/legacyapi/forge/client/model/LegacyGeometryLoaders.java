/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.legacyapi.forge.fml.LegacyModNamespaces;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.fml.ModLoadingContext;

/** Forge 1.20.1's geometry loader registration for each old mod that uses it, on Forge 26.2's per-reload event. */
public final class LegacyGeometryLoaders {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    // Each mod that installed this copy, by namespace, and the package of its @Mod class.
    private static final Map<String, String> MOD_PACKAGES = new LinkedHashMap<>();
    private static boolean attached;

    private LegacyGeometryLoaders() {}

    /** Called first in each old {@code @Mod} constructor of a jar that uses old models; safe to call on a server. */
    public static void install() {
        LegacyClientRenderBootstrap.install();
        if (!LegacyClientRenderBootstrap.isClient()) return;
        noteMod(WALKER.getCallerClass());
        synchronized (LegacyGeometryLoaders.class) {
            if (attached) return;
            attached = true;
        }
        try {
            // The listeners name client classes, so a dedicated server never reaches them.
            LegacyGeometryHooks.attach();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old geometry loaders of " + LegacyClientRenderBootstrap.namespaces()
                    + ": " + failure);
        }
    }

    /** Forge 1.20.1's {@code RegisterGeometryLoaders.register(name, loader)}: the loader under the registering mod's namespace. */
    public static void register(ModelEvent.RegisterGeometryLoaders event, String name, LegacyGeometryLoader<?> loader) {
        String namespace = namespaceFor(WALKER.getCallerClass());
        if (namespace == null) {
            LegacyLog.warnOnce("legacy geometry loader " + name, "Retromod could not tell which mod registered the geometry"
                    + " loader " + name + ", so models that name it draw the missing model.");
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath(namespace, name);
        event.register(id, new LegacyGeometryLoaderAdapter(loader, id));
    }

    // Forge 1.20.1 posted the event with the mod whose bus held the listener active. Forge 26.2 posts it at every
    // reload with none, so the listener's mod comes first, as the bus bridge noted it when the mod added the listener.
    // Then the constructing mod, then the mods that installed this copy: the only one, or the one whose package
    // holds the registering class.
    static String namespaceFor(Class<?> caller) {
        String listening = LegacyModNamespaces.current();
        if (listening != null) return listening;
        String active = activeNamespace();
        if (active != null) return active;
        String callerName = caller == null ? "" : caller.getName();
        String best = null;
        int bestLength = -1;
        synchronized (MOD_PACKAGES) {
            if (MOD_PACKAGES.size() == 1) return MOD_PACKAGES.keySet().iterator().next();
            for (Map.Entry<String, String> mod : MOD_PACKAGES.entrySet()) {
                String prefix = mod.getValue().isEmpty() ? "" : mod.getValue() + ".";
                if (callerName.startsWith(prefix) && prefix.length() > bestLength) {
                    best = mod.getKey();
                    bestLength = prefix.length();
                }
            }
        }
        if (best != null) return best;
        List<String> mods = LegacyClientRenderBootstrap.namespaces();
        return mods.isEmpty() ? null : mods.get(0);
    }

    private static void noteMod(Class<?> modClass) {
        String namespace = activeNamespace();
        if (namespace == null || modClass == null) return;
        synchronized (MOD_PACKAGES) {
            MOD_PACKAGES.putIfAbsent(namespace, modClass.getPackageName());
        }
    }

    // FML names a mod only while it constructs, and answers "minecraft" otherwise.
    private static String activeNamespace() {
        try {
            String namespace = ModLoadingContext.get().getActiveNamespace();
            return namespace == null || namespace.equals("minecraft") ? null : namespace;
        } catch (RuntimeException | LinkageError noContext) {
            return null;
        }
    }
}
