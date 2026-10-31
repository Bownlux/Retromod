/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Reads {@code level.isClientSide} and {@code level.random} through their getters on hosts that hid the fields. */
public final class LegacyLevelFieldBridge {

    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String RANDOM = "Lnet/minecraft/util/RandomSource;";

    private LegacyLevelFieldBridge() {}

    /** Registers a read-only accessor for each field this host made private or protected. */
    public static void register(RetromodTransformer t) {
        // Mods read both through Level, ServerLevel, or their own types, so subclass owners count.
        // Offline, isClientSide is always read through its getter, which every version has.
        ClassNode level = ClassResourceInspector.read(LEVEL);
        if (level == null || hidden(level, "isClientSide")) {
            t.registerInheritedFieldAccessorRedirect(LEVEL, "isClientSide", "Z", "isClientSide", "()Z", null, null);
        }
        boolean randomHidden = level == null
                ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0
                : hidden(level, "random");
        if (randomHidden) {
            t.registerInheritedFieldAccessorRedirect(LEVEL, "random", RANDOM, "getRandom", "()" + RANDOM, null, null);
        }
    }

    private static boolean hidden(ClassNode level, String name) {
        for (FieldNode field : level.fields) {
            if (field.name.equals(name)) return (field.access & Opcodes.ACC_PUBLIC) == 0;
        }
        return false;
    }
}
