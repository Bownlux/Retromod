/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

/** Keeps the block property {@code isViewBlocking(StatePredicate)}, which 26.3 replaced with a predicate that also gets a box. */
public final class LegacyViewBlockingBridge {

    static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String OLD_DESC = "(Lnet/minecraft/world/level/block/state/BlockBehaviour$StatePredicate;)L" + PROPERTIES + ";";
    // Each setter's helper links only on the host that changed it, so an older host never embeds the newer calls.
    public static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/block/state/LegacyBlockProperties263";

    private LegacyViewBlockingBridge() {}

    /** Registers the bridge on a host whose block properties lost the old form. */
    public static void register(RetromodTransformer transformer) {
        if (hostLostOldForm()) registerRedirects(transformer);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        RebuiltApiClasses.registerTree(transformer, HELPER);
        // The helper wraps the old predicate in the new one, so the receiver becomes its first argument.
        transformer.registerMethodRedirect(PROPERTIES, "isViewBlocking", OLD_DESC,
                HELPER, "isViewBlocking", "(L" + PROPERTIES + ";" + OLD_DESC.substring(1), true);
    }

    static boolean hostLostOldForm() {
        ClassNode properties = ClassResourceInspector.read(PROPERTIES);
        // Offline the host classes are not readable, so the host version decides.
        if (properties == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        return properties.methods.stream().noneMatch(m -> m.name.equals("isViewBlocking") && m.desc.equals(OLD_DESC));
    }
}
