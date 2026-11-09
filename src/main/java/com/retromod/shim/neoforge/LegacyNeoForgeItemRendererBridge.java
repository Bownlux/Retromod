/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyItemRendererBridge;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.HostBuffers;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Registers the rebuilt item renderers (BlockEntityWithoutLevelRenderer) on NeoForge hosts, for NeoForge and Forge mods alike. */
public final class LegacyNeoForgeItemRendererBridge {

    static final String NEO_EXTENSIONS = "net/neoforged/neoforge/client/extensions/common/IClientItemExtensions";
    // A Forge mod's calls name Forge's interface until the Forge-to-NeoForge rename reaches them.
    static final String FORGE_EXTENSIONS = "net/minecraftforge/client/extensions/common/IClientItemExtensions";
    static final String REGISTER_ITEM_MODELS = "net/neoforged/neoforge/client/event/RegisterItemModelsEvent";
    // Every NeoForge host ships this class, so reading it tells a running NeoForge host from an offline run.
    static final String ANCHOR = "net/neoforged/neoforge/common/NeoForge";
    static final String UNBAKED = "net/minecraft/client/renderer/item/ItemModel$Unbaked";
    static final String BAKE_DESC = "(Lnet/minecraft/client/renderer/item/ItemModel$BakingContext;Lorg/joml/Matrix4fc;)"
            + "Lnet/minecraft/client/renderer/item/ItemModel;";
    /** The first NeoForge host whose item model API the rebuilt classes are written for. */
    public static final String FIRST_HOST = "26.1";
    /** Minecraft 1.21.4 removed the old base, so a mod built for it or later never names it. */
    static final String FIRST_VERSION_WITHOUT = "1.21.4";
    // 26.1 still has MultiBufferSource, which 26.2 replaced with Retromod's stand-in, so old renderers take the host's there.
    static final String HOST_BUFFERS = HostBuffers.HOST_BUFFERS;

    /** The rebuilt class whose {@code install()} registers each mod's item model type through NeoForge's event. */
    public static final String INSTALLER = RebuiltApiClasses.ROOT + "neoforge/client/renderer/LegacyItemRenderers";
    static final String HOOK_KEY = "legacy-item-renderers-neoforge";
    private static final ModConstructorHooks.Call INSTALL =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, INSTALLER, "install", "()V", List.of());
    private static final byte[] OLD_RENDERER_NAME = LegacyItemRendererBridge.OLD_RENDERER.getBytes(StandardCharsets.ISO_8859_1);

    private LegacyNeoForgeItemRendererBridge() {}

    /** Registers the bridge where the NeoForge host has the 26.1 item model API and no old base; offline, a NeoForge target from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge() || !hostTakesBridge(host)) return;
        LegacyItemRendererBridge.register(t, List.of(NEO_EXTENSIONS, FORGE_EXTENSIONS), FIRST_VERSION_WITHOUT, HostBuffers.names(host));
        RebuiltApiClasses.registerTree(t, INSTALLER);
        // Each jar that names the old base installs its copy while its mods construct, the one time NeoForge names a mod.
        ModConstructorHooks.register(t, HOOK_KEY, LegacyNeoForgeItemRendererBridge::plan);
    }

    /** Whether the host has NeoForge's item model event, no old base, no old getter, and the 26.1 item model bake. */
    static boolean hostTakesBridge(Function<String, ClassNode> host) {
        // Offline the CLI reads only the vanilla jar, so the target version stands in for the host.
        if (host.apply(ANCHOR) == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        ClassNode extensions = host.apply(NEO_EXTENSIONS);
        if (host.apply(REGISTER_ITEM_MODELS) == null || extensions == null) return false;
        if (host.apply(LegacyItemRendererBridge.OLD_RENDERER) != null) return false;
        if (extensions.methods.stream().anyMatch(m -> m.name.equals(LegacyItemRendererBridge.GET_CUSTOM_RENDERER))) return false;
        // A dedicated server reads no client class, so the version rule decides there, as offline.
        ClassNode unbaked = host.apply(UNBAKED);
        if (unbaked == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        // NeoForge 1.21.4 to 1.21.11 bake item models without a transformation, which the rebuilt model is not written for.
        return unbaked.methods.stream().anyMatch(m -> m.name.equals("bake") && m.desc.equals(BAKE_DESC));
    }

    /** Whether the host still has MultiBufferSource, which old renderers then take instead of Retromod's stand-in. */
    static boolean hostKeepsBuffers(Function<String, ClassNode> host) {
        return HostBuffers.keeps(host);
    }

    /** The install call in each {@code @Mod} constructor of a jar whose original classes name the old base, or null. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (contains(original, OLD_RENDERER_NAME)) return (modClass, modId) -> List.of(INSTALL);
        }
        return null;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) continue outer;
            }
            return true;
        }
        return false;
    }
}
