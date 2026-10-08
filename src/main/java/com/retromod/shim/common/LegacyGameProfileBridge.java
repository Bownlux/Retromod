/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

/**
 * Renames the {@code GameProfile} getters for mods built before authlib 7.
 *
 * <p>Minecraft 1.21.9 moved to authlib 7, where {@code GameProfile} is a record: {@code getName},
 * {@code getId}, and {@code getProperties} became {@code name}, {@code id}, and {@code properties}.
 * Any mod that read a player's name or UUID through the profile failed with
 * {@code NoSuchMethodError} on 1.21.9 and every host after it. Authlib is never obfuscated, so the
 * same rename applies on every loader.
 */
public final class LegacyGameProfileBridge {

    private static final String PROFILE = "com/mojang/authlib/GameProfile";
    private static final String[][] RENAMES = {
        {"getName", "name"}, {"getId", "id"}, {"getProperties", "properties"}
    };

    private LegacyGameProfileBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (!hostProfileIsRecord()) return;
        for (String[] rename : RENAMES) {
            transformer.registerMethodRename(PROFILE, rename[0], rename[1]);
        }
    }

    /** The host's own GameProfile decides; offline, the host version does. */
    static boolean hostProfileIsRecord() {
        ClassNode host = ClassResourceInspector.read(PROFILE);
        if (host != null) {
            boolean hasName = host.methods.stream().anyMatch(m -> m.name.equals("name"));
            boolean hasGetName = host.methods.stream().anyMatch(m -> m.name.equals("getName"));
            return hasName && !hasGetName;
        }
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.9") >= 0;
    }
}
