/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Rebuilds or redirects vanilla members that mods built for 1.20 to 1.21.4 call and Minecraft removed by 1.21.11, 26.1 or 26.2. */
public final class LegacyRemovedMemberBridge {

    static final String RENDER_CALL = "com/mojang/blaze3d/pipeline/RenderCall";
    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String LIGHTING = "com/mojang/blaze3d/platform/Lighting";
    static final String ABGR32 = "net/minecraft/util/FastColor$ABGR32";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String OWNABLE = "net/minecraft/world/entity/OwnableEntity";
    static final String DIRECTION = "net/minecraft/core/Direction";
    static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    static final String PATCH = "net/minecraft/core/component/DataComponentPatch";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";
    static final String HUMANOID = "net/minecraft/client/model/HumanoidModel";

    public static final String RENDER_CALL_STAND_IN = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyRenderCall";
    static final String RENDER_CALLS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyRenderCalls";
    static final String ABGR = RebuiltApiClasses.ROOT + "minecraft/util/LegacyAbgr32";
    static final String CLIENT_FLAGS = RebuiltApiClasses.ROOT + "minecraft/client/LegacyClientFlags";
    static final String ENTITY_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacyEntityMembers";
    static final String PATCHES = RebuiltApiClasses.ROOT + "minecraft/core/component/LegacyComponentPatches";
    static final String HUMANOIDS = RebuiltApiClasses.ROOT + "minecraft/client/model/LegacyHumanoidModels";

    // The first host each removal was checked on. 1.21.11 is the oldest Mojang-named jar at hand, so
    // offline those members wait for it; in a game the host is read instead.
    static final String CHECKED_1_21_11 = "1.21.11";
    static final String FIRST_PATCH_LOOKUP_HOST = "26.1";
    static final String FIRST_HOST_WITHOUT_SET_ALL_VISIBLE = "26.1";
    static final String FIRST_HUD_HOST = "26.2";

    private LegacyRemovedMemberBridge() {}

    /** Mojang names: Forge and NeoForge from 1.21.11, and Fabric from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        registerRenderCalls(t, host);
        // The static GUI lighting setups became Lighting.setupFor(entry) on an instance the renderer owns.
        for (String setup : new String[] {"setupFor3DItems", "setupForFlatItems", "setupForEntityInInventory"}) {
            if (lacksMethod(host, LIGHTING, setup, "()V", CHECKED_1_21_11)) {
                t.registerRemovedMethodNeutralize(LIGHTING, setup, "()V");
            }
        }
        // ARGB32 already maps onto ARGB; the ABGR order has no host class left, so it is rebuilt.
        if (lacksClass(host, ABGR32, MINECRAFT, CHECKED_1_21_11)) {
            RebuiltApiClasses.registerTree(t, ABGR);
            t.registerClassRedirect(ABGR32, ABGR);
        }
        registerEntityMembers(t, host);
        registerHumanoidModels(t, host);
        LegacyVanillaMemberBridge.register(t, host);
        if (lacksMethod(host, PATCH, "get", "(Lnet/minecraft/core/component/DataComponentType;)Ljava/util/Optional;",
                FIRST_PATCH_LOOKUP_HOST)) {
            RebuiltApiClasses.registerTree(t, PATCHES);
            t.registerMethodRedirect(PATCH, "get", "(Lnet/minecraft/core/component/DataComponentType;)Ljava/util/Optional;",
                    PATCHES, "get", "(Ljava/lang/Object;Lnet/minecraft/core/component/DataComponentType;)Ljava/util/Optional;", true);
        }
        if (lacksMethod(host, MINECRAFT, "renderNames", "()Z", FIRST_HUD_HOST)) {
            RebuiltApiClasses.registerTree(t, CLIENT_FLAGS);
            t.registerMethodRedirect(MINECRAFT, "renderNames", "()Z", CLIENT_FLAGS, "renderNames", "()Z");
        }
        if (lacksMethod(host, BLOCK_POS, "getCenter", "()L" + VEC3 + ";", FIRST_HUD_HOST)) {
            t.registerMethodRedirect(BLOCK_POS, "getCenter", "()L" + VEC3 + ";",
                    VEC3, "atCenterOf", "(Lnet/minecraft/core/Vec3i;)L" + VEC3 + ";", true);
        }
    }

    // RenderCall was the task type RenderSystem.recordRenderCall queued for the render thread.
    private static void registerRenderCalls(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!lacksClass(host, RENDER_CALL, RENDER_SYSTEM, CHECKED_1_21_11)) return;
        RebuiltApiClasses.registerTree(t, RENDER_CALLS);
        t.registerClassRedirect(RENDER_CALL, RENDER_CALL_STAND_IN);
        // Under both task names, whichever the class redirect has already applied when the call is matched.
        for (String task : new String[] {RENDER_CALL, RENDER_CALL_STAND_IN}) {
            t.registerMethodRedirect(RENDER_SYSTEM, "recordRenderCall", "(L" + task + ";)V",
                    RENDER_CALLS, "recordRenderCall", "(L" + RENDER_CALL_STAND_IN + ";)V");
        }
    }

    // Humanoid models pose themselves from a render state now; the part visibility and pose copies still have parts to act on.
    private static void registerHumanoidModels(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean visibility = lacksMethod(host, HUMANOID, "setAllVisible", "(Z)V", FIRST_HOST_WITHOUT_SET_ALL_VISIBLE);
        String copy = "(L" + HUMANOID + ";)V";
        boolean poses = lacksMethod(host, HUMANOID, "copyPropertiesTo", copy, CHECKED_1_21_11);
        if (visibility || poses) RebuiltApiClasses.registerTree(t, HUMANOIDS);
        if (visibility) {
            t.registerMethodRedirect(HUMANOID, "setAllVisible", "(Z)V",
                    HUMANOIDS, "setAllVisible", "(Ljava/lang/Object;Z)V", true);
        }
        if (poses) {
            t.registerMethodRedirect(HUMANOID, "copyPropertiesTo", copy,
                    HUMANOIDS, "copyPropertiesTo", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        }
    }

    private static void registerEntityMembers(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean armorSlots = lacksMethod(host, LIVING, "getArmorSlots", "()Ljava/lang/Iterable;", CHECKED_1_21_11);
        boolean ownerId = lacksMethod(host, OWNABLE, "getOwnerUUID", "()Ljava/util/UUID;", CHECKED_1_21_11);
        if (armorSlots || ownerId) RebuiltApiClasses.registerTree(t, ENTITY_MEMBERS);
        if (armorSlots) {
            t.registerMethodRedirect(LIVING, "getArmorSlots", "()Ljava/lang/Iterable;",
                    ENTITY_MEMBERS, "getArmorSlots", "(Ljava/lang/Object;)Ljava/lang/Iterable;", true);
        }
        if (ownerId) {
            t.registerMethodRedirect(OWNABLE, "getOwnerUUID", "()Ljava/util/UUID;",
                    ENTITY_MEMBERS, "getOwnerUUID", "(Ljava/lang/Object;)Ljava/util/UUID;", true);
        }
        // Direction.getNearest(Vec3) kept its job under a new name.
        String nearest = "(L" + VEC3 + ";)L" + DIRECTION + ";";
        if (lacksMethod(host, DIRECTION, "getNearest", nearest, CHECKED_1_21_11)) {
            t.registerMethodRedirect(DIRECTION, "getNearest", nearest, DIRECTION, "getApproximateNearest", nearest);
        }
    }

    /** Whether the host lacks {@code owner.name(desc)}; with no host to read, whether the target is {@code firstHost} or newer. */
    static boolean lacksMethod(Function<String, ClassNode> host, String owner, String name, String desc, String firstHost) {
        ClassNode node = host.apply(owner);
        if (node == null) return targetAtLeast(firstHost);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    /** Whether the host lacks {@code name}, read only when {@code anchor} shows host classes are readable. */
    static boolean lacksClass(Function<String, ClassNode> host, String name, String anchor, String firstHost) {
        if (host.apply(anchor) == null) return targetAtLeast(firstHost);
        return host.apply(name) == null;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
