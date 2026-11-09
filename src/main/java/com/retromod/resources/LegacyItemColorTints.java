/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.retromod.shim.forge.LegacyForgeColorBridge;
import com.retromod.shim.neoforge.LegacyNeoForgeColorBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** The item definition tints that hand an old Forge or NeoForge mod's item models to the item colors Retromod rebuilds in it. */
final class LegacyItemColorTints {

    /** The first Forge host whose item colors Retromod rebuilds; NeoForge has its own. */
    static final String FIRST_HOST = "26.2";
    /** The tint type path each mod's copy registers under the mod's namespace. */
    static final String TYPE_PATH = "retromod_legacy_color";
    /** Tint slots for a model whose parent comes from another mod, which nothing here can read. */
    static final int UNKNOWN_SLOTS = 4;
    /** The most tints one definition gets; each is computed for every drawn stack, every frame. */
    static final int MAX_SLOTS = 16;
    /** A vanilla template that ends in builtin/generated, where the child's layer textures decide. */
    static final int GENERATED = -1;

    private static final String TABLE = "/retromod/vanilla-model-tints.tsv";
    private static final String BUILTIN_GENERATED = "minecraft:builtin/generated";
    private static volatile Map<String, Integer> vanilla;

    private LegacyItemColorTints() {}

    /** The declared mod ids whose {@code @Mod} constructor installs old item colors, so their tint types exist; null for none. */
    static Set<String> modsWithItemColors(MigrationContext context, Collection<String> classNames,
            Function<String, byte[]> readClass) {
        // Each loader's install adds the type from its own first host: Forge from 26.2, NeoForge from 26.1.
        if (context.targetLoader() == MigrationContext.Loader.NEOFORGE) {
            return LegacyClientInstalls.modsInstalling(context, classNames, readClass, LegacyNeoForgeColorBridge.INSTALLER,
                    LegacyNeoForgeColorBridge.FIRST_HOST, MigrationContext.Loader.NEOFORGE);
        }
        return LegacyClientInstalls.modsInstalling(context, classNames, readClass, LegacyForgeColorBridge.INSTALLER, FIRST_HOST);
    }

    /** {@code <mod>:retromod_legacy_color}: the item's own mod when it installs, otherwise the first mod that does. */
    static String typeId(String itemNamespace, Set<String> mods) {
        return LegacyClientInstalls.typeId(itemNamespace, mods, TYPE_PATH);
    }

    /** One tint per slot, each asking the old item color for its own tint index. */
    static JsonArray tints(String typeId, int slots) {
        JsonArray tints = new JsonArray();
        for (int index = 0; index < Math.min(slots, MAX_SLOTS); index++) {
            JsonObject tint = new JsonObject();
            tint.addProperty("type", typeId);
            tint.addProperty("index", index);
            tints.add(tint);
        }
        return tints;
    }

    /** The tint slots a vanilla model's quads use, {@link #GENERATED} for a layered template, or 0. */
    static int vanillaSlots(String modelId) {
        if (BUILTIN_GENERATED.equals(modelId)) return GENERATED;
        return vanilla().getOrDefault(modelId, 0);
    }

    private static Map<String, Integer> vanilla() {
        Map<String, Integer> table = vanilla;
        if (table != null) return table;
        table = new HashMap<>();
        try (InputStream in = LegacyItemColorTints.class.getResourceAsStream(TABLE)) {
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] row = line.split("\t");
                    table.put(row[0], row[1].equals("generated") ? GENERATED : Integer.parseInt(row[1]));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Without the table a vanilla parent counts as untinted, as most are.
        }
        vanilla = Map.copyOf(table);
        return vanilla;
    }
}
