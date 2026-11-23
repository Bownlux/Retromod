/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Function;

/** Gives 1.20.1 to 1.21.4 mods the int[] BakedQuad getters and constructor on 26.1 and newer hosts, on every loader. */
public final class LegacyQuadGettersBridge {

    static final String QUAD = "net/minecraft/client/resources/model/geometry/BakedQuad";
    static final String OLD_QUAD = "net/minecraft/client/renderer/block/model/BakedQuad";
    // The rebuilt int[] view Forge's quad bridge uses; it names no Forge type, so every loader shares it.
    static final String QUAD_VERTICES = RebuiltApiClasses.ROOT + "forge/client/model/LegacyQuadVertices";
    // 26.1 replaced the int[] with positions and packed UVs, so a mod built for 26.1 or newer never calls these.
    static final String FIRST_QUAD_RECORD_VERSION = "26.1";

    private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
    private static final String SPRITE = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    /** {name, return}: the getters 1.20.1 to 1.21.4 declared, which the record dropped or renamed. */
    static final String[][] GETTERS = {
        {"getVertices", "[I"},
        {"getTintIndex", "I"},
        {"getSprite", SPRITE},
        {"isShade", "Z"},
        {"hasAmbientOcclusion", "Z"},
    };

    private LegacyQuadGettersBridge() {}

    /** Registers the getters where the host quad is the 26.1 record; offline, from a 26.1 target. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!isRecordQuad(host.apply(QUAD))) return;
        RebuiltApiClasses.registerTree(t, QUAD_VERTICES);
        // The quad conversion notes the vertex colors it drops through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        String quad = "L" + QUAD + ";";
        // The class move to geometry/BakedQuad may run before or after these, so both owners are keyed.
        for (String owner : List.of(QUAD, OLD_QUAD)) {
            String head = "([II" + DIRECTION + SPRITE;
            // Vanilla's constructor ends with shade; Forge's second one adds ambient occlusion.
            for (String tail : List.of("Z", "ZZ")) {
                t.registerConstructorRedirect(owner, head + tail + ")V", QUAD_VERTICES, "create", head + tail + ")" + quad);
            }
            for (String[] getter : GETTERS) {
                String desc = "()" + getter[1];
                t.registerMethodRedirect(owner, getter[0], desc, QUAD_VERTICES, getter[0], "(" + quad + ")" + getter[1], true);
                t.limitRedirectToModsBefore(owner, getter[0], desc, FIRST_QUAD_RECORD_VERSION);
            }
            // The record kept the direction under its own name.
            t.registerMethodRedirect(owner, "getDirection", "()" + DIRECTION, QUAD, "direction", "()" + DIRECTION);
            t.limitRedirectToModsBefore(owner, "getDirection", "()" + DIRECTION, FIRST_QUAD_RECORD_VERSION);
        }
    }

    /** Whether the host quad is the 26.1 record: it has materialInfo() and no int[] getter. */
    static boolean isRecordQuad(ClassNode quad) {
        if (quad == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_QUAD_RECORD_VERSION) >= 0;
        boolean material = false;
        for (MethodNode method : quad.methods) {
            if (method.name.equals("getVertices") || method.name.equals("vertices")) return false;
            if (method.name.equals("materialInfo")) material = true;
        }
        return material;
    }
}
