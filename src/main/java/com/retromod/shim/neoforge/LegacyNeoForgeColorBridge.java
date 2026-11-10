/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyColorClasses;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Rebuilds NeoForge 1.21.1's block and item color events inside each old mod, on NeoForge hosts that replaced them with tint sources. */
public final class LegacyNeoForgeColorBridge {

    static final String EVENTS = "net/neoforged/neoforge/client/event/RegisterColorHandlersEvent";
    static final String NEO_BLOCK = EVENTS + "$Block";
    static final String NEO_ITEM = EVENTS + "$Item";
    // A Forge mod's names reach NeoForge's through the bulk event renames, and are redirected here too for the offline Forge chain.
    static final String FORGE_BLOCK = "net/minecraftforge/client/event/RegisterColorHandlersEvent$Block";
    static final String FORGE_ITEM = "net/minecraftforge/client/event/RegisterColorHandlersEvent$Item";
    static final String BLOCK_TINT_SOURCES = EVENTS + "$BlockTintSources";
    static final String ITEM_TINT_SOURCES = EVENTS + "$ItemTintSources";
    // Every NeoForge host ships this class, so reading it tells a running NeoForge host from an offline run.
    static final String ANCHOR = "net/neoforged/neoforge/common/NeoForge";
    /** The first NeoForge host without the block color event; mods built for it never name the old classes. */
    public static final String FIRST_HOST = "26.1";

    static final String BLOCK_EVENT = RebuiltApiClasses.ROOT + "neoforge/client/event/LegacyBlockColorsEvent";
    static final String ITEM_EVENT = RebuiltApiClasses.ROOT + "neoforge/client/event/LegacyItemColorsEvent";
    /** The rebuilt class whose {@code install()} posts the old events and adds each mod's item tint type. */
    public static final String INSTALLER = RebuiltApiClasses.ROOT + "neoforge/client/color/LegacyNeoForgeColors";
    static final String HOOK_KEY = "legacy-colors-neoforge";
    private static final ModConstructorHooks.Call INSTALL =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, INSTALLER, "install", "()V", List.of());
    // A jar that names one of these needs the install: the events are posted by it, and item tints need its tint type.
    private static final List<byte[]> INSTALL_NAMES = List.of(bytes(NEO_BLOCK), bytes(NEO_ITEM), bytes(FORGE_BLOCK),
            bytes(FORGE_ITEM), bytes(LegacyColorClasses.OLD_ITEM_COLOR), bytes(LegacyColorClasses.OLD_ITEM_COLORS));

    private LegacyNeoForgeColorBridge() {}

    /** Registers the bridge where the NeoForge host has the block tint event and no old block event; offline, a NeoForge target from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge() || !hostTakesBridge(host)) return;
        // The color classes, BlockColors' old calls and Minecraft.getItemColors() are rebuilt the same way for Forge.
        LegacyColorClasses.register(t, FIRST_HOST);
        RebuiltApiClasses.registerTree(t, INSTALLER);
        // A subscriber class that names a missing event loses every listener it has, so both old events resolve to
        // stand-ins that @SubscribeEvent methods and addListener method references subscribe to on NeoForge's bus.
        for (String old : List.of(NEO_BLOCK, FORGE_BLOCK)) redirect(t, old, BLOCK_EVENT);
        for (String old : List.of(NEO_ITEM, FORGE_ITEM)) redirect(t, old, ITEM_EVENT);
        LegacyColorClasses.registerItemColorsOwner(t, ITEM_EVENT);
        // Each jar's copy posts its own stand-ins and adds its mods' tint types, set up while its mods construct.
        ModConstructorHooks.register(t, HOOK_KEY, LegacyNeoForgeColorBridge::plan);
    }

    private static void redirect(RetromodTransformer t, String old, String standIn) {
        t.registerClassRedirect(old, standIn);
        t.limitClassRedirectToModsBefore(old, FIRST_HOST);
    }

    /** Whether the NeoForge host registers block tint sources and has no old block event; offline or unreadable, NeoForge from 26.1. */
    static boolean hostTakesBridge(Function<String, ClassNode> host) {
        // Offline the CLI reads only the vanilla jar, so the target version stands in for the host.
        if (host.apply(ANCHOR) == null) return fromFirstHost();
        // NeoForge 1.21.x still posts the old block event, so its mods keep their names there.
        if (host.apply(NEO_BLOCK) != null) return false;
        if (host.apply(BLOCK_TINT_SOURCES) != null) return host.apply(ITEM_TINT_SOURCES) != null;
        // A host whose client events cannot be read keeps the version rule, as offline.
        return fromFirstHost();
    }

    private static boolean fromFirstHost() {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
    }

    /** The install call in each {@code @Mod} constructor of a jar whose original classes name an old color event or item colors, or null. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            for (byte[] name : INSTALL_NAMES) {
                if (namesClass(original, name)) return (modClass, modId) -> List.of(INSTALL);
            }
        }
        return null;
    }

    // The exact class name, so NeoForge 26.x's own BlockTintSources does not count as the old Block event.
    private static boolean namesClass(byte[] haystack, byte[] name) {
        outer:
        for (int start = 0; start <= haystack.length - name.length; start++) {
            for (int i = 0; i < name.length; i++) {
                if (haystack[start + i] != name[i]) continue outer;
            }
            int next = start + name.length;
            if (next == haystack.length || !isNamePart(haystack[next])) return true;
        }
        return false;
    }

    // A class name goes on with a letter, digit, '_' or '$'; a descriptor's ';' or the next constant's tag ends it.
    private static boolean isNamePart(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_' || b == '$';
    }

    private static byte[] bytes(String name) {
        return name.getBytes(StandardCharsets.ISO_8859_1);
    }
}
