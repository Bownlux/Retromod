/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Starts each old mod's rebuilt client rendering at the head of its {@code @Mod} constructors, for jars that use it. */
public final class LegacyClientRenderBootstrapInjector {

    /** The rebuilt class whose {@code install()} notes the constructing mod and its mod-bus group, on a client only. */
    public static final String BOOTSTRAP = RebuiltApiClasses.ROOT + "forge/client/LegacyClientRenderBootstrap";
    static final String HOOK_KEY = "legacy-client-render";
    private static final String INSTALL_DESC = "()V";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    /** One rebuilt client-render feature: the removed classes whose use calls for it, and the rebuilt class whose {@code install()} sets it up. */
    public record Part(String name, Collection<String> oldClasses, String installer) {
        public Part {
            oldClasses = List.copyOf(oldClasses);
            Objects.requireNonNull(installer, "installer");
        }
    }

    // Parts belong to one transformer's registration set and leave with it.
    private static final Map<RetromodTransformer, Map<String, Part>> PARTS = Collections.synchronizedMap(new WeakHashMap<>());

    private LegacyClientRenderBootstrapInjector() {}

    // Each @Mod constructor of a jar naming one of the part's old classes then calls the bootstrap's install() and the
    // part's own right after its super call. Head calls run in no promised order, so a part's install() calls the
    // bootstrap's first itself.
    /** Adds a part, whose installer runs at the head of every {@code @Mod} constructor of a jar that uses it. */
    public static void addPart(RetromodTransformer t, Part part) {
        RebuiltApiClasses.registerTree(t, BOOTSTRAP);
        RebuiltApiClasses.registerTree(t, part.installer());
        // The rebuilt classes note what they could not do through the game log, whichever bridge adds the part.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        Map<String, Part> parts;
        synchronized (PARTS) {
            parts = PARTS.computeIfAbsent(t, any -> Collections.synchronizedMap(new LinkedHashMap<>()));
        }
        boolean first = parts.isEmpty();
        parts.put(part.name(), part);
        if (first) {
            t.onRegistrationReset(() -> PARTS.remove(t));
            ModConstructorHooks.register(t, HOOK_KEY, (classes, modIds) -> plan(List.copyOf(parts.values()), classes));
        }
    }

    /** The install calls for a jar whose original classes name a part's old classes; null when none does. */
    static ModConstructorHooks.JarPlan plan(List<Part> parts, Map<String, byte[]> classes) {
        List<Part> used = new ArrayList<>();
        for (byte[] original : classes.values()) {
            // A class names a removed class through its constant pool, where the name sits as text.
            String text = new String(original, StandardCharsets.ISO_8859_1);
            for (Part part : parts) {
                if (!used.contains(part) && part.oldClasses().stream().anyMatch(text::contains)) used.add(part);
            }
            if (used.size() == parts.size()) break;
        }
        if (used.isEmpty()) return null;
        List<ModConstructorHooks.Call> calls = new ArrayList<>(List.of(install(BOOTSTRAP)));
        for (Part part : parts) {
            ModConstructorHooks.Call call = install(part.installer());
            if (used.contains(part) && !calls.contains(call)) calls.add(call);
        }
        List<ModConstructorHooks.Call> planned = List.copyOf(calls);
        return (modClass, modId) -> planned;
    }

    private static ModConstructorHooks.Call install(String owner) {
        return new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, owner, "install", INSTALL_DESC, List.of());
    }
}
