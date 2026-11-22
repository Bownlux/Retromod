/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

/** Keeps the block property setters {@code emissiveRendering} and {@code hasPostProcess} that took a {@code StatePredicate}. */
public final class LegacyStatePredicateBridge {

    static final String PROPERTIES = LegacyViewBlockingBridge.PROPERTIES;
    static final String OLD_DESC = LegacyViewBlockingBridge.OLD_DESC;
    static final String STATE_ROOT = RebuiltApiClasses.ROOT + "minecraft/world/level/block/state/";
    /** emissiveRendering calls 26.2's state-only form, so it has a class of its own that 26.1 never embeds. */
    static final String EMISSIVE_HELPER = STATE_ROOT + "LegacyBlockProperties262";
    static final String POST_PROCESS_HELPER = STATE_ROOT + "LegacyBlockProperties";

    private LegacyStatePredicateBridge() {}

    /** Registers each setter the host changed: emissiveRendering from 26.2, hasPostProcess from 26.1. */
    public static void register(RetromodTransformer t) {
        ClassNode properties = ClassResourceInspector.read(PROPERTIES);
        if (lost(properties, "emissiveRendering", "26.2")) redirect(t, "emissiveRendering", EMISSIVE_HELPER);
        if (lost(properties, "hasPostProcess", "26.1")) redirect(t, "hasPostProcess", POST_PROCESS_HELPER);
    }

    private static void redirect(RetromodTransformer t, String name, String helper) {
        RebuiltApiClasses.registerTree(t, helper);
        // The helper takes the receiver first and answers the setter on the host's terms.
        t.registerMethodRedirect(PROPERTIES, name, OLD_DESC, helper, name,
                "(L" + PROPERTIES + ";" + OLD_DESC.substring(1), true);
    }

    /** Whether the host lacks {@code name(StatePredicate)}; offline, whether the target is at least {@code since}. */
    static boolean lost(ClassNode properties, String name, String since) {
        if (properties == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        return properties.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(OLD_DESC));
    }
}
