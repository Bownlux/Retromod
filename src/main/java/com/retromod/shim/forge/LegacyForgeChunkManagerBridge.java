/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Points Forge's {@code ForgeChunkManager} at its rebuild on NeoForge's ticket controllers, or on a Forge host's forced chunks. */
public final class LegacyForgeChunkManagerBridge {

    static final String FORGE = "net/minecraftforge/common/world/ForgeChunkManager";
    static final String REBUILT = RebuiltApiClasses.ROOT + "forge/common/world/LegacyForgeChunkManager";
    static final String VANILLA = RebuiltApiClasses.ROOT + "forge/common/world/LegacyVanillaChunkManager";
    static final String CONTROLLER = "net/neoforged/neoforge/common/world/chunk/TicketController";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String OLD_ID = "net/minecraft/resources/ResourceLocation";
    static final String TICKET_SET = "net/neoforged/neoforge/common/world/chunk/TicketSet";
    static final String LONG_SET = "Lit/unimi/dsi/fastutil/longs/LongSet;";
    // Loader classes that show the CLI read the loader jar, not only Minecraft's.
    static final String NEOFORGE_ANCHOR = "net/neoforged/neoforge/common/NeoForge";
    static final String FORGE_ANCHOR = "net/minecraftforge/common/MinecraftForge";
    static final String HOOK_KEY = "legacy-forge-chunk-manager";
    private static final byte[] FORGE_NAME = FORGE.getBytes(StandardCharsets.ISO_8859_1);

    private LegacyForgeChunkManagerBridge() {}

    /** Rebuilds {@code ForgeChunkManager} on NeoForge, where Epic Core API sets its callback while it constructs (#398). */
    public static void register(RetromodTransformer t) {
        String target = RetromodVersion.TARGET_MC_VERSION;
        // The controllers came with NeoForge's chunk rework; without the loader jar, every NeoForge 1.21 and newer has them.
        boolean controllers = ClassResourceInspector.read(NEOFORGE_ANCHOR) != null ? ClassResourceInspector.exists(CONTROLLER)
                : RetromodVersion.compareMcVersions(target, "1.21") >= 0;
        if (!controllers) return;
        Map<String, String> names = new HashMap<>();
        // The rebuild names ids Identifier, which a host before 1.21.11 calls ResourceLocation.
        boolean identifier = ClassResourceInspector.read("net/minecraft/server/MinecraftServer") != null
                ? ClassResourceInspector.exists(IDENTIFIER)
                : RetromodVersion.compareMcVersions(target, "1.21.11") >= 0;
        if (!identifier) names.put(IDENTIFIER, OLD_ID);
        // 1.21.5's ticket storage split a ticket set into normal and natural spawning chunks, read as non-ticking and ticking.
        ClassNode sets = ClassResourceInspector.read(TICKET_SET);
        boolean spawningSets = sets != null ? sets.methods.stream().anyMatch(method -> method.name.equals("naturalSpawning"))
                : RetromodVersion.compareMcVersions(target, "1.21.5") >= 0;
        if (spawningSets) {
            names.put(TICKET_SET + ".nonTicking()" + LONG_SET, "normal");
            names.put(TICKET_SET + ".ticking()" + LONG_SET, "naturalSpawning");
        }
        Remapper remapper = names.isEmpty() ? null : new SimpleRemapper(names);
        RebuiltApiClasses.registerTree(t, REBUILT, remapper);
        redirect(t, REBUILT);
        // Each mod that names the class declares its controller while it constructs, even without a callback.
        ModConstructorHooks.register(t, HOOK_KEY, LegacyForgeChunkManagerBridge::plan);
    }

    /** On a Forge host that removed {@code ForgeChunkManager}, as Forge 26.2 did, the level's own forced chunks take its place. */
    public static void registerForge(RetromodTransformer t) {
        // Without the loader jar, Forge 26.2 and newer lack it.
        boolean removed = ClassResourceInspector.read(FORGE_ANCHOR) != null ? !ClassResourceInspector.exists(FORGE)
                : RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0;
        if (!removed) return;
        RebuiltApiClasses.registerTree(t, VANILLA);
        redirect(t, VANILLA);
    }

    /** The declare call for each {@code @Mod} constructor of a jar whose original classes name {@code ForgeChunkManager}, or null. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (contains(original, FORGE_NAME)) {
                return (modClass, modId) -> List.of(new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, REBUILT,
                        "declare", "(Ljava/lang/String;)V", List.of(modId)));
            }
        }
        return null;
    }

    // The class name or one of its nested classes; nothing else in Forge starts with it.
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

    private static void redirect(RetromodTransformer t, String rebuilt) {
        for (String nested : new String[] {"", "$LoadingValidationCallback", "$TicketHelper"}) {
            t.registerClassRedirect(FORGE + nested, rebuilt + nested);
        }
    }
}
