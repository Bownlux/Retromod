/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Answers whether a Forge host has the shape a bridge needs, from the host's own classes when they can be read. */
public final class ForgeHostShape {

    /** The first Forge version checked to lack the old API the Forge 26.x bridges rebuild. */
    public static final String FIRST_CHECKED_VERSION = "26.2";

    // Every Forge host ships this class, so reading it tells a running Forge host from an offline run.
    static final String ANCHOR = "net/minecraftforge/common/MinecraftForge";
    static final String MOD_BUS_EVENT = "net/minecraftforge/fml/event/IModBusEvent";
    // EventBus is a library below FML, so none of its types can lead to FML's mod-bus marker.
    private static final String EVENT_BUS_PACKAGE = "net/minecraftforge/eventbus/";

    private ForgeHostShape() {}

    /** Whether a Forge host has every class in {@code has} and none in {@code lacks}; offline, a Forge target from 26.2. */
    public static boolean matches(Collection<String> has, Collection<String> lacks) {
        return matches(has, lacks, FIRST_CHECKED_VERSION, ClassResourceInspector::read);
    }

    /** Whether a Forge host lacks the class; offline, a Forge target from 26.2. */
    public static boolean lacks(String internalName) {
        return lacks(internalName, FIRST_CHECKED_VERSION);
    }

    /** Whether a Forge host lacks the class; offline, a Forge target from the first version checked to lack it. */
    public static boolean lacks(String internalName, String offlineFrom) {
        return matches(List.of(), List.of(internalName), offlineFrom, ClassResourceInspector::read);
    }

    /** Whether a Forge host has the event outside the mod bus; offline, whether the list names it for a Forge target from 26.2. */
    public static boolean isOutsideModBus(String event, Set<String> offline) {
        return isOutsideModBus(event, offline, ClassResourceInspector::read);
    }

    static boolean isOutsideModBus(String event, Set<String> offline, Function<String, ClassNode> host) {
        if (!McReflect.isForgeTarget()) return false;
        // Offline the CLI reads no Forge classes, so a list checked against the Forge jars stands in for the host.
        if (host.apply(ANCHOR) == null) {
            return offline.contains(event)
                    && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_CHECKED_VERSION) >= 0;
        }
        ClassNode node = host.apply(event);
        if (node == null) return false;
        // Walk the event's Forge supertypes; one that cannot be read leaves the answer open, so it is no.
        Deque<String> pending = new ArrayDeque<>(supertypes(node));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String type = pending.pop();
            if (type.equals(MOD_BUS_EVENT)) return false;
            if (!type.startsWith("net/minecraftforge/") || type.startsWith(EVENT_BUS_PACKAGE) || !seen.add(type)) continue;
            ClassNode supertype = host.apply(type);
            if (supertype == null) return false;
            pending.addAll(supertypes(supertype));
        }
        return true;
    }

    private static List<String> supertypes(ClassNode node) {
        List<String> types = new ArrayList<>(node.interfaces == null ? List.of() : node.interfaces);
        if (node.superName != null) types.add(node.superName);
        return types;
    }

    static boolean matches(Collection<String> has, Collection<String> lacks, String offlineFrom,
            Function<String, ClassNode> host) {
        // NeoForge, Fabric and Quilt never take a Forge bridge.
        if (!McReflect.isForgeTarget()) return false;
        // Offline the CLI reads only the vanilla jar, so the target version stands in for the host.
        if (host.apply(ANCHOR) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, offlineFrom) >= 0;
        }
        for (String name : has) {
            if (host.apply(name) == null) return false;
        }
        for (String name : lacks) {
            if (host.apply(name) != null) return false;
        }
        return true;
    }
}
