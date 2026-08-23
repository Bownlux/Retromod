/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Transforms Resource Packs (texture packs) to work on newer Minecraft versions.
 * 
 * What changes between versions:
 * - pack.mcmeta "pack_format" number
 * - Some texture paths (renamed blocks/items)
 * - Some JSON model formats
 * - Some sound paths
 * 
 * Pack Format History:
 * - 1: 1.6.1 - 1.8.9
 * - 2: 1.9 - 1.10.2
 * - 3: 1.11 - 1.12.2
 * - 4: 1.13 - 1.14.4
 * - 5: 1.15 - 1.16.1
 * - 6: 1.16.2 - 1.16.5
 * - 7: 1.17 - 1.17.1
 * - 8: 1.18 - 1.18.2
 * - 9: 1.19 - 1.19.2
 * - 12: 1.19.3
 * - 13: 1.19.4
 * - 15: 1.20 - 1.20.1
 * - 18: 1.20.2
 * - 22: 1.20.3 - 1.20.4
 * - 32: 1.20.5 - 1.20.6
 * - 34: 1.21 - 1.21.1
 * - 42: 1.21.2 - 1.21.3
 * - 46: 1.21.4
 * - 55: 1.21.5
 * - 63: 1.21.6
 * - 64: 1.21.7 - 1.21.8
 * - 69.0+: 1.21.9 and newer full-version metadata
 */
public class ResourcePackTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    
    // Texture path renames between versions (old -> new)
    private static final Map<String, String> TEXTURE_RENAMES_BLOCKS = new HashMap<>();
    static {
        // 1.13 flattening renames
        // initial set, partially corrected from original commit
        TEXTURE_RENAMES_BLOCKS.put("grass_side", "grass_block_side");
        TEXTURE_RENAMES_BLOCKS.put("grass_top", "grass_block_top");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay", "terracotta");
        TEXTURE_RENAMES_BLOCKS.put("stone_slab_top", "smooth_stone");
        TEXTURE_RENAMES_BLOCKS.put("stone_slab_side", "smooth_stone_slab_side");
        TEXTURE_RENAMES_BLOCKS.put("mob_spawner", "spawner");
        TEXTURE_RENAMES_BLOCKS.put("noteblock", "note_block");
        TEXTURE_RENAMES_BLOCKS.put("comparator_off", "comparator");
        TEXTURE_RENAMES_BLOCKS.put("repeater_off", "repeater");

        // other state/orientation based textures
        TEXTURE_RENAMES_BLOCKS.put("grass_side_overlay", "grass_block_side_overlay");
        TEXTURE_RENAMES_BLOCKS.put("grass_side_snowed", "grass_block_snow");
        TEXTURE_RENAMES_BLOCKS.put("comparator_off", "comparator");
        TEXTURE_RENAMES_BLOCKS.put("dispenser_front_horizontal", "dispenser_front");
        TEXTURE_RENAMES_BLOCKS.put("dropper_front_horizontal", "dropper_front");
        TEXTURE_RENAMES_BLOCKS.put("endframe_eye", "end_portal_frame_eye");
        TEXTURE_RENAMES_BLOCKS.put("endframe_side", "end_portal_frame_side");
        TEXTURE_RENAMES_BLOCKS.put("endframe_top", "end_portal_frame_top");
        TEXTURE_RENAMES_BLOCKS.put("farmland_wet", "farmland_moist");
        TEXTURE_RENAMES_BLOCKS.put("farmland_dry", "farmland");
        TEXTURE_RENAMES_BLOCKS.put("furnace_front_off", "furnace_front");
        TEXTURE_RENAMES_BLOCKS.put("observer_back_lit", "observer_back_on");
        TEXTURE_RENAMES_BLOCKS.put("piston_top_normal", "piston_top");
        TEXTURE_RENAMES_BLOCKS.put("pumpkin_face_off", "carved_pumpkin");
        TEXTURE_RENAMES_BLOCKS.put("pumpkin_face_on", "jack_o_lantern");
        TEXTURE_RENAMES_BLOCKS.put("redstone_lamp_off", "redstone_lamp");
        TEXTURE_RENAMES_BLOCKS.put("torch_on", "torch");

        // rails
        TEXTURE_RENAMES_BLOCKS.put("rail_activator", "activator_rail");
        TEXTURE_RENAMES_BLOCKS.put("rail_activator_powered", "activator_rail_on");
        TEXTURE_RENAMES_BLOCKS.put("rail_detector", "detector_rail");
        TEXTURE_RENAMES_BLOCKS.put("rail_detector_powered", "detector_rail_on");
        TEXTURE_RENAMES_BLOCKS.put("rail_golden", "golden_rail");
        TEXTURE_RENAMES_BLOCKS.put("rail_golden_powered", "golden_rail_on");
        TEXTURE_RENAMES_BLOCKS.put("rail_normal", "rail");
        TEXTURE_RENAMES_BLOCKS.put("rail_normal_turned", "rail_corner");

        // wool colors
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_black", "black_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_blue", "blue_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_brown", "brown_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_cyan", "cyan_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_gray", "gray_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_green", "green_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_light_blue", "light_blue_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_lime", "lime_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_magenta", "magenta_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_orange", "orange_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_pink", "pink_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_purple", "purple_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_red", "red_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_silver", "light_gray_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_white", "white_wool");
        TEXTURE_RENAMES_BLOCKS.put("wool_colored_yellow", "yellow_wool");

        // crops
        TEXTURE_RENAMES_BLOCKS.put("beetroots_stage_0", "beetroots_stage0");
        TEXTURE_RENAMES_BLOCKS.put("beetroots_stage_1", "beetroots_stage1");
        TEXTURE_RENAMES_BLOCKS.put("beetroots_stage_2", "beetroots_stage2");
        TEXTURE_RENAMES_BLOCKS.put("beetroots_stage_3", "beetroots_stage3");
        TEXTURE_RENAMES_BLOCKS.put("carrots_stage_0", "carrots_stage0");
        TEXTURE_RENAMES_BLOCKS.put("carrots_stage_1", "carrots_stage1");
        TEXTURE_RENAMES_BLOCKS.put("carrots_stage_2", "carrots_stage2");
        TEXTURE_RENAMES_BLOCKS.put("carrots_stage_3", "carrots_stage3");
        TEXTURE_RENAMES_BLOCKS.put("cocoa_stage_0", "cocoa_stage0");
        TEXTURE_RENAMES_BLOCKS.put("cocoa_stage_1", "cocoa_stage1");
        TEXTURE_RENAMES_BLOCKS.put("cocoa_stage_2", "cocoa_stage2");
        TEXTURE_RENAMES_BLOCKS.put("nether_wart_stage_0", "nether_wart_stage0");
        TEXTURE_RENAMES_BLOCKS.put("nether_wart_stage_1", "nether_wart_stage1");
        TEXTURE_RENAMES_BLOCKS.put("nether_wart_stage_2", "nether_wart_stage2");
        TEXTURE_RENAMES_BLOCKS.put("potatoes_stage_0", "potatoes_stage0");
        TEXTURE_RENAMES_BLOCKS.put("potatoes_stage_1", "potatoes_stage1");
        TEXTURE_RENAMES_BLOCKS.put("potatoes_stage_2", "potatoes_stage2");
        TEXTURE_RENAMES_BLOCKS.put("potatoes_stage_3", "potatoes_stage3");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_0", "wheat_stage0");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_1", "wheat_stage1");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_2", "wheat_stage2");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_3", "wheat_stage3");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_4", "wheat_stage4");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_5", "wheat_stage5");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_6", "wheat_stage6");
        TEXTURE_RENAMES_BLOCKS.put("wheat_stage_7", "wheat_stage7");

        // stems
        TEXTURE_RENAMES_BLOCKS.put("melon_stem_connected", "attached_melon_stem");
        TEXTURE_RENAMES_BLOCKS.put("melon_stem_disconnected", "melon_stem");
        TEXTURE_RENAMES_BLOCKS.put("pumpkin_stem_connected", "attached_pumpkin_stem");
        TEXTURE_RENAMES_BLOCKS.put("pumpkin_stem_disconnected", "pumpkin_stem");

        // plants and flowers
        TEXTURE_RENAMES_BLOCKS.put("deadbush", "deadbush");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_fern_bottom", "large_fern_bottom");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_fern_top", "large_fern_top");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_grass_bottom", "tall_grass_bottom");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_grass_top", "tall_grass_top");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_paeonia_bottom", "peony_bottom");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_paeonia_top", "peony_top");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_rose_bottom", "rose_bush_bottom");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_rose_top", "rose_bush_top");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_sunflower_back", "sunflower_back");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_sunflower_bottom", "sunflower_bottom");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_sunflower_front", "sunflower_front");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_sunflower_top", "sunflower_top");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_syringa_bottom", "lilac_bottom");
        TEXTURE_RENAMES_BLOCKS.put("double_plant_syringa_top", "lilac_top");
        TEXTURE_RENAMES_BLOCKS.put("flower_allium", "allium");
        TEXTURE_RENAMES_BLOCKS.put("flower_blue_orchid", "blue_orchid");
        TEXTURE_RENAMES_BLOCKS.put("flower_dandelion", "dandelion");
        TEXTURE_RENAMES_BLOCKS.put("flower_houstonia", "azure_bluet");
        TEXTURE_RENAMES_BLOCKS.put("flower_oxeye_daisy", "oxeye_daisy");
        TEXTURE_RENAMES_BLOCKS.put("flower_rose", "poppy");
        TEXTURE_RENAMES_BLOCKS.put("flower_tulip_orange", "orange_tulip");
        TEXTURE_RENAMES_BLOCKS.put("flower_tulip_pink", "pink_tulip");
        TEXTURE_RENAMES_BLOCKS.put("flower_tulip_red", "red_tulip");
        TEXTURE_RENAMES_BLOCKS.put("flower_tulip_white", "white_tulip");
        TEXTURE_RENAMES_BLOCKS.put("mushroom_brown", "brown_mushroom");
        TEXTURE_RENAMES_BLOCKS.put("mushroom_red", "red_mushroom");
        TEXTURE_RENAMES_BLOCKS.put("reeds", "sugar_cane"); // both item and block form
        TEXTURE_RENAMES_BLOCKS.put("tallgrass", "grass");
        TEXTURE_RENAMES_BLOCKS.put("waterlily", "lily_pad");

        // anvils
        TEXTURE_RENAMES_BLOCKS.put("anvil_base", "anvil");
        TEXTURE_RENAMES_BLOCKS.put("anvil_top_damaged_0", "anvil_top");
        TEXTURE_RENAMES_BLOCKS.put("anvil_top_damaged_1", "chipped_anvil_top");
        TEXTURE_RENAMES_BLOCKS.put("anvil_top_damaged_2", "damaged_anvil_top");

        // wood stuff (doors, logs, planks, leaves and saplings)
        TEXTURE_RENAMES_BLOCKS.put("door_acacia_lower", "acacia_door_bottom");
        TEXTURE_RENAMES_BLOCKS.put("door_acacia_upper", "acacia_door_top");
        TEXTURE_RENAMES_BLOCKS.put("door_birch_lower", "birch_door_bottom");
        TEXTURE_RENAMES_BLOCKS.put("door_birch_upper", "birch_door_top");
        TEXTURE_RENAMES_BLOCKS.put("door_dark_oak_lower", "dark_oak_door_bottom");
        TEXTURE_RENAMES_BLOCKS.put("door_dark_oak_upper", "dark_oak_door_top");
        TEXTURE_RENAMES_BLOCKS.put("door_iron_lower", "iron_door_bottom");
        TEXTURE_RENAMES_BLOCKS.put("door_iron_upper", "iron_door_top");
        TEXTURE_RENAMES_BLOCKS.put("door_jungle_lower", "jungle_door_bottom");
        TEXTURE_RENAMES_BLOCKS.put("door_jungle_upper", "jungle_door_top");
        TEXTURE_RENAMES_BLOCKS.put("door_spruce_lower", "spruce_door_bottom");
        TEXTURE_RENAMES_BLOCKS.put("door_spruce_upper", "spruce_door_top");
        TEXTURE_RENAMES_BLOCKS.put("door_wood_lower", "oak_door_bottom");
        TEXTURE_RENAMES_BLOCKS.put("door_wood_upper", "oak_door_top");
        TEXTURE_RENAMES_BLOCKS.put("leaves_acacia", "acacia_leaves");
        TEXTURE_RENAMES_BLOCKS.put("leaves_big_oak", "dark_oak_leaves");
        TEXTURE_RENAMES_BLOCKS.put("leaves_birch", "birch_leaves");
        TEXTURE_RENAMES_BLOCKS.put("leaves_jungle", "jungle_leaves");
        TEXTURE_RENAMES_BLOCKS.put("leaves_oak", "oak_leaves");
        TEXTURE_RENAMES_BLOCKS.put("leaves_spruce", "spruce_leaves");
        TEXTURE_RENAMES_BLOCKS.put("log_acacia", "acacia_log");
        TEXTURE_RENAMES_BLOCKS.put("log_acacia_top", "acacia_log_top");
        TEXTURE_RENAMES_BLOCKS.put("log_big_oak", "dark_oak_log");
        TEXTURE_RENAMES_BLOCKS.put("log_big_oak_top", "dark_oak_log_top");
        TEXTURE_RENAMES_BLOCKS.put("log_birch", "birch_log");
        TEXTURE_RENAMES_BLOCKS.put("log_birch_top", "birch_log_top");
        TEXTURE_RENAMES_BLOCKS.put("log_jungle", "jungle_log");
        TEXTURE_RENAMES_BLOCKS.put("log_jungle_top", "jungle_log_top");
        TEXTURE_RENAMES_BLOCKS.put("log_oak", "oak_log");
        TEXTURE_RENAMES_BLOCKS.put("log_oak_top", "oak_log_top");
        TEXTURE_RENAMES_BLOCKS.put("log_spruce", "spruce_log");
        TEXTURE_RENAMES_BLOCKS.put("log_spruce_top", "spruce_log_top");
        TEXTURE_RENAMES_BLOCKS.put("planks_acacia", "acacia_planks");
        TEXTURE_RENAMES_BLOCKS.put("planks_big_oak", "dark_oak_planks");
        TEXTURE_RENAMES_BLOCKS.put("planks_birch", "birch_planks");
        TEXTURE_RENAMES_BLOCKS.put("planks_jungle", "jungle_planks");
        TEXTURE_RENAMES_BLOCKS.put("planks_oak", "oak_planks");
        TEXTURE_RENAMES_BLOCKS.put("planks_spruce", "spruce_planks");
        TEXTURE_RENAMES_BLOCKS.put("sapling_acacia", "acacia_sapling");
        TEXTURE_RENAMES_BLOCKS.put("sapling_birch", "birch_sapling");
        TEXTURE_RENAMES_BLOCKS.put("sapling_jungle", "jungle_sapling");
        TEXTURE_RENAMES_BLOCKS.put("sapling_oak", "oak_sapling");
        TEXTURE_RENAMES_BLOCKS.put("sapling_roofed_oak", "dark_oak_sapling");
        TEXTURE_RENAMES_BLOCKS.put("sapling_spruce", "spruce_sapling");

        // animated textures
        TEXTURE_RENAMES_BLOCKS.put("fire_layer_0", "fire_0");
        TEXTURE_RENAMES_BLOCKS.put("fire_layer_1", "fire_1");
        TEXTURE_RENAMES_BLOCKS.put("portal", "nether_portal");

        // terracotta color variants
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_black", "black_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_blue", "blue_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_brown", "brown_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_cyan", "cyan_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_gray", "gray_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_green", "green_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_light_blue", "light_blue_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_lime", "lime_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_magenta", "magenta_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_orange", "orange_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_pink", "pink_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_purple", "purple_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_red", "red_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_silver", "light_gray_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_white", "white_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("hardened_clay_stained_yellow", "yellow_terracotta");

        // glazed terracotta
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_black", "black_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_blue", "blue_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_brown", "brown_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_cyan", "cyan_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_gray", "gray_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_green", "green_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_light_blue", "light_blue_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_lime", "lime_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_magenta", "magenta_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_orange", "orange_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_pink", "pink_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_purple", "purple_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_red", "red_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_silver", "light_gray_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_white", "white_glazed_terracotta");
        TEXTURE_RENAMES_BLOCKS.put("glazed_terracotta_yellow", "yellow_glazed_terracotta");

        // concrete
        TEXTURE_RENAMES_BLOCKS.put("concrete_black", "black_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_blue", "blue_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_brown", "brown_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_cyan", "cyan_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_gray", "gray_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_green", "green_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_light_blue", "light_blue_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_lime", "lime_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_magenta", "magenta_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_orange", "orange_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_pink", "pink_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_purple", "purple_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_red", "red_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_silver", "light_gray_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_white", "white_concrete");
        TEXTURE_RENAMES_BLOCKS.put("concrete_yellow", "yellow_concrete");

        // concrete powder
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_black", "black_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_blue", "blue_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_brown", "brown_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_cyan", "cyan_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_gray", "gray_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_green", "green_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_light_blue", "light_blue_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_lime", "lime_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_magenta", "magenta_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_orange", "orange_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_pink", "pink_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_purple", "purple_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_red", "red_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_silver", "light_gray_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_white", "white_concrete_powder");
        TEXTURE_RENAMES_BLOCKS.put("concrete_powder_yellow", "yellow_concrete_powder");

        // glass
        TEXTURE_RENAMES_BLOCKS.put("glass_black", "black_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_blue", "blue_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_brown", "brown_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_cyan", "cyan_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_gray", "gray_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_green", "green_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_light_blue", "light_blue_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_lime", "lime_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_magenta", "magenta_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_orange", "orange_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_pink", "pink_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_purple", "purple_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_red", "red_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_silver", "light_gray_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_white", "white_stained_glass");
        TEXTURE_RENAMES_BLOCKS.put("glass_yellow", "yellow_stained_glass");

        // glass panes
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_black", "black_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_blue", "blue_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_brown", "brown_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_cyan", "cyan_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_gray", "gray_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_green", "green_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_light_blue", "light_blue_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_lime", "lime_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_magenta", "magenta_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_orange", "orange_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_pink", "pink_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_purple", "purple_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_red", "red_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_silver", "light_gray_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_white", "white_stained_glass_pane_top");
        TEXTURE_RENAMES_BLOCKS.put("glass_pane_top_yellow", "yellow_stained_glass_pane_top");

        // Shulker boxes
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_black", "black_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_blue", "blue_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_brown", "brown_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_cyan", "cyan_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_gray", "gray_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_green", "green_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_light_blue", "light_blue_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_lime", "lime_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_magenta", "magenta_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_orange", "orange_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_pink", "pink_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_purple", "purple_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_red", "red_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_silver", "light_gray_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_white", "white_shulker_box");
        TEXTURE_RENAMES_BLOCKS.put("shulker_top_yellow", "yellow_shulker_box");

        // stone and bricks
        TEXTURE_RENAMES_BLOCKS.put("brick", "bricks");
        TEXTURE_RENAMES_BLOCKS.put("cobblestone_mossy", "mossy_cobblestone");
        TEXTURE_RENAMES_BLOCKS.put("end_bricks", "end_stone_bricks");
        TEXTURE_RENAMES_BLOCKS.put("nether_brick", "nether_bricks");
        TEXTURE_RENAMES_BLOCKS.put("prismarine_dark", "dark_prismarine");
        TEXTURE_RENAMES_BLOCKS.put("prismarine_rough", "prismarine");
        TEXTURE_RENAMES_BLOCKS.put("sandstone_carved", "chiseled_sandstone");
        TEXTURE_RENAMES_BLOCKS.put("sandstone_normal", "sandstone");
        TEXTURE_RENAMES_BLOCKS.put("sandstone_smooth", "cut_sandstone");
        TEXTURE_RENAMES_BLOCKS.put("red_sandstone_carved", "chiseled_red_sandstone");
        TEXTURE_RENAMES_BLOCKS.put("red_sandstone_normal", "red_sandstone");
        TEXTURE_RENAMES_BLOCKS.put("red_sandstone_smooth", "cut_red_sandstone");
        TEXTURE_RENAMES_BLOCKS.put("stone_andesite", "andesite");
        TEXTURE_RENAMES_BLOCKS.put("stone_andesite_smooth", "polished_andesite");
        TEXTURE_RENAMES_BLOCKS.put("stone_diorite", "diorite");
        TEXTURE_RENAMES_BLOCKS.put("stone_diorite_smooth", "polished_diorite");
        TEXTURE_RENAMES_BLOCKS.put("stone_granite", "granite");
        TEXTURE_RENAMES_BLOCKS.put("stone_granite_smooth", "granite_smooth");
        TEXTURE_RENAMES_BLOCKS.put("stonebrick", "stone_bricks");
        TEXTURE_RENAMES_BLOCKS.put("stonebrick_carved", "chiseled_stone_bricks");
        TEXTURE_RENAMES_BLOCKS.put("stonebrick_cracked", "cracked_stone_bricks");
        TEXTURE_RENAMES_BLOCKS.put("stonebrick_mossy", "mossy_stone_bricks");

        // miscellaneous block textures
        TEXTURE_RENAMES_BLOCKS.put("dirt_podzol_side", "podzol_side");
        TEXTURE_RENAMES_BLOCKS.put("dirt_podzol_top", "podzol_top");
        TEXTURE_RENAMES_BLOCKS.put("ice_packed", "packed_ice");
        TEXTURE_RENAMES_BLOCKS.put("itemframe_background", "item_frame");
        TEXTURE_RENAMES_BLOCKS.put("mushroom_block_skin_brown", "brown_mushroom_block");
        TEXTURE_RENAMES_BLOCKS.put("mushroom_block_skin_red", "red_mushroom_block");
        TEXTURE_RENAMES_BLOCKS.put("mushroom_block_skin_stem", "mushroom_stem");
        TEXTURE_RENAMES_BLOCKS.put("quartz_block_chiseled", "chiseled_quartz_block");
        TEXTURE_RENAMES_BLOCKS.put("quartz_block_chiseled_top", "chiseled_quartz_block_top");
        TEXTURE_RENAMES_BLOCKS.put("quartz_block_lines", "quartz_pillar");
        TEXTURE_RENAMES_BLOCKS.put("quartz_block_lines_top", "quartz_pillar_top");
        TEXTURE_RENAMES_BLOCKS.put("quartz_ore", "nether_quartz_ore");
        TEXTURE_RENAMES_BLOCKS.put("slime", "slime_block");
        TEXTURE_RENAMES_BLOCKS.put("sponge_wet", "wet_sponge");
        TEXTURE_RENAMES_BLOCKS.put("trapdoor", "oak_trapdoor");
        TEXTURE_RENAMES_BLOCKS.put("trip_wire", "tripwire");
        TEXTURE_RENAMES_BLOCKS.put("trip_wire_source", "tripwire_hook");
        TEXTURE_RENAMES_BLOCKS.put("web", "cobweb");

        TEXTURE_RENAMES_BLOCKS.put("workbench", "crafting_table");
        TEXTURE_RENAMES_BLOCKS.put("redstone_torch_on", "redstone_torch");
        // Add more as needed
    }
    private static final Map<String, String> TEXTURE_RENAMES_ITEMS = new HashMap<>();
    static {
        // item textures
        TEXTURE_RENAMES_ITEMS.put("apple_golden", "golden_apple");
        TEXTURE_RENAMES_ITEMS.put("beef_cooked", "cooked_beef");
        TEXTURE_RENAMES_ITEMS.put("beef_raw", "beef");
        TEXTURE_RENAMES_ITEMS.put("book_enchanted", "enchanted_book");
        TEXTURE_RENAMES_ITEMS.put("book_normal", "book");
        TEXTURE_RENAMES_ITEMS.put("book_writable", "writable_book");
        TEXTURE_RENAMES_ITEMS.put("book_written", "written_book");
        TEXTURE_RENAMES_ITEMS.put("bow_standby", "bow");
        TEXTURE_RENAMES_ITEMS.put("bucket_empty", "bucket");
        TEXTURE_RENAMES_ITEMS.put("bucket_lava", "lava_bucket");
        TEXTURE_RENAMES_ITEMS.put("bucket_milk", "milk_bucket");
        TEXTURE_RENAMES_ITEMS.put("bucket_water", "water_bucket");
        TEXTURE_RENAMES_ITEMS.put("carrot_golden", "golden_carrot");
        TEXTURE_RENAMES_ITEMS.put("chicken_cooked", "cooked_chicken");
        TEXTURE_RENAMES_ITEMS.put("chicken_raw", "chicken");
        TEXTURE_RENAMES_ITEMS.put("chorus_fruit_popped", "popped_chorus_fruit");
        TEXTURE_RENAMES_ITEMS.put("door_acacia", "acacia_door");
        TEXTURE_RENAMES_ITEMS.put("door_birch", "birch_door");
        TEXTURE_RENAMES_ITEMS.put("door_dark_oak", "dark_oak_door");
        TEXTURE_RENAMES_ITEMS.put("door_iron", "iron_door");
        TEXTURE_RENAMES_ITEMS.put("door_jungle", "jungle_door");
        TEXTURE_RENAMES_ITEMS.put("door_spruce", "spruce_door");
        TEXTURE_RENAMES_ITEMS.put("door_wood", "oak_door");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_black", "ink_sac");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_blue", "lapis_lazuli");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_brown", "cocoa_beans");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_cyan", "cyan_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_gray", "gray_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_green", "cactus_green");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_light_blue", "light_blue_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_lime", "lime_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_magenta", "magenta_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_orange", "orange_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_pink", "rose_red");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_purple", "purple_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_red", "rose_red");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_silver", "light_gray_dye");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_white", "bone_meal");
        TEXTURE_RENAMES_ITEMS.put("dye_powder_yellow", "dandelion_yellow");
        TEXTURE_RENAMES_ITEMS.put("fireball", "fire_charge");
        TEXTURE_RENAMES_ITEMS.put("fireworks", "firework_rocket");
        TEXTURE_RENAMES_ITEMS.put("fireworks_charge", "firework_star");
        TEXTURE_RENAMES_ITEMS.put("fireworks_charge_overlay", "firework_star_overlay");
        TEXTURE_RENAMES_ITEMS.put("fish_clownfish_raw", "tropical_fish");
        TEXTURE_RENAMES_ITEMS.put("fish_cod_cooked", "cooked_cod");
        TEXTURE_RENAMES_ITEMS.put("fish_cod_raw", "cod");
        TEXTURE_RENAMES_ITEMS.put("fish_pufferfish_raw", "pufferfish");
        TEXTURE_RENAMES_ITEMS.put("fish_salmon_cooked", "cooked_salmon");
        TEXTURE_RENAMES_ITEMS.put("fish_salmon_raw", "salmon");
        TEXTURE_RENAMES_ITEMS.put("fishing_rod_uncast", "fishing_rod");
        TEXTURE_RENAMES_ITEMS.put("gold_axe", "golden_axe");
        TEXTURE_RENAMES_ITEMS.put("gold_boots", "golden_boots");
        TEXTURE_RENAMES_ITEMS.put("gold_chestplate", "golden_chestplate");
        TEXTURE_RENAMES_ITEMS.put("gold_helmet", "golden_helmet");
        TEXTURE_RENAMES_ITEMS.put("gold_hoe", "golden_hoe");
        TEXTURE_RENAMES_ITEMS.put("gold_horse_armor", "golden_horse_armor");
        TEXTURE_RENAMES_ITEMS.put("gold_leggings", "golden_leggings");
        TEXTURE_RENAMES_ITEMS.put("gold_pickaxe", "golden_pickaxe");
        TEXTURE_RENAMES_ITEMS.put("gold_shovel", "golden_shovel");
        TEXTURE_RENAMES_ITEMS.put("gold_sword", "golden_sword");
        TEXTURE_RENAMES_ITEMS.put("map_empty", "map");
        TEXTURE_RENAMES_ITEMS.put("map_filled", "filled_map");
        TEXTURE_RENAMES_ITEMS.put("map_filled_markings", "filled_map_markings");
        TEXTURE_RENAMES_ITEMS.put("melon", "melon_slice");
        TEXTURE_RENAMES_ITEMS.put("melon_speckled", "glistering_melon_slice");
        TEXTURE_RENAMES_ITEMS.put("minecart_chest", "chest_minecart");
        TEXTURE_RENAMES_ITEMS.put("minecart_command_block", "command_block_minecart");
        TEXTURE_RENAMES_ITEMS.put("minecart_furnace", "furnace_minecart");
        TEXTURE_RENAMES_ITEMS.put("minecart_hopper", "hopper_minecart");
        TEXTURE_RENAMES_ITEMS.put("minecart_normal", "minecart");
        TEXTURE_RENAMES_ITEMS.put("minecart_tnt", "tnt_minecart");
        TEXTURE_RENAMES_ITEMS.put("mutton_cooked", "cooked_mutton");
        TEXTURE_RENAMES_ITEMS.put("mutton_raw", "mutton");
        TEXTURE_RENAMES_ITEMS.put("netherbrick", "nether_brick");
        TEXTURE_RENAMES_ITEMS.put("porkchop_cooked", "cooked_porkchop");
        TEXTURE_RENAMES_ITEMS.put("porkchop_raw", "porkchop");
        TEXTURE_RENAMES_ITEMS.put("potato_baked", "baked_potato");
        TEXTURE_RENAMES_ITEMS.put("potato_poisonous", "poisonous_potato");
        TEXTURE_RENAMES_ITEMS.put("potion_bottle_drinkable", "potion");
        TEXTURE_RENAMES_ITEMS.put("potion_bottle_empty", "glass_bottle");
        TEXTURE_RENAMES_ITEMS.put("potion_bottle_lingering", "lingering_potion");
        TEXTURE_RENAMES_ITEMS.put("potion_bottle_splash_potion", "splash_potion");
        TEXTURE_RENAMES_ITEMS.put("rabbit_cooked", "cooked_rabbit");
        TEXTURE_RENAMES_ITEMS.put("rabbit_raw", "rabbit");
        TEXTURE_RENAMES_ITEMS.put("record_11", "music_disc_11");
        TEXTURE_RENAMES_ITEMS.put("record_13", "music_disc_13");
        TEXTURE_RENAMES_ITEMS.put("record_blocks", "music_disc_blocks");
        TEXTURE_RENAMES_ITEMS.put("record_cat", "music_disc_cat");
        TEXTURE_RENAMES_ITEMS.put("record_chirp", "music_disc_chirp");
        TEXTURE_RENAMES_ITEMS.put("record_far", "music_disc_far");
        TEXTURE_RENAMES_ITEMS.put("record_mall", "music_disc_mall");
        TEXTURE_RENAMES_ITEMS.put("record_mellohi", "music_disc_mellohi");
        TEXTURE_RENAMES_ITEMS.put("record_stal", "music_disc_stal");
        TEXTURE_RENAMES_ITEMS.put("record_strad", "music_disc_strad");
        TEXTURE_RENAMES_ITEMS.put("record_wait", "music_disc_wait");
        TEXTURE_RENAMES_ITEMS.put("record_ward", "music_disc_ward");
        TEXTURE_RENAMES_ITEMS.put("redstone_dust", "redstone");
        TEXTURE_RENAMES_ITEMS.put("seeds_melon", "melon_seeds");
        TEXTURE_RENAMES_ITEMS.put("seeds_pumpkin", "pumpkin_seeds");
        TEXTURE_RENAMES_ITEMS.put("seeds_wheat", "wheat_seeds");
        TEXTURE_RENAMES_ITEMS.put("slimeball", "slime_ball");
        TEXTURE_RENAMES_ITEMS.put("totem", "totem_of_undying");
        TEXTURE_RENAMES_ITEMS.put("wood_axe", "wooden_axe");
        TEXTURE_RENAMES_ITEMS.put("wood_hoe", "wooden_hoe");
        TEXTURE_RENAMES_ITEMS.put("wood_pickaxe", "wooden_pickaxe");
        TEXTURE_RENAMES_ITEMS.put("wood_shovel", "wooden_shovel");
        TEXTURE_RENAMES_ITEMS.put("wood_sword", "wooden_sword");
        TEXTURE_RENAMES_ITEMS.put("wooden_armorstand", "armor_stand");
    }
    
    private final PackFormat targetPackFormat;
    private final String targetMcVersion;
    
    public ResourcePackTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.targetPackFormat = PackFormat.resourceTarget(targetMcVersion);
    }
    
    /**
     * Check if a file is a resource pack.
     */
    public static boolean isResourcePack(Path path) {
        try {
            PackMetadata.read(path);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }
    
    /**
     * Get pack format from a resource pack.
     */
    public int getPackFormat(Path packPath) {
        try {
            return PackMetadata.read(packPath).primary().major();
        } catch (IOException e) {
            return -1;
        }
    }
    
    /**
     * Check if pack needs transformation.
     */
    public boolean needsTransformation(Path packPath) throws IOException {
        PackMetadata.DeclaredFormats formats = PackMetadata.read(packPath);
        refuseDowngrade(formats, packPath);
        return !formats.supports(targetPackFormat);
    }
    
    /**
     * Transform a resource pack to work with target version.
     * 
     * @param sourcePack Path to original pack (.zip or folder)
     * @param outputDir Directory to write transformed pack
     * @return Path to transformed pack
     */
    public Path transformPack(Path sourcePack, Path outputDir) throws IOException {
        String name = sourcePack.getFileName().toString();
        PackMetadata.DeclaredFormats oldFormats = PackMetadata.read(sourcePack);
        refuseDowngrade(oldFormats, sourcePack);
        PackFormat oldFormat = oldFormats.primary();
        
        LOGGER.info("Transforming resource pack: {} (format {} to {})", name,
            oldFormat.display(), targetPackFormat.display());
        
        if (oldFormats.supports(targetPackFormat)) {
            LOGGER.info("  Pack is already compatible - copying unchanged");
            Path dest = outputDir.resolve(name);
            PackArchive.copyPathAtomically(sourcePack, dest);
            return dest;
        }
        
        // Create temp directory for transformation
        Path tempDir = Files.createTempDirectory("retromod-rp-");
        
        try {
            // Extract pack
            if (Files.isDirectory(sourcePack, LinkOption.NOFOLLOW_LINKS)) {
                PackArchive.copyDirectoryContents(sourcePack, tempDir);
            } else {
                PackArchive.extractZip(sourcePack, tempDir, "Resource pack");
            }
            
            // Transform pack.mcmeta
            PackMetadata.rewrite(tempDir, targetPackFormat, targetPackFormat.major() >= 65);
            
            // Transform texture paths if needed
            if (oldFormat.compareTo(new PackFormat(4, 0)) < 0) {
                // Pre-1.13 pack: needs path transforms
                transformTexturePaths(tempDir);
            }

            int migrated = ModDataMigrator.migrateTreeChecked(tempDir, targetMcVersion);
            if (migrated > 0) {
                LOGGER.info("  Updated {} resource file(s)", migrated);
            }
            
            // Repack
            String outputName = PackArchive.transformedOutputName(name);
            Path outputPath = outputDir.resolve(outputName);
            PackArchive.packZip(tempDir, outputPath);
            
            LOGGER.info("  Transformed: {}", outputName);
            return outputPath;
            
        } finally {
            PackArchive.deleteRecursivelyQuietly(tempDir);
        }
    }
    
    /**
     * Transform texture paths for pre-1.13 packs.
     */
    private void transformTexturePaths(Path packDir) throws IOException {
        Path texturesDir = packDir.resolve("assets/minecraft/textures");
        if (!Files.exists(texturesDir)) return;
        
        // Check for old structure (blocks/ vs block/)
        Path oldBlocks = texturesDir.resolve("blocks");
        Path newBlocks = texturesDir.resolve("block");
        if (Files.exists(oldBlocks)) {
            mergeTextureDirectory(oldBlocks, newBlocks);
            LOGGER.debug("  Renamed textures/blocks → textures/block");
        }
        
        Path oldItems = texturesDir.resolve("items");
        Path newItems = texturesDir.resolve("item");
        if (Files.exists(oldItems)) {
            mergeTextureDirectory(oldItems, newItems);
            LOGGER.debug("  Renamed textures/items → textures/item");
        }
        
        // These are block texture names. Item textures with the same basename are unrelated.
        for (var entry : TEXTURE_RENAMES_BLOCKS.entrySet()) {
            renameTexture(newBlocks, entry.getKey(), entry.getValue());
        }
        for (var entry : TEXTURE_RENAMES_ITEMS.entrySet()) {
            renameTexture(newItems, entry.getKey(), entry.getValue());
        }
    }

    private void mergeTextureDirectory(Path source, Path destination) throws IOException {
        if (!Files.exists(destination)) {
            Files.move(source, destination);
            return;
        }
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Texture destination is not a directory: " + destination);
        }

        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(source)) continue;
                Path target = destination.resolve(source.relativize(path).toString()).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("Texture path escapes its destination: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Texture destination already exists: " + target);
                    }
                    Files.createDirectories(target.getParent());
                    Files.move(path, target);
                }
            }
        }
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(path);
            }
        }
    }
    
    /**
     * Rename a texture file if it exists.
     */
    private void renameTexture(Path blockTextures, String oldName, String newName) throws IOException {
        if (!Files.isDirectory(blockTextures, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(blockTextures)) {
            for (Path path : stream
                    .filter(p -> p.getFileName().toString().equals(oldName + ".png"))
                    .toList()) {
                Path newPath = path.getParent().resolve(newName + ".png");
                Path oldMetadata = path.resolveSibling(path.getFileName() + ".mcmeta");
                Path newMetadata = newPath.resolveSibling(newPath.getFileName() + ".mcmeta");
                if (Files.exists(newPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(newMetadata, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Texture rename destination already exists: " + newPath);
                }
                Files.move(path, newPath);
                if (Files.exists(oldMetadata, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(oldMetadata, newMetadata);
                }
                LOGGER.debug("  Renamed {} to {}", oldName, newName);
            }
        }
    }

    private void refuseDowngrade(PackMetadata.DeclaredFormats formats, Path packPath)
            throws IOException {
        if (formats.minimum().compareTo(targetPackFormat) > 0) {
            throw new IOException("Resource pack " + packPath.getFileName()
                + " requires format " + formats.minimum().display()
                + ", which is newer than target format " + targetPackFormat.display());
        }
    }

}
