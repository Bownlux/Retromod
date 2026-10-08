/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Moves the 1.19.2 registry key constants to their 1.19.3 home.
 *
 * <p>Up to 1.19.2 every registry key lived on {@code Registry} as {@code X_REGISTRY}, for example
 * {@code Registry.DIMENSION_REGISTRY}. 1.19.3 moved them all to {@code Registries.X} and deleted the
 * old fields, so a mod that builds a {@code ResourceKey} for its dimension or biome dies with
 * {@code NoSuchFieldError} (#290). The value is the same key; only the owner and name changed.
 */
public final class LegacyRegistryKeyMoves {

    private static final String OLD_OWNER = "net/minecraft/core/Registry";
    private static final String NEW_OWNER = "net/minecraft/core/registries/Registries";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";

    /** Registry keys whose new name is the old one without the {@code _REGISTRY} suffix. */
    private static final String[] SAME_STEM = {
            "ACTIVITY", "ATTRIBUTE", "BANNER_PATTERN", "BIOME", "BIOME_SOURCE", "BLOCK",
            "BLOCK_ENTITY_TYPE", "BLOCK_PREDICATE_TYPE", "BLOCK_STATE_PROVIDER_TYPE", "CARVER",
            "CAT_VARIANT", "CHAT_TYPE", "CHUNK_GENERATOR", "CHUNK_STATUS", "COMMAND_ARGUMENT_TYPE",
            "CONFIGURED_CARVER", "CONFIGURED_FEATURE", "CUSTOM_STAT", "DENSITY_FUNCTION",
            "DENSITY_FUNCTION_TYPE", "DIMENSION", "DIMENSION_TYPE", "ENCHANTMENT", "ENTITY_TYPE",
            "FEATURE", "FEATURE_SIZE_TYPE", "FLAT_LEVEL_GENERATOR_PRESET", "FLOAT_PROVIDER_TYPE",
            "FLUID", "FOLIAGE_PLACER_TYPE", "FROG_VARIANT", "GAME_EVENT", "HEIGHT_PROVIDER_TYPE",
            "INSTRUMENT", "INT_PROVIDER_TYPE", "ITEM", "LEVEL_STEM", "MEMORY_MODULE_TYPE", "MENU",
            "MOB_EFFECT", "NOISE", "PAINTING_VARIANT", "PARTICLE_TYPE", "PLACED_FEATURE",
            "POINT_OF_INTEREST_TYPE", "POS_RULE_TEST", "POSITION_SOURCE_TYPE", "POTION",
            "PROCESSOR_LIST", "RECIPE_SERIALIZER", "RECIPE_TYPE", "ROOT_PLACER_TYPE", "RULE_TEST",
            "SCHEDULE", "SENSOR_TYPE", "SOUND_EVENT", "STAT_TYPE", "STRUCTURE_PIECE",
            "STRUCTURE_POOL_ELEMENT", "STRUCTURE_PROCESSOR", "STRUCTURE", "STRUCTURE_SET",
            "STRUCTURE_TYPE", "TEMPLATE_POOL", "TREE_DECORATOR_TYPE", "TRUNK_PLACER_TYPE",
            "VILLAGER_PROFESSION", "VILLAGER_TYPE", "WORLD_PRESET",
    };

    /**
     * Built-in registries that 1.19.3 moved from {@code Registry} to {@code BuiltInRegistries}
     * under the same name, such as {@code Registry.PARTICLE_TYPE}. The registry object is the same.
     */
    static final String[] BUILT_IN_SAME_NAME = {
            "ACTIVITY", "ATTRIBUTE", "BANNER_PATTERN", "BIOME_SOURCE", "BLOCK", "BLOCK_ENTITY_TYPE",
            "CARVER", "CAT_VARIANT", "CHUNK_GENERATOR", "CHUNK_STATUS", "COMMAND_ARGUMENT_TYPE",
            "CUSTOM_STAT", "ENCHANTMENT", "ENTITY_TYPE", "FEATURE", "FLUID", "FROG_VARIANT",
            "GAME_EVENT", "INSTRUMENT", "ITEM", "LOOT_CONDITION_TYPE", "LOOT_FUNCTION_TYPE",
            "LOOT_NBT_PROVIDER_TYPE", "LOOT_NUMBER_PROVIDER_TYPE", "LOOT_POOL_ENTRY_TYPE",
            "LOOT_SCORE_PROVIDER_TYPE", "MEMORY_MODULE_TYPE", "MENU", "MOB_EFFECT",
            "PAINTING_VARIANT", "PARTICLE_TYPE", "POINT_OF_INTEREST_TYPE", "POSITION_SOURCE_TYPE",
            "POS_RULE_TEST", "POTION", "RECIPE_SERIALIZER", "RECIPE_TYPE", "RULE_TEST", "SCHEDULE",
            "SENSOR_TYPE", "SOUND_EVENT", "STAT_TYPE", "STRUCTURE_PIECE", "STRUCTURE_POOL_ELEMENT",
            "STRUCTURE_PROCESSOR", "VILLAGER_PROFESSION", "VILLAGER_TYPE",
    };
    private static final String BUILT_IN_OWNER = "net/minecraft/core/registries/BuiltInRegistries";

    private LegacyRegistryKeyMoves() {}

    /** Old {@code Registry} field name to new {@code Registries} field name. */
    static Map<String, String> moves() {
        Map<String, String> moves = new LinkedHashMap<>();
        for (String stem : SAME_STEM) moves.put(stem + "_REGISTRY", stem);
        // The keys 1.19.3 renamed as well as moved.
        moves.put("NOISE_GENERATOR_SETTINGS_REGISTRY", "NOISE_SETTINGS");
        moves.put("CONDITION_REGISTRY", "MATERIAL_CONDITION");
        moves.put("RULE_REGISTRY", "MATERIAL_RULE");
        moves.put("PLACEMENT_MODIFIER_REGISTRY", "PLACEMENT_MODIFIER_TYPE");
        moves.put("STRUCTURE_PLACEMENT_TYPE_REGISTRY", "STRUCTURE_PLACEMENT");
        moves.put("LOOT_ENTRY_REGISTRY", "LOOT_POOL_ENTRY_TYPE");
        moves.put("LOOT_FUNCTION_REGISTRY", "LOOT_FUNCTION_TYPE");
        moves.put("LOOT_ITEM_REGISTRY", "LOOT_CONDITION_TYPE");
        moves.put("LOOT_NUMBER_PROVIDER_REGISTRY", "LOOT_NUMBER_PROVIDER_TYPE");
        moves.put("LOOT_NBT_PROVIDER_REGISTRY", "LOOT_NBT_PROVIDER_TYPE");
        moves.put("LOOT_SCORE_PROVIDER_REGISTRY", "LOOT_SCORE_PROVIDER_TYPE");
        return moves;
    }

    public static void register(RetromodTransformer transformer) {
        for (Map.Entry<String, String> move : moves().entrySet()) {
            transformer.registerFieldRedirect(OLD_OWNER, move.getKey(), KEY,
                    NEW_OWNER, move.getValue(), KEY);
        }
        for (String name : BUILT_IN_SAME_NAME) {
            transformer.registerFieldRedirect(OLD_OWNER, name, BUILT_IN_OWNER, name);
        }
    }
}
