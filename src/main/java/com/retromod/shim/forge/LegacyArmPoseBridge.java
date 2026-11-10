/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Sends Forge 1.20.1's {@code ArmPose.create(name, twoHanded, transformer)} to a rebuilt helper on Forge 26.x and on NeoForge. */
public final class LegacyArmPoseBridge {

    static final String ARM_POSE = "net/minecraft/client/model/HumanoidModel$ArmPose";
    static final String TRANSFORMER = "net/minecraftforge/client/IArmPoseTransformer";
    static final String OLD_CREATE = "(Ljava/lang/String;ZL" + TRANSFORMER + ";)L" + ARM_POSE + ";";
    static final String HOST_CREATE = "(Ljava/lang/String;ZZL" + TRANSFORMER + ";)L" + ARM_POSE + ";";
    static final String HOST_APPLY = "(Lnet/minecraft/client/model/HumanoidModel;"
            + "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;Lnet/minecraft/world/entity/HumanoidArm;)V";

    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/";
    static final String HELPER = ROOT + "LegacyArmPoses";
    static final String NEO_HELPER = ROOT + "LegacyNeoArmPoses";
    static final String LEGACY_TRANSFORMER = ROOT + "LegacyArmPoseTransformer";
    static final String HELPER_CREATE = "(Ljava/lang/String;ZL" + LEGACY_TRANSFORMER + ";)L" + ARM_POSE + ";";

    /**
     * The first version whose transformer takes a render state. Minecraft 1.21.2 moved the humanoid model onto
     * render states, so a mod built for it or later already implements the host's shape.
     */
    static final String RENDER_STATE_VERSION = "1.21.2";

    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyArmPoseBridge() {}

    /** Registers the helper on a Forge host whose create also asks about the offhand; offline, a Forge target from 26.2. */
    public static void registerForge(RetromodTransformer t) {
        if (!forgeTakesRenderStates(ClassResourceInspector::read)) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        // The old interface first, so the create redirect below also matches once the class is renamed.
        t.registerClassRedirect(TRANSFORMER, LEGACY_TRANSFORMER);
        t.limitClassRedirectToModsBefore(TRANSFORMER, RENDER_STATE_VERSION);
        t.registerMethodRedirect(ARM_POSE, "create", OLD_CREATE, HELPER, "create", HELPER_CREATE);
        t.limitRedirectToModsBefore(ARM_POSE, "create", OLD_CREATE, RENDER_STATE_VERSION);
    }

    /** Registers the fixed-pose helper on NeoForge, which has neither Forge's transformer nor a create to call. */
    public static void registerNeoForge(RetromodTransformer t) {
        if (!McReflect.isNeoForge()) return;
        RebuiltApiClasses.registerTree(t, NEO_HELPER);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        // The mod's transformer class must still load, so it implements the rebuilt interface.
        t.registerClassRedirect(TRANSFORMER, LEGACY_TRANSFORMER);
        t.registerMethodRedirect(ARM_POSE, "create", OLD_CREATE, NEO_HELPER, "create", HELPER_CREATE);
    }

    static boolean forgeTakesRenderStates(Function<String, ClassNode> host) {
        if (!ForgeHostShape.matches(List.of(), List.of(), ForgeHostShape.FIRST_CHECKED_VERSION, host)) return false;
        ClassNode pose = host.apply(ARM_POSE);
        ClassNode transformer = host.apply(TRANSFORMER);
        // A dedicated server has no client classes and never runs this call, so the version rule above stands.
        if (pose == null || transformer == null) return true;
        return has(pose, "create", HOST_CREATE) && !has(pose, "create", OLD_CREATE)
                && has(transformer, "applyTransform", HOST_APPLY);
    }

    private static boolean has(ClassNode node, String name, String desc) {
        return node.methods != null && node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
