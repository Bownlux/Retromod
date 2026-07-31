/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.mapping.IntermediaryToMojangMapper;

/**
 * Remaps a Fabric mixin refmap JSON from the {@code intermediary} namespace to Mojang names and adds
 * an {@code official} data section, so a mod's {@code @Inject}/{@code @At} selectors resolve on a
 * 26.1+ (official-namespace) host. Shared by the runtime path ({@link FabricModTransformer}) and the
 * offline {@code RetromodCli} batch / AOT / nested-jar paths.
 *
 * <p>Nearly every Fabric mod ships a refmap. Unremapped, an {@code @Inject} targeting an
 * intermediary class like {@code net/minecraft/class_310} fails on 26.1+ with
 * {@code InvalidInjectionException: ... target class ... not supported}, usually killing the mod's
 * construction. Found on an in-game 26.2 Fabric launch, where the offline batch path had left
 * refmaps unremapped while the runtime path handled them.
 */
public final class MixinRefmapRemapper {

    private MixinRefmapRemapper() {}

    /**
     * Return {@code json} with its {@code mappings} section remapped to Mojang and a Mojang-mapped
     * {@code data.official} section added (from {@code data.intermediary} or {@code data.named}), or
     * the original text if nothing changed / it can't be parsed. Never throws.
     */
    public static String remap(String json, IntermediaryToMojangMapper mapper) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean changed = false;

            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                root.add("mappings", remapSection(root.getAsJsonObject("mappings"), mapper));
                changed = true;
            }

            if (root.has("data") && root.get("data").isJsonObject()) {
                JsonObject data = root.getAsJsonObject("data");
                // The data key names the target namespace as "<to>" or "<from>:<to>" (e.g. plain
                // "intermediary", or the combined "named:intermediary" that current Fabric loom emits).
                // For every key targeting intermediary, add the same key with "intermediary" -> "official"
                // and its selectors remapped to Mojang, so Fabric resolves the mixins on a 26.1+ host.
                for (String key : new java.util.ArrayList<>(data.keySet())) {
                    if (!data.get(key).isJsonObject()) continue;
                    String officialKey = null;
                    if (key.equals("intermediary")) officialKey = "official";
                    else if (key.endsWith(":intermediary")) {
                        officialKey = key.substring(0, key.length() - ":intermediary".length()) + ":official";
                    }
                    if (officialKey != null && !data.has(officialKey)) {
                        data.add(officialKey, remapSection(data.getAsJsonObject(key), mapper));
                        changed = true;
                    }
                }
            }

            if (!changed) return json;
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            return gson.toJson(root);
        } catch (Throwable t) {
            return json;
        }
    }

    /** Replace intermediary names with Mojang names throughout a refmap section (keys AND values). */
    private static JsonObject remapSection(JsonObject section, IntermediaryToMojangMapper mapper) {
        JsonObject result = new JsonObject();
        for (String mixinClassName : section.keySet()) {
            if (!section.get(mixinClassName).isJsonObject()) {
                result.add(mixinClassName, section.get(mixinClassName));
                continue;
            }
            JsonObject entries = section.getAsJsonObject(mixinClassName);
            JsonObject remappedEntries = new JsonObject();
            for (String key : entries.keySet()) {
                String value = entries.get(key).getAsString();
                remappedEntries.addProperty(mapper.remapString(key), mapper.remapString(value));
            }
            result.add(mixinClassName, remappedEntries);
        }
        return result;
    }
}
