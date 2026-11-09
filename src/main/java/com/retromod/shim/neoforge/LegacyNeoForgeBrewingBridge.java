/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.forge.LegacyBrewingBridge;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.shim.forge.embedded.LegacyBrewingStore;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Rebuilds NeoForge 1.21.1's brewing registration event inside each old mod, on NeoForge hosts that removed it for brewing recipe files. */
public final class LegacyNeoForgeBrewingBridge {

    static final String OLD_EVENT = "net/neoforged/neoforge/event/brewing/RegisterBrewingRecipesEvent";
    static final String OLD_BUILDER = "net/minecraft/world/item/alchemy/PotionBrewing$Builder";
    static final String OLD_RECIPE = "net/neoforged/neoforge/common/brewing/IBrewingRecipe";
    // Every NeoForge host ships this class, so reading it tells a running NeoForge host from an offline run.
    static final String ANCHOR = "net/neoforged/neoforge/common/NeoForge";
    /** The first NeoForge host without the event; mods built for it never name the old classes. */
    public static final String FIRST_HOST = "26.3";

    static final String ROOT = RebuiltApiClasses.ROOT + "neoforge/brewing/";
    static final String EVENT = ROOT + "LegacyRegisterBrewingRecipesEvent";
    static final String BUILDER = ROOT + "LegacyPotionBrewingBuilder";
    /** The rebuilt class whose {@code install()} posts the old event in each jar that names it. */
    static final String INSTALLER = ROOT + "LegacyNeoForgeBrewing";
    static final String STORE = "com/retromod/shim/forge/embedded/LegacyBrewingStore";
    // The store offers its recipe files through this pack source.
    static final String PACK_SOURCE = "com/retromod/shim/forge/embedded/LegacyDataPackSource";
    static final String HOOK_KEY = "legacy-brewing-neoforge";
    private static final ModConstructorHooks.Call INSTALL =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, INSTALLER, "install", "()V", List.of());
    private static final byte[] OLD_EVENT_NAME = OLD_EVENT.getBytes(StandardCharsets.ISO_8859_1);

    private LegacyNeoForgeBrewingBridge() {}

    /** Registers the bridge on a NeoForge host without the old event; offline, a NeoForge target from 26.3. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge() || !hostTakesBridge(host)) return;
        // The recipe interface and the registry's adds, which the rebuilt builder hands its recipes to.
        LegacyBrewingBridge.register(t);
        SyntheticEmbedder.registerClassResource(t, STORE, LegacyBrewingStore.class);
        SyntheticEmbedder.registerClassResource(t, PACK_SOURCE, LegacyBrewingStore.class);
        RebuiltApiClasses.registerTree(t, INSTALLER);
        // @SubscribeEvent methods and addListener references subscribe to the stand-in on NeoForge's bus.
        redirect(t, OLD_EVENT, EVENT);
        redirect(t, OLD_BUILDER, BUILDER);
        // A recipe the mod built is handed on as it is; its interface may have either name at this point.
        for (String owner : List.of(OLD_BUILDER, BUILDER)) {
            for (String recipe : List.of(OLD_RECIPE, LegacyBrewingBridge.recipeInterface())) {
                t.registerMethodRedirect(owner, "addRecipe", "(L" + recipe + ";)V",
                        BUILDER, "addRecipe", "(Ljava/lang/Object;)V");
            }
        }
        // Each jar's copy posts its own stand-in once the server has started.
        ModConstructorHooks.register(t, HOOK_KEY, LegacyNeoForgeBrewingBridge::plan);
    }

    private static void redirect(RetromodTransformer t, String old, String standIn) {
        t.registerClassRedirect(old, standIn);
        t.limitClassRedirectToModsBefore(old, FIRST_HOST);
    }

    /** Whether the NeoForge host lacks the old event; offline or unreadable, a target from 26.3. */
    static boolean hostTakesBridge(Function<String, ClassNode> host) {
        // Offline the CLI reads only the vanilla jar, so the target version stands in for the host.
        if (host.apply(ANCHOR) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        }
        return host.apply(OLD_EVENT) == null;
    }

    /** The install call in each {@code @Mod} constructor of a jar whose original classes name the old event, or null. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (namesOldEvent(original)) return (modClass, modId) -> List.of(INSTALL);
        }
        return null;
    }

    // The exact class name, so a class in the same package with a longer name does not count.
    static boolean namesOldEvent(byte[] haystack) {
        outer:
        for (int start = 0; start <= haystack.length - OLD_EVENT_NAME.length; start++) {
            for (int i = 0; i < OLD_EVENT_NAME.length; i++) {
                if (haystack[start + i] != OLD_EVENT_NAME[i]) continue outer;
            }
            int next = start + OLD_EVENT_NAME.length;
            if (next == haystack.length || !isNamePart(haystack[next])) return true;
        }
        return false;
    }

    private static boolean isNamePart(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_' || b == '$';
    }
}
