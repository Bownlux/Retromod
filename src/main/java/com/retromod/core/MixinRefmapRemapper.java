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
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.mixin.MixinRefmapRepairIndex;
import com.retromod.util.JsonSecurity;

import java.util.function.UnaryOperator;

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

    /** Remapped JSON plus the exact handler-repair facts collected from it. */
    public record RemapResult(String json, MixinRefmapRepairIndex repairs) {}

    /**
     * Return {@code json} with its {@code mappings} section remapped to Mojang and a Mojang-mapped
     * {@code data.official} section added (from {@code data.intermediary} or {@code data.named}), or
     * the original text if nothing changed / it can't be parsed. Never throws.
     */
    public static String remap(String json, IntermediaryToMojangMapper mapper) {
        return remap(json, mapper, null);
    }

    /**
     * Remap Fabric names, then apply a host-aware repair to official method-selector values.
     * The original intermediary data section is retained for older hosts.
     */
    public static String remap(String json, IntermediaryToMojangMapper mapper,
            UnaryOperator<String> selectorRepair) {
        try {
            JsonSecurity.validate(json, "Mixin refmap JSON");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean changed = false;

            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                root.add("mappings", remapSection(
                        root.getAsJsonObject("mappings"), mapper, selectorRepair));
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
                        data.add(officialKey, remapSection(
                                data.getAsJsonObject(key), mapper, selectorRepair));
                        changed = true;
                    } else if (key.equals("official") || key.endsWith(":official")) {
                        data.add(key, remapSection(
                                data.getAsJsonObject(key), mapper, selectorRepair));
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

    /**
     * Remaps a Fabric refmap and collects exact source-selector relationships for class repair.
     */
    public static RemapResult remapWithRepairs(String json, IntermediaryToMojangMapper mapper,
            MixinCompatibilityTransformer transformer) {
        if (transformer == null) {
            return new RemapResult(remap(json, mapper), MixinRefmapRepairIndex.empty());
        }
        MixinRefmapRepairIndex.Builder repairs = MixinRefmapRepairIndex.builder();
        try {
            JsonSecurity.validate(json, "Mixin refmap JSON");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean changed = false;

            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                root.add("mappings", remapSectionWithRepairs(
                        root.getAsJsonObject("mappings"), mapper, transformer, repairs));
                changed = true;
            }

            if (root.has("data") && root.get("data").isJsonObject()) {
                JsonObject data = root.getAsJsonObject("data");
                for (String key : new java.util.ArrayList<>(data.keySet())) {
                    if (!data.get(key).isJsonObject()) continue;
                    String officialKey = null;
                    if (key.equals("intermediary")) officialKey = "official";
                    else if (key.endsWith(":intermediary")) {
                        officialKey = key.substring(0,
                                key.length() - ":intermediary".length()) + ":official";
                    }
                    if (officialKey != null && !data.has(officialKey)) {
                        data.add(officialKey, remapSectionWithRepairs(
                                data.getAsJsonObject(key), mapper, transformer, repairs));
                        changed = true;
                    } else if (key.equals("official") || key.endsWith(":official")) {
                        data.add(key, remapSectionWithRepairs(
                                data.getAsJsonObject(key), mapper, transformer, repairs));
                        changed = true;
                    }
                }
            }

            String output = changed
                    ? new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                    : json;
            return new RemapResult(output, repairs.build());
        } catch (Throwable t) {
            return new RemapResult(json, MixinRefmapRepairIndex.empty());
        }
    }

    /**
     * Remap Forge selectors in every refmap section. Forge mixin annotations and their refmap
     * values are separate resources, so rewriting only the class leaves Mixin resolving the old
     * owner from the refmap.
     */
    public static String remapForgeSelectors(
            String json, MixinCompatibilityTransformer transformer) {
        try {
            JsonSecurity.validate(json, "Mixin refmap JSON");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean[] changed = {false};
            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                root.add("mappings", remapForgeSection(
                        root.getAsJsonObject("mappings"), transformer, changed));
            }
            if (root.has("data") && root.get("data").isJsonObject()) {
                JsonObject data = root.getAsJsonObject("data");
                for (String namespace : new java.util.ArrayList<>(data.keySet())) {
                    if (data.get(namespace).isJsonObject()) {
                        data.add(namespace, remapForgeSection(
                                data.getAsJsonObject(namespace), transformer, changed));
                    }
                }
            }
            if (!changed[0]) return json;
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            return gson.toJson(root);
        } catch (Throwable t) {
            return json;
        }
    }

    /** Remaps Forge selectors and collects exact source-selector relationships. */
    public static RemapResult remapForgeSelectorsWithRepairs(
            String json, MixinCompatibilityTransformer transformer) {
        try {
            JsonSecurity.validate(json, "Mixin refmap JSON");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean[] changed = {false};
            MixinRefmapRepairIndex.Builder repairs = MixinRefmapRepairIndex.builder();
            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                root.add("mappings", remapForgeSectionWithRepairs(
                        root.getAsJsonObject("mappings"), transformer, changed, repairs));
            }
            if (root.has("data") && root.get("data").isJsonObject()) {
                JsonObject data = root.getAsJsonObject("data");
                for (String namespace : new java.util.ArrayList<>(data.keySet())) {
                    if (data.get(namespace).isJsonObject()) {
                        data.add(namespace, remapForgeSectionWithRepairs(
                                data.getAsJsonObject(namespace), transformer, changed, repairs));
                    }
                }
            }
            String output = changed[0]
                    ? new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                    : json;
            return new RemapResult(output, repairs.build());
        } catch (Throwable t) {
            return new RemapResult(json, MixinRefmapRepairIndex.empty());
        }
    }

    private static JsonObject remapForgeSection(JsonObject section,
            MixinCompatibilityTransformer transformer, boolean[] changed) {
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
                String remappedValue = transformer.remapResourceSelector(value);
                String sourceMember = memberPart(value);
                String remappedKey = key.equals(sourceMember) ? memberPart(remappedValue) : key;
                remappedEntries.addProperty(remappedKey, remappedValue);
                changed[0] |= !key.equals(remappedKey) || !value.equals(remappedValue);
            }
            result.add(mixinClassName, remappedEntries);
        }
        return result;
    }

    private static JsonObject remapForgeSectionWithRepairs(JsonObject section,
            MixinCompatibilityTransformer transformer, boolean[] changed,
            MixinRefmapRepairIndex.Builder repairs) {
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
                MixinCompatibilityTransformer.ResourceSelectorResult planned =
                        transformer.remapResourceSelectorWithRepair(value);
                String remappedValue = planned.selector();
                String sourceMember = memberPart(value);
                String remappedKey = key.equals(sourceMember) ? memberPart(remappedValue) : key;
                remappedEntries.addProperty(remappedKey, remappedValue);
                changed[0] |= !key.equals(remappedKey) || !value.equals(remappedValue);
                recordRepair(repairs, mixinClassName, key, planned.repair());
            }
            result.add(mixinClassName, remappedEntries);
        }
        return result;
    }

    private static String memberPart(String selector) {
        if (selector == null || !selector.startsWith("L")) return selector;
        int ownerEnd = selector.indexOf(';');
        return ownerEnd >= 0 ? selector.substring(ownerEnd + 1) : selector;
    }

    /** Replace intermediary names with Mojang names throughout a refmap section (keys AND values). */
    private static JsonObject remapSection(JsonObject section, IntermediaryToMojangMapper mapper,
            UnaryOperator<String> selectorRepair) {
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
                String remappedKey = mapper.remapString(key);
                String remappedValue = mapper.remapString(value);
                String repairedValue = selectorRepair != null
                        ? selectorRepair.apply(remappedValue) : remappedValue;

                // A descriptor-qualified annotation is also rewritten in the class. Move its
                // lookup key only when it exactly mirrored the old value's member selector.
                if (remappedKey.equals(memberPart(remappedValue))) {
                    remappedKey = memberPart(repairedValue);
                }
                remappedEntries.addProperty(remappedKey, repairedValue);
            }
            result.add(mixinClassName, remappedEntries);
        }
        return result;
    }

    private static JsonObject remapSectionWithRepairs(JsonObject section,
            IntermediaryToMojangMapper mapper, MixinCompatibilityTransformer transformer,
            MixinRefmapRepairIndex.Builder repairs) {
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
                String remappedKey = mapper.remapString(key);
                String remappedValue = mapper.remapString(value);
                MixinCompatibilityTransformer.ResourceSelectorResult planned =
                        transformer.remapResourceSelectorWithRepair(remappedValue);
                String repairedValue = planned.selector();

                if (remappedKey.equals(memberPart(remappedValue))) {
                    remappedKey = memberPart(repairedValue);
                }
                remappedEntries.addProperty(remappedKey, repairedValue);
                recordRepair(repairs, mixinClassName, key, planned.repair());
            }
            result.add(mixinClassName, remappedEntries);
        }
        return result;
    }

    private static void recordRepair(MixinRefmapRepairIndex.Builder repairs,
            String mixinClassName, String sourceSelector,
            com.retromod.mixin.AutomaticMixinTranslator.ResourceSelectorRepair repair) {
        if (repair == null) return;
        repairs.put(mixinClassName, sourceSelector, new MixinRefmapRepairIndex.Repair(
                repair.targetOwner(), repair.oldTargetDescriptor(),
                repair.newTargetDescriptor(), repair.targetAccess(), repair.insertions()));
    }
}
