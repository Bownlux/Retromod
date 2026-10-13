/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.retromod.core.RetromodVersion;

import java.util.List;

/**
 * Rewrites a mod's dimension types for the environment attributes of 1.21.11 and newer.
 *
 * <p>Up to 1.21.10 a dimension type carried gameplay flags such as {@code bed_works},
 * {@code ultrawarm}, {@code piglin_safe}, and {@code has_raids}, a fixed time, and an
 * {@code effects} id for its sky. 1.21.11 moved the flags into {@code attributes}, the sky into
 * {@code skybox} and {@code cardinal_light}, and time into clocks and timelines, and made
 * {@code has_ender_dragon_fight} required. An old mod dimension then failed to parse, which stops
 * the whole registry load and every world with it. 26.3 also renamed the bed rule's
 * {@code explodes} to {@code destroy_on_use}.
 *
 * <p>Each old flag becomes the attribute that does the same thing, and the dimension takes the
 * timeline, clock, and sky of the vanilla dimension its {@code effects} named. Fog, sky, and
 * cloud colors and the music are not carried over, so an old dimension keeps the default look.
 */
public final class DimensionTypeMigrator {

    private static final List<String> KEPT = List.of("ambient_light", "coordinate_scale", "has_ceiling",
            "has_skylight", "height", "infiniburn", "logical_height", "min_y", "monster_spawn_block_light_limit",
            "monster_spawn_light_level");
    private static final List<String> OLD_FLAGS = List.of("effects", "bed_works", "ultrawarm", "piglin_safe",
            "has_raids", "respawn_anchor_works", "natural", "fixed_time");

    private DimensionTypeMigrator() {}

    static boolean isDimensionType(String entryName) {
        return entryName.startsWith("data/") && entryName.contains("/dimension_type/") && entryName.endsWith(".json");
    }

    /** The file rewritten for the target, or the same array when nothing changed. */
    static byte[] migrate(byte[] json, String targetMcVersion) {
        JsonObject before;
        try {
            before = com.google.gson.JsonParser.parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException unparsable) {
            return json;
        }
        JsonObject after = migrate(before.deepCopy(), targetMcVersion);
        if (after.equals(before)) return json;
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(after)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The dimension type in the target's shape; may be {@code type}, changed in place. */
    static JsonObject migrate(JsonObject type, String targetMcVersion) {
        boolean destroyOnUse = RetromodVersion.compareMcVersions(targetMcVersion, "26.3") >= 0;
        if (!type.has("attributes") && OLD_FLAGS.stream().anyMatch(type::has)) {
            return fromFlags(type, destroyOnUse);
        }
        if (destroyOnUse && type.get("attributes") instanceof JsonObject attributes) {
            renameExplodes(attributes, "minecraft:gameplay/bed_rule");
            renameExplodes(attributes, "minecraft:gameplay/straw_bed_rule");
        }
        return type;
    }

    private static JsonObject fromFlags(JsonObject old, boolean destroyOnUse) {
        JsonObject type = new JsonObject();
        for (String key : KEPT) {
            if (old.has(key)) type.add(key, old.get(key));
        }
        type.addProperty("has_ender_dragon_fight", false);
        boolean fixedTime = old.has("fixed_time");
        if (fixedTime) type.addProperty("has_fixed_time", true);

        String effects = old.has("effects") ? old.get("effects").getAsString() : "minecraft:overworld";
        switch (effects) {
            case "minecraft:the_nether" -> {
                type.addProperty("skybox", "none");
                type.addProperty("cardinal_light", "nether");
                type.addProperty("timelines", "#minecraft:in_nether");
            }
            case "minecraft:the_end" -> {
                type.addProperty("skybox", "end");
                type.addProperty("default_clock", "minecraft:the_end");
                type.addProperty("timelines", "#minecraft:in_end");
            }
            default -> {
                if (!fixedTime) {
                    type.addProperty("default_clock", "minecraft:overworld");
                    type.addProperty("timelines", "#minecraft:in_overworld");
                }
            }
        }

        JsonObject attributes = new JsonObject();
        if (old.has("bed_works")) attributes.add("minecraft:gameplay/bed_rule", bedRule(isTrue(old, "bed_works"), destroyOnUse));
        if (old.has("respawn_anchor_works")) {
            attributes.addProperty("minecraft:gameplay/respawn_anchor_works", isTrue(old, "respawn_anchor_works"));
        }
        if (old.has("has_raids") && !isTrue(old, "has_raids")) {
            attributes.addProperty("minecraft:gameplay/can_start_raid", false);
        }
        if (isTrue(old, "piglin_safe")) attributes.addProperty("minecraft:gameplay/piglins_zombify", false);
        if (isTrue(old, "ultrawarm")) {
            attributes.addProperty("minecraft:gameplay/water_evaporates", true);
            attributes.addProperty("minecraft:gameplay/fast_lava", true);
        }
        if (isTrue(old, "natural")) attributes.addProperty("minecraft:gameplay/nether_portal_spawns_piglin", true);
        type.add("attributes", attributes);
        return type;
    }

    private static JsonObject bedRule(boolean bedWorks, boolean destroyOnUse) {
        JsonObject rule = new JsonObject();
        if (bedWorks) {
            rule.addProperty("can_set_spawn", "always");
            rule.addProperty("can_sleep", "when_dark");
        } else {
            rule.addProperty("can_set_spawn", "never");
            rule.addProperty("can_sleep", "never");
            rule.addProperty(destroyOnUse ? "destroy_on_use" : "explodes", true);
        }
        return rule;
    }

    private static void renameExplodes(JsonObject attributes, String rule) {
        if (attributes.get(rule) instanceof JsonObject bedRule && bedRule.has("explodes")
                && !bedRule.has("destroy_on_use")) {
            bedRule.add("destroy_on_use", bedRule.remove("explodes"));
        }
    }

    private static boolean isTrue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }
}
