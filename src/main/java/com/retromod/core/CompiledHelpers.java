/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ShadedLibraryNames;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Picks the compiled Retromod helpers a translated mod carries, so it still runs without Retromod. */
final class CompiledHelpers {

    // Helpers written to run inside a mod that live outside an embedded package.
    private static final Set<String> HELPERS_OUTSIDE_EMBEDDED = Set.of(
            "com/retromod/compat/VersionSpoofer",
            "com/retromod/polyfill/minecraft/DataResultPolyfill",
            "com/retromod/polyfill/registry/DirectionPropertyLookup",
            "com/retromod/polyfill/registry/NbtCompatLookup",
            "com/retromod/polyfill/registry/RegistryRefLookup",
            "com/retromod/util/HostLibraryNames",
            // Only its class and method lookups run in a copy. Its loader flags are set for Retromod's
            // own translation and stay off in a mod's copy, so a helper must not read them there.
            "com/retromod/util/McReflect");

    // Helpers the running Retromod calls or fills in, so every mod has to share its one copy.
    private static final Set<String> RUNTIME_ONLY = Set.of(
            // Retromod's NeoForge constructor subscribes it to payload registration.
            "com/retromod/shim/forge/embedded/NetworkShim",
            // The loader API runtime installs its channel delegate here and calls these.
            "com/retromod/shim/api/fabric/embedded/RawChannelPayloads",
            "com/retromod/polyfill/fabric/embedded/FabricNetworkingBridge",
            "com/retromod/shim/forge/embedded/LegacyDataPackSource",
            // Retromod's client entry point bridges these callbacks to the new HUD API.
            "com/retromod/shim/fabric/embedded/HudRenderCallbackShim");

    // Stateless helpers that may exist both shared and per mod, so a runtime-only class naming one does not hold it back.
    static final Set<String> DUPLICABLE = Set.of(
            "com/retromod/shim/common/embedded/LegacyLog",
            "com/retromod/shim/common/embedded/LegacyPublicMethods",
            "com/retromod/shim/common/embedded/LegacyEnchantmentRegistry",
            "com/retromod/shim/fabric/embedded/IdentifierShim",
            "com/retromod/util/HostLibraryNames",
            "com/retromod/util/McReflect");

    private static final Map<String, Optional<byte[]>> CLASS_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, Reach> REACH = new ConcurrentHashMap<>();

    private CompiledHelpers() {}

    /** The helpers a class reaches that can be copied, and the Retromod classes it reaches that cannot. */
    private record Reach(Set<String> helpers, Set<String> blocked) {}

    /** The registered synthetics plus every compiled helper the mod or its synthetics call that can travel. */
    static Map<String, byte[]> withHelpers(Map<String, byte[]> synthetics, Set<String> references) {
        Map<String, byte[]> helpers = new HashMap<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>(references);
        while (!work.isEmpty()) {
            String name = work.poll();
            if (!seen.add(name)) continue;
            // A synthetic the mod uses travels with it, so the helpers it calls are needed too.
            byte[] synthetic = synthetics.get(name);
            if (synthetic != null) {
                work.addAll(SyntheticEmbedder.referencedClasses(synthetic));
                continue;
            }
            if (!isHelperName(name)) continue;
            Reach reach = reach(name);
            // A class it reaches that cannot be copied is fine only when it is a registered synthetic.
            if (!synthetics.keySet().containsAll(reach.blocked())) continue;
            // The helpers it calls go too, since the embedder relocates only classes it holds.
            for (String member : reach.helpers()) {
                if (!synthetics.containsKey(member)) helpers.put(member, classBytes(member));
            }
            work.addAll(reach.blocked());
        }
        if (helpers.isEmpty()) return synthetics;
        Map<String, byte[]> all = new HashMap<>(synthetics);
        all.putAll(helpers);
        return all;
    }

    /** Whether a mod can carry its own copy of this Retromod class and of every Retromod class it calls. */
    static boolean travels(String internalName) {
        return isHelperName(internalName) && reach(internalName).blocked().isEmpty();
    }

    private static Reach reach(String start) {
        return REACH.computeIfAbsent(start, name -> {
            Set<String> helpers = new LinkedHashSet<>();
            Set<String> blocked = new HashSet<>();
            ArrayDeque<String> work = new ArrayDeque<>();
            work.add(name);
            while (!work.isEmpty()) {
                String next = work.poll();
                if (helpers.contains(next) || blocked.contains(next)) continue;
                byte[] bytes = isHelperName(next) && !RuntimeOnlyHolder.CLASSES.contains(next) ? classBytes(next) : null;
                // A class that must stay in Retromod is not followed further.
                if (bytes == null) {
                    blocked.add(next);
                    continue;
                }
                helpers.add(next);
                work.addAll(retromodReferences(next, bytes));
            }
            return new Reach(Set.copyOf(helpers), Set.copyOf(blocked));
        });
    }

    /** Whether the name belongs to one of Retromod's helpers that run inside a mod, judged by name alone. */
    private static boolean isHelperName(String internalName) {
        if (!SyntheticEmbedder.isRetromodClass(internalName) || internalName.startsWith(SyntheticEmbedder.PREFIX)) {
            return false;
        }
        String outer = outerName(internalName);
        return outer.contains("/embedded/") || HELPERS_OUTSIDE_EMBEDDED.contains(outer);
    }

    private static Set<String> retromodReferences(String owner, byte[] bytes) {
        Set<String> references = new HashSet<>();
        for (String dep : SyntheticEmbedder.referencedClasses(bytes)) {
            if (!dep.equals(owner) && SyntheticEmbedder.isRetromodClass(dep)) references.add(dep);
        }
        return references;
    }

    /** The class file as a mod sees it, with Retromod's relocated Gson and SLF4J named as the game's, or null. */
    private static byte[] classBytes(String internalName) {
        return CLASS_BYTES.computeIfAbsent(internalName, name -> {
            try (InputStream in = CompiledHelpers.class.getClassLoader().getResourceAsStream(name + ".class")) {
                return in == null ? Optional.empty()
                        : Optional.of(ShadedLibraryNames.toHostNames(in.readAllBytes()));
            } catch (IOException unreadable) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static String outerName(String internalName) {
        int nested = internalName.indexOf('$', internalName.lastIndexOf('/') + 1);
        return nested < 0 ? internalName : internalName.substring(0, nested);
    }

    // Built on first use: the runtime-only helpers and the helpers they reach, so a type they hand a mod stays shared too.
    private static final class RuntimeOnlyHolder {
        static final Set<String> CLASSES = runtimeOnlyClasses();

        private static Set<String> runtimeOnlyClasses() {
            Set<String> classes = new HashSet<>();
            ArrayDeque<String> work = new ArrayDeque<>(RUNTIME_ONLY);
            while (!work.isEmpty()) {
                String next = work.poll();
                if (DUPLICABLE.contains(outerName(next)) || !isHelperName(next) || !classes.add(next)) continue;
                byte[] bytes = classBytes(next);
                if (bytes != null) work.addAll(retromodReferences(next, bytes));
            }
            return Set.copyOf(classes);
        }
    }
}
