/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Sends old model code's reads of block model elements, faces and rotations to the records 26.1 renamed them to. */
public final class LegacyBlockElementBridge {

    static final String CUBOID = "net/minecraft/client/resources/model/cuboid/";
    static final String ELEMENT = CUBOID + "CuboidModelElement";
    static final String FACE = CUBOID + "CuboidFace";
    static final String ROTATION = CUBOID + "CuboidRotation";
    static final String OLD_MODEL = "net/minecraft/client/renderer/block/model/";
    static final String OLD_ELEMENT = OLD_MODEL + "BlockElement";
    static final String OLD_FACE = OLD_MODEL + "BlockElementFace";
    static final String OLD_ROTATION = OLD_MODEL + "BlockElementRotation";
    static final String OLD_FACE_UV = OLD_MODEL + "BlockFaceUV";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/client/renderer/block/model/";
    static final String HELPERS = REBUILT + "LegacyBlockElements";
    static final String FACE_UV = REBUILT + "BlockFaceUV";

    private static final String VECTOR = "Lorg/joml/Vector3f;";
    private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
    private static final String AXIS = "Lnet/minecraft/core/Direction$Axis;";
    private static final String MAP = "Ljava/util/Map;";

    private LegacyBlockElementBridge() {}

    /** Registers the bridge on a host whose block model elements are 26.1's cuboid records. */
    public static void register(RetromodTransformer t) {
        if (!hostHasCuboidElements()) return;
        RebuiltApiClasses.registerTree(t, HELPERS);
        registerClassMoves(t);
        registerElement(t);
        registerRotation(t);
        registerFace(t);
    }

    /** Whether the host has the cuboid element record; offline, a 26.1 or newer target. */
    static boolean hostHasCuboidElements() {
        if (ClassResourceInspector.exists(ELEMENT)) return true;
        // The old class on the classpath means a host before 26.1, which keeps the old members.
        if (ClassResourceInspector.exists(OLD_ELEMENT)) return false;
        return RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION);
    }

    private static void registerClassMoves(RetromodTransformer t) {
        // Also in the 26.1 class move table; repeated here so the members below always meet the new names.
        t.registerClassRedirect(OLD_ELEMENT, ELEMENT);
        t.registerClassRedirect(OLD_FACE, FACE);
        t.registerClassRedirect(OLD_ROTATION, ROTATION);
        // BlockFaceUV went in 1.21.5, when its corners and rotation moved onto the face.
        t.registerClassRedirect(OLD_FACE_UV, FACE_UV);
    }

    /** Before 1.21.5 the element kept public final fields; the record keeps them private behind accessors. */
    private static void registerElement(RetromodTransformer t) {
        String element = "(L" + ELEMENT + ";)";
        // The host keeps unchangeable vectors, and old code reads the old changeable type's fields.
        t.registerExactFieldRedirect(ELEMENT, "from", VECTOR, HELPERS, "from", element + VECTOR);
        t.registerExactFieldRedirect(ELEMENT, "to", VECTOR, HELPERS, "to", element + VECTOR);
        // A face loaded without "uv" reads its corners from the element's bounds, as 1.20.1 filled them in.
        t.registerExactFieldRedirect(ELEMENT, "faces", MAP, HELPERS, "faces", element + MAP);
        // Reads only: a write, such as Supplementaries replacing an awning's rotation, stays as written.
        t.registerFieldAccessorRedirect(ELEMENT, "rotation", "rotation", "()L" + ROTATION + ";", null, null);
        t.registerFieldAccessorRedirect(ELEMENT, "lightEmission", "lightEmission", "()I", null, null);
        // 26.3 replaced the flag with a shading direction, so a helper reads either.
        t.registerExactFieldRedirect(ELEMENT, "shade", "Z", HELPERS, "shade", element + "Z");
        String oldConstructor = "(" + VECTOR + VECTOR + MAP + "L" + ROTATION + ";Z)";
        t.registerConstructorRedirect(ELEMENT, oldConstructor + "V", HELPERS, "element", oldConstructor + "L" + ELEMENT + ";");
    }

    /** The old rotation turned around one axis, and read its origin as a changeable vector. */
    private static void registerRotation(RetromodTransformer t) {
        String rotation = "(L" + ROTATION + ";)";
        t.registerMethodRedirect(ROTATION, "origin", "()" + VECTOR, HELPERS, "origin", rotation + VECTOR);
        t.registerMethodRedirect(ROTATION, "axis", "()" + AXIS, HELPERS, "axis", rotation + AXIS);
        t.registerMethodRedirect(ROTATION, "angle", "()F", HELPERS, "angle", rotation + "F");
        String oldConstructor = "(" + VECTOR + AXIS + "FZ)";
        t.registerConstructorRedirect(ROTATION, oldConstructor + "V", HELPERS, "rotation", oldConstructor + "L" + ROTATION + ";");
    }

    /** 1.20.1's face kept public final fields; 1.21.1's record kept its old UVs. */
    private static void registerFace(RetromodTransformer t) {
        t.registerFieldAccessorRedirect(FACE, "cullForDirection", "cullForDirection", "()" + DIRECTION, null, null);
        t.registerFieldAccessorRedirect(FACE, "tintIndex", "tintIndex", "()I", null, null);
        t.registerFieldAccessorRedirect(FACE, "texture", "texture", "()Ljava/lang/String;", null, null);
        String uv = "L" + FACE_UV + ";";
        String face = "(L" + FACE + ";)";
        t.registerExactFieldRedirect(FACE, "uv", uv, HELPERS, "uv", face + uv);
        t.registerMethodRedirect(FACE, "uv", "()" + uv, HELPERS, "uv", face + uv);
        String oldConstructor = "(" + DIRECTION + "ILjava/lang/String;" + uv + ")";
        t.registerConstructorRedirect(FACE, oldConstructor + "V", HELPERS, "face", oldConstructor + "L" + FACE + ";");
    }
}
