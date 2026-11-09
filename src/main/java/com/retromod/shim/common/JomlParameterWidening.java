/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Retypes calls to vanilla members whose JOML parameters became read-only interfaces in 1.21.x. */
public final class JomlParameterWidening {

    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String POSE = "com/mojang/blaze3d/vertex/PoseStack$Pose";
    private static final String VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String BUFFER_BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final String TRANSFORMATION = "com/mojang/math/Transformation";
    private static final String KEYFRAME = "net/minecraft/client/animation/Keyframe";
    private static final String VEC3 = "net/minecraft/world/phys/Vec3";
    private static final String ITEM_TRANSFORM = "net/minecraft/client/renderer/block/model/ItemTransform";
    private static final String ITEM_TRANSFORM_26_1 = "net/minecraft/client/resources/model/cuboid/ItemTransform";

    private static final String V3 = "Lorg/joml/Vector3f;";
    private static final String V3C = "Lorg/joml/Vector3fc;";
    private static final String Q = "Lorg/joml/Quaternionf;";
    private static final String QC = "Lorg/joml/Quaternionfc;";
    private static final String M4 = "Lorg/joml/Matrix4f;";
    private static final String M4C = "Lorg/joml/Matrix4fc;";

    private JomlParameterWidening() {}

    /** Every Mojang-named widening, for the 26.1 path, which can run without the 1.21.x shims. */
    public static void registerAll(RetromodTransformer t) {
        register1_21_5(t);
        register1_21_9(t);
        register1_21_11(t);
    }

    /** PoseStack, its Pose, Transformation, and ItemTransform took the interfaces in 1.21.5. */
    public static void register1_21_5(RetromodTransformer t) {
        // The concrete value on the stack already implements the interface, so only the descriptor moves.
        widen(t, POSE_STACK, "mulPose", "(" + Q + ")V", "(" + QC + ")V");
        widen(t, POSE_STACK, "mulPose", "(" + M4 + ")V", "(" + M4C + ")V");
        widen(t, POSE_STACK, "rotateAround", "(" + Q + "FFF)V", "(" + QC + "FFF)V");
        // Only the input normal widened; the output vector is still written, so it stays Vector3f.
        widen(t, POSE, "transformNormal", "(" + V3 + V3 + ")" + V3, "(" + V3C + V3 + ")" + V3);
        widen(t, TRANSFORMATION, "<init>", "(" + M4 + ")V", "(" + M4C + ")V");

        // 26.1 moved ItemTransform, and the class move renames the call's owner before this lookup.
        String itemTransform = RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)
                ? ITEM_TRANSFORM_26_1 : ITEM_TRANSFORM;
        widen(t, itemTransform, "<init>", "(" + V3 + V3 + V3 + ")V", "(" + V3C + V3C + V3C + ")V");
    }

    /** Keyframe's constructors took Vector3fc in 1.21.9. */
    public static void register1_21_9(RetromodTransformer t) {
        String interp = "Lnet/minecraft/client/animation/AnimationChannel$Interpolation;";
        widen(t, KEYFRAME, "<init>", "(F" + V3 + interp + ")V", "(F" + V3C + interp + ")V");
        // No release had the pre and post form with Vector3f; the key is harmless and kept as before.
        widen(t, KEYFRAME, "<init>", "(F" + V3 + V3 + interp + ")V", "(F" + V3C + V3C + interp + ")V");
    }

    /** VertexConsumer.addVertex, Transformation, and Vec3 took the interfaces in 1.21.11. */
    public static void register1_21_11(RetromodTransformer t) {
        registerVertexMatrixWidening(t);
        String tail = ")L" + VERTEX_CONSUMER + ";";
        for (String owner : new String[]{VERTEX_CONSUMER, BUFFER_BUILDER}) {
            t.registerMethodRedirect(owner, "addVertex", "(" + V3 + tail, owner, "addVertex", "(" + V3C + tail);
        }
        widen(t, TRANSFORMATION, "<init>", "(" + V3 + Q + V3 + Q + ")V", "(" + V3C + QC + V3C + QC + ")V");
        widen(t, VEC3, "<init>", "(" + V3 + ")V", "(" + V3C + ")V");
    }

    /** VertexConsumer.addVertex(Matrix4f, x, y, z) took Matrix4fc in 1.21.11. */
    public static void registerVertexMatrixWidening(RetromodTransformer t) {
        // BufferBuilder inherits the widened default from VertexConsumer, so its own owner still resolves.
        String tail = "FFF)L" + VERTEX_CONSUMER + ";";
        for (String owner : new String[]{VERTEX_CONSUMER, BUFFER_BUILDER}) {
            t.registerMethodRedirect(owner, "addVertex", "(" + M4 + tail, owner, "addVertex", "(" + M4C + tail);
        }
    }

    /** The 1.21.5 widenings in the intermediary names Fabric mods keep before 26.1. */
    public static void registerIntermediary1_21_5(RetromodTransformer t) {
        // Intermediary kept every method id through the widening, so these match the Mojang forms.
        String poseStack = "net/minecraft/class_4587";
        widen(t, poseStack, "method_22907", "(" + Q + ")V", "(" + QC + ")V");
        widen(t, poseStack, "method_34425", "(" + M4 + ")V", "(" + M4C + ")V");
        widen(t, poseStack, "method_49278", "(" + Q + "FFF)V", "(" + QC + "FFF)V");
        widen(t, "net/minecraft/class_4587$class_4665", "method_56821",
                "(" + V3 + V3 + ")" + V3, "(" + V3C + V3 + ")" + V3);
        widen(t, "net/minecraft/class_4590", "<init>", "(" + M4 + ")V", "(" + M4C + ")V");
        widen(t, "net/minecraft/class_804", "<init>", "(" + V3 + V3 + V3 + ")V", "(" + V3C + V3C + V3C + ")V");
    }

    /** The 1.21.9 Keyframe widening in intermediary names. */
    public static void registerIntermediary1_21_9(RetromodTransformer t) {
        String interp = "Lnet/minecraft/class_7179$class_7180;";
        widen(t, "net/minecraft/class_7186", "<init>", "(F" + V3 + interp + ")V", "(F" + V3C + interp + ")V");
    }

    /** The 1.21.11 widenings in intermediary names. */
    public static void registerIntermediary1_21_11(RetromodTransformer t) {
        String tail = ")Lnet/minecraft/class_4588;";
        for (String owner : new String[]{"net/minecraft/class_4588", "net/minecraft/class_287"}) {
            t.registerMethodRedirect(owner, "method_22918", "(" + M4 + "FFF" + tail,
                    owner, "method_22918", "(" + M4C + "FFF" + tail);
            t.registerMethodRedirect(owner, "method_60830", "(" + V3 + tail,
                    owner, "method_60830", "(" + V3C + tail);
        }
        widen(t, "net/minecraft/class_4590", "<init>",
                "(" + V3 + Q + V3 + Q + ")V", "(" + V3C + QC + V3C + QC + ")V");
        widen(t, "net/minecraft/class_243", "<init>", "(" + V3 + ")V", "(" + V3C + ")V");
    }

    private static void widen(RetromodTransformer t, String owner, String name, String oldDesc, String newDesc) {
        t.registerConvertingRedirect(owner, name, oldDesc, owner, name, newDesc, 0, 0);
    }
}
