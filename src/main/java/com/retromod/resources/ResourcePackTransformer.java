/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.regex.*;

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
 * - 69.0: 1.21.9 - 1.21.10
 * - 75.0: 1.21.11
 * - 84.0: 26.1 - 26.1.2
 * - 88.0: 26.2
 */
public class ResourcePackTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    
    // Pack format for target MC versions
    private static final Map<String, Integer> PACK_FORMATS = new HashMap<>();
    static {
        PACK_FORMATS.put("1.20", 15);
        PACK_FORMATS.put("1.20.1", 15);
        PACK_FORMATS.put("1.20.2", 18);
        PACK_FORMATS.put("1.20.3", 22);
        PACK_FORMATS.put("1.20.4", 22);
        PACK_FORMATS.put("1.20.5", 32);
        PACK_FORMATS.put("1.20.6", 32);
        PACK_FORMATS.put("1.21", 34);
        PACK_FORMATS.put("1.21.1", 34);
        PACK_FORMATS.put("1.21.2", 42);
        PACK_FORMATS.put("1.21.3", 42);
        PACK_FORMATS.put("1.21.4", 46);
        PACK_FORMATS.put("1.21.5", 55);
        PACK_FORMATS.put("1.21.6", 63);
        PACK_FORMATS.put("1.21.7", 64);
        PACK_FORMATS.put("1.21.8", 64);
        // 1.21.9 and newer use versions with dots in them
        // and are not included in this map.
    }
    
    // Texture path renames between versions (old -> new)
    private static final Map<String, String> TEXTURE_RENAMES = new HashMap<>();
    static {
        // 1.13 flattening renames
        // initial set, partially corrected from original commit
        TEXTURE_RENAMES.put("grass_side", "grass_block_side");
        TEXTURE_RENAMES.put("grass_top", "grass_block_top");
        TEXTURE_RENAMES.put("hardened_clay", "terracotta");
        TEXTURE_RENAMES.put("stone_slab_top", "smooth_stone");
        TEXTURE_RENAMES.put("stone_slab_side", "smooth_stone_slab_side");
        TEXTURE_RENAMES.put("mob_spawner", "spawner");
        TEXTURE_RENAMES.put("noteblock", "note_block");
        TEXTURE_RENAMES.put("redstone_torch_on", "redstone_torch");
        TEXTURE_RENAMES.put("comparator_off", "comparator");
        TEXTURE_RENAMES.put("repeater_off", "repeater");

        // other state/orientation based textures
        TEXTURE_RENAMES.put("grass_side_overlay", "grass_block_side_overlay");
        TEXTURE_RENAMES.put("grass_side_snowed", "grass_block_snow");
        TEXTURE_RENAMES.put("comparator_off", "comparator");
        TEXTURE_RENAMES.put("dispenser_front_horizontal", "dispenser_front");
        TEXTURE_RENAMES.put("dropper_front_horizontal", "dropper_front");
        TEXTURE_RENAMES.put("endframe_ey", "end_portal_frame_eye");
        TEXTURE_RENAMES.put("endframe_side", "end_portal_frame_side");
        TEXTURE_RENAMES.put("endframe_top", "end_portal_frame_top");
        TEXTURE_RENAMES.put("farmland_wet", "farmland_moist");
        TEXTURE_RENAMES.put("farmland_dry", "farmland");
        TEXTURE_RENAMES.put("furnace_front_off", "furnace_front");
        TEXTURE_RENAMES.put("observer_back_lit", "observer_back_on");
        TEXTURE_RENAMES.put("piston_top_normal", "piston_top");
        TEXTURE_RENAMES.put("pumpkin_face_off", "carved_pumpkin");
        TEXTURE_RENAMES.put("pumpkin_face_on", "jack_o_lantern");
        TEXTURE_RENAMES.put("redstone_lamp_off", "redstone_lamp");
        TEXTURE_RENAMES.put("torch_on", "torch");

        // rails
        TEXTURE_RENAMES.put("rail_activator", "activator_rail");
        TEXTURE_RENAMES.put("rail_powered", "activator_rail_on");
        TEXTURE_RENAMES.put("rail_detector", "detector_rail");
        TEXTURE_RENAMES.put("rail_detector_powered", "detector_rail_on");
        TEXTURE_RENAMES.put("rail_golden", "golden_rail");
        TEXTURE_RENAMES.put("rail_golden_powered", "golden_rail_on");
        TEXTURE_RENAMES.put("rail_normal", "rail");
        TEXTURE_RENAMES.put("rail_normal_turned", "rail_corner");

        // wool colors
        TEXTURE_RENAMES.put("wool_colored_black", "black_wool");
        TEXTURE_RENAMES.put("wool_colored_blue", "blue_wool");
        TEXTURE_RENAMES.put("wool_colored_brown", "brown_wool");
        TEXTURE_RENAMES.put("wool_colored_cyan", "cyan_wool");
        TEXTURE_RENAMES.put("wool_colored_gray", "gray_wool");
        TEXTURE_RENAMES.put("wool_colored_green", "green_wool");
        TEXTURE_RENAMES.put("wool_colored_light_blue", "light_blue_wool");
        TEXTURE_RENAMES.put("wool_colored_lime", "lime_wool");
        TEXTURE_RENAMES.put("wool_colored_magenta", "magenta_wool");
        TEXTURE_RENAMES.put("wool_colored_orange", "orange_wool");
        TEXTURE_RENAMES.put("wool_colored_pink", "pink_wool");
        TEXTURE_RENAMES.put("wool_colored_purple", "purple_wool");
        TEXTURE_RENAMES.put("wool_colored_red", "red_wool");
        TEXTURE_RENAMES.put("wool_colored_silver", "light_gray_wool");
        TEXTURE_RENAMES.put("wool_colored_white", "white_wool");
        TEXTURE_RENAMES.put("wool_colored_yellow", "yellow_wool");

        // crops
        TEXTURE_RENAMES.put("beetroots_stage_0", "beetroots_stage0");
        TEXTURE_RENAMES.put("beetroots_stage_1", "beetroots_stage1");
        TEXTURE_RENAMES.put("beetroots_stage_2", "beetroots_stage2");
        TEXTURE_RENAMES.put("beetroots_stage_3", "beetroots_stage3");
        TEXTURE_RENAMES.put("carrots_stage_0", "carrots_stage0");
        TEXTURE_RENAMES.put("carrots_stage_1", "carrots_stage1");
        TEXTURE_RENAMES.put("carrots_stage_2", "carrots_stage2");
        TEXTURE_RENAMES.put("carrots_stage_3", "carrots_stage3");
        TEXTURE_RENAMES.put("cocoa_stage_0", "cocoa_stage0");
        TEXTURE_RENAMES.put("cocoa_stage_1", "cocoa_stage1");
        TEXTURE_RENAMES.put("cocoa_stage_2", "cocoa_stage2");
        TEXTURE_RENAMES.put("nether_wart_stage_0", "nether_wart_stage0");
        TEXTURE_RENAMES.put("nether_wart_stage_1", "nether_wart_stage1");
        TEXTURE_RENAMES.put("nether_wart_stage_2", "nether_wart_stage2");
        TEXTURE_RENAMES.put("potatoes_stage_0", "potatoes_stage0");
        TEXTURE_RENAMES.put("potatoes_stage_1", "potatoes_stage1");
        TEXTURE_RENAMES.put("potatoes_stage_2", "potatoes_stage2");
        TEXTURE_RENAMES.put("potatoes_stage_3", "potatoes_stage3");
        TEXTURE_RENAMES.put("wheat_stage_0", "wheat_stage0");
        TEXTURE_RENAMES.put("wheat_stage_1", "wheat_stage1");
        TEXTURE_RENAMES.put("wheat_stage_2", "wheat_stage2");
        TEXTURE_RENAMES.put("wheat_stage_3", "wheat_stage3");
        TEXTURE_RENAMES.put("wheat_stage_4", "wheat_stage4");
        TEXTURE_RENAMES.put("wheat_stage_5", "wheat_stage5");
        TEXTURE_RENAMES.put("wheat_stage_6", "wheat_stage6");
        TEXTURE_RENAMES.put("wheat_stage_7", "wheat_stage7");

        // stems
        TEXTURE_RENAMES.put("melon_stem_connected", "attached_melon_stem");
        TEXTURE_RENAMES.put("melon_stem_disconnected", "melon_stem");
        TEXTURE_RENAMES.put("pumpkin_stem_connected", "attached_pumpkin_stem");
        TEXTURE_RENAMES.put("pumpkin_stem_disconnected", "pumpkin_stem");

        // plants and flowers
        TEXTURE_RENAMES.put("deadbush", "deadbush");
        TEXTURE_RENAMES.put("double_plant_fern_bottom", "large_fern_bottom");
        TEXTURE_RENAMES.put("double_plant_fern_top", "large_fern_top");
        TEXTURE_RENAMES.put("double_plant_grass_bottom", "tall_grass_bottom");
        TEXTURE_RENAMES.put("double_plant_grass_top", "tall_grass_top");
        TEXTURE_RENAMES.put("double_plant_paeonia_bottom", "peony_bottom");
        TEXTURE_RENAMES.put("double_plant_paeonia_top", "peony_top");
        TEXTURE_RENAMES.put("double_plant_rose_bottom", "rose_bush_bottom");
        TEXTURE_RENAMES.put("double_plant_rose_top", "rose_bush_top");
        TEXTURE_RENAMES.put("double_plant_sunflower_back", "sunflower_back");
        TEXTURE_RENAMES.put("double_plant_sunflower_bottom", "sunflower_bottom");
        TEXTURE_RENAMES.put("double_plant_sunflower_front", "sunflower_front");
        TEXTURE_RENAMES.put("double_plant_sunflower_top", "sunflower_top");
        TEXTURE_RENAMES.put("double_plant_syringa_bottom", "lilac_bottom");
        TEXTURE_RENAMES.put("double_plant_syringa_top", "lilac_top");
        TEXTURE_RENAMES.put("flower_allium", "allium");
        TEXTURE_RENAMES.put("flower_blue_orchid", "blue_orchid");
        TEXTURE_RENAMES.put("flower_dandelion", "dandelion");
        TEXTURE_RENAMES.put("flower_houstonia", "azure_bluet");
        TEXTURE_RENAMES.put("flower_oxeye_daisy", "oxeye_daisy");
        TEXTURE_RENAMES.put("flower_rose", "poppy");
        TEXTURE_RENAMES.put("flower_tulip_orange", "orange_tulip");
        TEXTURE_RENAMES.put("flower_tulip_pink", "pink_tulip");
        TEXTURE_RENAMES.put("flower_tulip_red", "red_tulip");
        TEXTURE_RENAMES.put("flower_tulip_white", "white_tulip");
        TEXTURE_RENAMES.put("mushroom_brown", "brown_mushroom");
        TEXTURE_RENAMES.put("mushroom_red", "red_mushroom");
        TEXTURE_RENAMES.put("reeds", "sugar_cane");
        TEXTURE_RENAMES.put("tallgrass", "grass");
        TEXTURE_RENAMES.put("waterlily", "lily_pad");

        // anvils
        TEXTURE_RENAMES.put("anvil_base", "anvil");
        TEXTURE_RENAMES.put("anvil_top_damaged_0", "anvil_top");
        TEXTURE_RENAMES.put("anvil_top_damaged_1", "chipped_anvil_top");
        TEXTURE_RENAMES.put("anvil_top_damaged_2", "damaged_anvil_top");

        // wood stuff (doors, logs, planks, leaves and saplings)
        TEXTURE_RENAMES.put("door_acacia_lower", "acacia_door_bottom");
        TEXTURE_RENAMES.put("door_acacia_upper", "acacia_door_top");
        TEXTURE_RENAMES.put("door_birch_lower", "birch_door_bottom");
        TEXTURE_RENAMES.put("door_birch_upper", "birch_door_top");
        TEXTURE_RENAMES.put("door_dark_oak_lower", "dark_oak_door_bottom");
        TEXTURE_RENAMES.put("door_dark_oak_upper", "dark_oak_door_top");
        TEXTURE_RENAMES.put("door_iron_lower", "iron_door_bottom");
        TEXTURE_RENAMES.put("door_iron_upper", "iron_door_top");
        TEXTURE_RENAMES.put("door_jungle_lower", "jungle_door_bottom");
        TEXTURE_RENAMES.put("door_jungle_upper", "jungle_door_top");
        TEXTURE_RENAMES.put("door_spruce_lower", "spruce_door_bottom");
        TEXTURE_RENAMES.put("door_spruce_upper", "spruce_door_top");
        TEXTURE_RENAMES.put("door_wood_lower", "oak_door_bottom");
        TEXTURE_RENAMES.put("door_wood_upper", "oak_door_top");
        TEXTURE_RENAMES.put("leaves_acacia", "acacia_leaves");
        TEXTURE_RENAMES.put("leaves_big_oak", "dark_oak_leaves");
        TEXTURE_RENAMES.put("leaves_birch", "birch_leaves");
        TEXTURE_RENAMES.put("leaves_jungle", "jungle_leaves");
        TEXTURE_RENAMES.put("leaves_oak", "oak_leaves");
        TEXTURE_RENAMES.put("leaves_spruce", "spruce_leaves");
        TEXTURE_RENAMES.put("log_acacia", "acacia_log");
        TEXTURE_RENAMES.put("log_acacia_top", "acacia_log_top");
        TEXTURE_RENAMES.put("log_big_oak", "dark_oak_log");
        TEXTURE_RENAMES.put("log_big_oak_top", "dark_oak_log_top");
        TEXTURE_RENAMES.put("log_birch", "birch_log");
        TEXTURE_RENAMES.put("log_birch_top", "birch_log_top");
        TEXTURE_RENAMES.put("log_jungle", "jungle_log");
        TEXTURE_RENAMES.put("log_jungle_top", "jungle_log_top");
        TEXTURE_RENAMES.put("log_oak", "oak_log");
        TEXTURE_RENAMES.put("log_oak_top", "oak_log_top");
        TEXTURE_RENAMES.put("log_spruce", "spruce_log");
        TEXTURE_RENAMES.put("log_spruce_top", "spruce_log_top");
        TEXTURE_RENAMES.put("planks_acacia", "acacia_planks");
        TEXTURE_RENAMES.put("planks_big_oak", "dark_oak_planks");
        TEXTURE_RENAMES.put("planks_birch", "birch_planks");
        TEXTURE_RENAMES.put("planks_jungle", "jungle_planks");
        TEXTURE_RENAMES.put("planks_oak", "oak_planks");
        TEXTURE_RENAMES.put("planks_spruce", "spruce_planks");
        TEXTURE_RENAMES.put("sapling_acacia", "acacia_sapling");
        TEXTURE_RENAMES.put("sapling_birch", "birch_sapling");
        TEXTURE_RENAMES.put("sapling_jungle", "jungle_sapling");
        TEXTURE_RENAMES.put("sapling_oak", "oak_sapling");
        TEXTURE_RENAMES.put("sapling_roofed_oak", "dark_oak_sapling");
        TEXTURE_RENAMES.put("sapling_spruce", "spruce_sapling");

        // animated textures
        TEXTURE_RENAMES.put("fire_layer_0", "fire_0");
        TEXTURE_RENAMES.put("fire_layer_1", "fire_1");
        TEXTURE_RENAMES.put("portal", "nether_portal");

        // terracotta color variants
        TEXTURE_RENAMES.put("hardened_clay_stained_black", "black_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_blue", "blue_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_brown", "brown_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_cyan", "cyan_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_gray", "gray_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_green", "green_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_light_blue", "light_blue_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_lime", "lime_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_magenta", "magenta_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_orange", "orange_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_pink", "pink_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_purple", "purple_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_red", "red_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_silver", "light_gray_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_white", "white_terracotta");
        TEXTURE_RENAMES.put("hardened_clay_stained_yellow", "yellow_terracotta");

        // glazed terracotta
        TEXTURE_RENAMES.put("glazed_terracotta_black", "black_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_blue", "blue_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_brown", "brown_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_cyan", "cyan_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_gray", "gray_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_green", "green_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_light_blue", "light_blue_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_lime", "lime_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_magenta", "magenta_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_orange", "orange_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_pink", "pink_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_purple", "purple_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_red", "red_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_silver", "light_gray_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_white", "white_glazed_terracotta");
        TEXTURE_RENAMES.put("glazed_terracotta_yellow", "yellow_glazed_terracotta");

        // concrete
        TEXTURE_RENAMES.put("concrete_black", "black_concrete");
        TEXTURE_RENAMES.put("concrete_blue", "blue_concrete");
        TEXTURE_RENAMES.put("concrete_brown", "brown_concrete");
        TEXTURE_RENAMES.put("concrete_cyan", "cyan_concrete");
        TEXTURE_RENAMES.put("concrete_gray", "gray_concrete");
        TEXTURE_RENAMES.put("concrete_green", "green_concrete");
        TEXTURE_RENAMES.put("concrete_light_blue", "light_blue_concrete");
        TEXTURE_RENAMES.put("concrete_lime", "lime_concrete");
        TEXTURE_RENAMES.put("concrete_magenta", "magenta_concrete");
        TEXTURE_RENAMES.put("concrete_orange", "orange_concrete");
        TEXTURE_RENAMES.put("concrete_pink", "pink_concrete");
        TEXTURE_RENAMES.put("concrete_purple", "purple_concrete");
        TEXTURE_RENAMES.put("concrete_red", "red_concrete");
        TEXTURE_RENAMES.put("concrete_silver", "light_gray_concrete");
        TEXTURE_RENAMES.put("concrete_white", "white_concrete");
        TEXTURE_RENAMES.put("concrete_yellow", "yellow_concrete");

        // concrete powder
        TEXTURE_RENAMES.put("concrete_powder_black", "black_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_blue", "blue_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_brown", "brown_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_cyan", "cyan_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_gray", "gray_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_green", "green_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_light_blue", "light_blue_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_lime", "lime_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_magenta", "magenta_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_orange", "orange_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_pink", "pink_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_purple", "purple_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_red", "red_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_silver", "light_gray_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_white", "white_concrete_powder");
        TEXTURE_RENAMES.put("concrete_powder_yellow", "yellow_concrete_powder");

        // glass
        TEXTURE_RENAMES.put("glass_black", "black_stained_glass");
        TEXTURE_RENAMES.put("glass_blue", "blue_stained_glass");
        TEXTURE_RENAMES.put("glass_brown", "brown_stained_glass");
        TEXTURE_RENAMES.put("glass_cyan", "cyan_stained_glass");
        TEXTURE_RENAMES.put("glass_gray", "gray_stained_glass");
        TEXTURE_RENAMES.put("glass_green", "green_stained_glass");
        TEXTURE_RENAMES.put("glass_light_blue", "light_blue_stained_glass");
        TEXTURE_RENAMES.put("glass_lime", "lime_stained_glass");
        TEXTURE_RENAMES.put("glass_magenta", "magenta_stained_glass");
        TEXTURE_RENAMES.put("glass_orange", "orange_stained_glass");
        TEXTURE_RENAMES.put("glass_pink", "pink_stained_glass");
        TEXTURE_RENAMES.put("glass_purple", "purple_stained_glass");
        TEXTURE_RENAMES.put("glass_red", "red_stained_glass");
        TEXTURE_RENAMES.put("glass_silver", "light_gray_stained_glass");
        TEXTURE_RENAMES.put("glass_white", "white_stained_glass");
        TEXTURE_RENAMES.put("glass_yellow", "yellow_stained_glass");

        // glass panes
        TEXTURE_RENAMES.put("glass_pane_top_black", "black_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_blue", "blue_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_brown", "brown_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_cyan", "cyan_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_gray", "gray_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_green", "green_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_light_blue", "light_blue_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_lime", "lime_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_magenta", "magenta_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_orange", "orange_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_pink", "pink_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_purple", "purple_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_red", "red_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_silver", "light_gray_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_white", "white_stained_glass_pane_top");
        TEXTURE_RENAMES.put("glass_pane_top_yellow", "yellow_stained_glass_pane_top");

        // Shulker boxes
        TEXTURE_RENAMES.put("shulker_top_black", "black_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_blue", "blue_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_brown", "brown_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_cyan", "cyan_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_gray", "gray_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_green", "green_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_light_blue", "light_blue_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_lime", "lime_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_magenta", "magenta_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_orange", "orange_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_pink", "pink_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_purple", "purple_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_red", "red_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_silver", "light_gray_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_white", "white_shulker_box");
        TEXTURE_RENAMES.put("shulker_top_yellow", "yellow_shulker_box");

        // stone and bricks
        TEXTURE_RENAMES.put("brick", "bricks");
        TEXTURE_RENAMES.put("cobblestone_mossy", "mossy_cobblestone");
        TEXTURE_RENAMES.put("end_bricks", "end_stone_bricks");
        TEXTURE_RENAMES.put("nether_brick", "nether_bricks");
        TEXTURE_RENAMES.put("prismarine_dark", "dark_prismarine");
        TEXTURE_RENAMES.put("prismarine_rough", "prismarine");
        TEXTURE_RENAMES.put("sandstone_carved", "chiseled_sandstone");
        TEXTURE_RENAMES.put("sandstone_normal", "sandstone");
        TEXTURE_RENAMES.put("sandstone_smooth", "cut_sandstone");
        TEXTURE_RENAMES.put("red_sandstone_carved", "chiseled_red_sandstone");
        TEXTURE_RENAMES.put("red_sandstone_normal", "red_sandstone");
        TEXTURE_RENAMES.put("red_sandstone_smooth", "cut_red_sandstone");
        TEXTURE_RENAMES.put("stone_andesite", "andesite");
        TEXTURE_RENAMES.put("stone_andesite_smooth", "polished_andesite");
        TEXTURE_RENAMES.put("stone_diorite", "diorite");
        TEXTURE_RENAMES.put("stone_diorite_smooth", "polished_diorite");
        TEXTURE_RENAMES.put("stone_granite", "granite");
        TEXTURE_RENAMES.put("stone_granite_smooth", "granite_smooth");
        TEXTURE_RENAMES.put("stonebrick", "stone_bricks");
        TEXTURE_RENAMES.put("stonebrick_carved", "chiseled_stone_bricks");
        TEXTURE_RENAMES.put("stonebrick_cracked", "cracked_stone_bricks");
        TEXTURE_RENAMES.put("stonebrick_mossy", "mossy_stone_bricks");

        // miscellaneous block textures
        TEXTURE_RENAMES.put("dirt_podzol_side", "podzol_side");
        TEXTURE_RENAMES.put("dirt_podzol_top", "podzol_top");
        TEXTURE_RENAMES.put("ice_packed", "packed_ice");
        TEXTURE_RENAMES.put("itemframe_background", "item_frame");
        TEXTURE_RENAMES.put("mushroom_block_skin_brown", "brown_mushroom_block");
        TEXTURE_RENAMES.put("mushroom_block_skin_red", "red_mushroom_block");
        TEXTURE_RENAMES.put("mushroom_block_skin_stem", "mushroom_stem");
        TEXTURE_RENAMES.put("quartz_block_chiseled", "chiseled_quartz_block");
        TEXTURE_RENAMES.put("quartz_block_chiseled_top", "chiseled_quartz_block_top");
        TEXTURE_RENAMES.put("quartz_block_lines", "quartz_pillar");
        TEXTURE_RENAMES.put("quartz_block_lines_top", "quartz_pillar_top");
        TEXTURE_RENAMES.put("quartz_ore", "nether_quartz_ore");
        TEXTURE_RENAMES.put("slime", "slime_block");
        TEXTURE_RENAMES.put("sponge_wet", "wet_sponge");
        TEXTURE_RENAMES.put("trapdoor", "oak_trapdoor");
        TEXTURE_RENAMES.put("trip_wire", "tripwire");
        TEXTURE_RENAMES.put("trip_wire_source", "tripwire_hook");
        TEXTURE_RENAMES.put("web", "cobweb");
        
        // Add more as needed
    }
    
    private final String targetMcVersion;
    private final int targetPackFormat;
    
    public ResourcePackTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.targetPackFormat = PACK_FORMATS.getOrDefault(targetMcVersion, 46);
    }
    
    /**
     * Check if a file is a resource pack.
     */
    public static boolean isResourcePack(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                return zip.getEntry("pack.mcmeta") != null;
            } catch (Exception e) {
                return false;
            }
        }
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("pack.mcmeta"));
        }
        return false;
    }
    
    /**
     * Get pack format from a resource pack.
     */
    public int getPackFormat(Path packPath) {
        try {
            String mcmeta = readPackMcmeta(packPath);
            if (mcmeta != null) {
                Pattern p = Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+)");
                Matcher m = p.matcher(mcmeta);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }
    
    /**
     * Check if pack needs transformation.
     */
    public boolean needsTransformation(Path packPath) {
        int format = getPackFormat(packPath);
        return format > 0 && format < targetPackFormat;
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
        int oldFormat = getPackFormat(sourcePack);
        
        LOGGER.info("Transforming resource pack: {} (format {} → {})", name, oldFormat, targetPackFormat);
        
        // If already correct format, just copy
        if (oldFormat >= targetPackFormat) {
            LOGGER.info("  Pack is already compatible - copying unchanged");
            Path dest = outputDir.resolve(name);
            if (Files.isDirectory(sourcePack)) {
                copyDirectory(sourcePack, dest);
            } else {
                Files.copy(sourcePack, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return dest;
        }
        
        // Create temp directory for transformation
        Path tempDir = Files.createTempDirectory("retromod-rp-");
        
        try {
            // Extract pack
            if (Files.isDirectory(sourcePack)) {
                copyDirectory(sourcePack, tempDir);
            } else {
                extractZip(sourcePack, tempDir);
            }
            
            // Transform pack.mcmeta
            transformPackMcmeta(tempDir);
            
            // Transform texture paths if needed
            if (oldFormat < 4) {
                // Pre-1.13 pack: needs path transforms
                transformTexturePaths(tempDir);
            }
            
            // Repack
            String outputName = name.replace(".zip", "") + "-retromod.zip";
            Path outputPath = outputDir.resolve(outputName);
            packZip(tempDir, outputPath);
            
            LOGGER.info("  Transformed: {}", outputName);
            return outputPath;
            
        } finally {
            // Cleanup temp
            deleteDirectory(tempDir);
        }
    }
    
    /**
     * Transform pack.mcmeta to target format.
     */
    private void transformPackMcmeta(Path packDir) throws IOException {
        Path mcmeta = packDir.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            // Create one
            Files.writeString(mcmeta, String.format("""
                {
                    "pack": {
                        "pack_format": %d,
                        "description": "Transformed by Retromod"
                    }
                }
                """, targetPackFormat));
            return;
        }
        
        String content = Files.readString(mcmeta);
        
        // Update pack_format
        content = content.replaceAll(
            "\"pack_format\"\\s*:\\s*\\d+",
            "\"pack_format\": " + targetPackFormat
        );
        
        // Add supported_formats for newer versions (1.20.2+)
        if (targetPackFormat >= 18 && !content.contains("supported_formats")) {
            // Insert supported_formats after pack_format
            content = content.replaceAll(
                "(\"pack_format\"\\s*:\\s*" + targetPackFormat + ")",
                "$1,\n        \"supported_formats\": [" + (targetPackFormat - 10) + ", " + targetPackFormat + "]"
            );
        }
        
        Files.writeString(mcmeta, content);
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
        if (Files.exists(oldBlocks) && !Files.exists(newBlocks)) {
            Files.move(oldBlocks, newBlocks);
            LOGGER.debug("  Renamed textures/blocks → textures/block");
        }
        
        Path oldItems = texturesDir.resolve("items");
        Path newItems = texturesDir.resolve("item");
        if (Files.exists(oldItems) && !Files.exists(newItems)) {
            Files.move(oldItems, newItems);
            LOGGER.debug("  Renamed textures/items → textures/item");
        }
        
        // Rename individual textures
        for (var entry : TEXTURE_RENAMES.entrySet()) {
            renameTexture(texturesDir, entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * Rename a texture file if it exists.
     */
    private void renameTexture(Path texturesDir, String oldName, String newName) {
        try (var stream = Files.walk(texturesDir)) {
            stream.filter(p -> p.getFileName().toString().equals(oldName + ".png"))
                  .forEach(p -> {
                      try {
                          Path newPath = p.getParent().resolve(newName + ".png");
                          if (!Files.exists(newPath)) {
                              Files.move(p, newPath);
                              LOGGER.debug("  Renamed {} → {}", oldName, newName);
                          }
                      } catch (Exception e) {
                          // Ignore
                      }
                  });
        } catch (Exception e) {
            // Ignore
        }
    }
    
    
    private String readPackMcmeta(Path packPath) throws IOException {
        if (Files.isDirectory(packPath)) {
            Path mcmeta = packPath.resolve("pack.mcmeta");
            return Files.exists(mcmeta) ? Files.readString(mcmeta) : null;
        } else {
            try (ZipFile zip = new ZipFile(packPath.toFile())) {
                var entry = zip.getEntry("pack.mcmeta");
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        return new String(is.readAllBytes());
                    }
                }
            }
        }
        return null;
    }
    
    private void extractZip(Path zipPath, Path outputDir) throws IOException {
        // Use bounded extraction (ZipSecurity.copyBounded) rather than
        // Files.copy(is, …): resource packs are user-supplied ZIPs and an
        // attacker-crafted entry can lie about its declared size to slip
        // past any header-based check. We count actual decompressed bytes
        // and enforce both per-entry and per-archive caps.
        long totalSize = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path outPath = ZipSecurity.safeResolve(outputDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    long writtenBytes;
                    try (InputStream is = zip.getInputStream(entry)) {
                        writtenBytes = ZipSecurity.copyBounded(
                            is, outPath, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, entry.getName());
                    }
                    totalSize += writtenBytes;
                    if (totalSize > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                        throw new IOException("Resource pack total extracted size exceeds limit ("
                            + ZipSecurity.DEFAULT_MAX_TOTAL_SIZE + " bytes) - possible zip bomb "
                            + "(decompressed " + totalSize + " bytes so far)");
                    }
                }
            }
        }
    }
    
    private void packZip(Path sourceDir, Path zipPath) throws IOException {
        Files.deleteIfExists(zipPath);
        try (var zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            try (var stream = Files.walk(sourceDir)) {
                stream.filter(p -> !Files.isDirectory(p)).forEach(path -> {
                    try {
                        String entryName = sourceDir.relativize(path).toString().replace("\\", "/");
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (Exception e) {
                        // Ignore
                    }
                });
            }
        }
    }
    
    private void copyDirectory(Path source, Path dest) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dst = dest.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    // Ignore
                }
            });
        }
    }
    
    private void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> -a.compareTo(b))
                  .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        } catch (Exception e) {
            // Ignore
        }
    }
}
