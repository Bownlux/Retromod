/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Single removed members spread over 1.21.11 and 26.x, each rebuilt on what replaced it where the host lacks it. */
public final class LegacyScatteredMembersBridge {

    static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/";
    static final String TEAM = "net/minecraft/world/scores/PlayerTeam";
    static final String TEAM_COLORS = ROOT + "world/scores/LegacyTeamColors";
    static final String FORMATTING = "Lnet/minecraft/ChatFormatting;";
    static final String PROFILE = "net/minecraft/world/item/component/ResolvableProfile";
    static final String PROFILES = ROOT + "world/item/component/LegacyResolvableProfiles";
    static final String PROPERTIES = "()Lcom/mojang/authlib/properties/PropertyMap;";
    static final String OPTIONS = "net/minecraft/client/Options";
    static final String CLOUDS = "()Lnet/minecraft/client/CloudStatus;";
    static final String RESULT = "net/minecraft/world/InteractionResult";
    static final String HOVER = "net/minecraft/network/chat/HoverEvent";
    static final String HOVER_VALUES = ROOT + "network/chat/LegacyHoverValues";
    static final String GET_VALUE = "(L" + HOVER + "$Action;)Ljava/lang/Object;";
    static final String BLOCK_ENTITY_TYPE = "net/minecraft/world/level/block/entity/BlockEntityType";
    static final String BLOCK_ENTITY_TYPES = ROOT + "world/level/block/entity/LegacyBlockEntityTypes";
    static final String GET_KEY = "(L" + BLOCK_ENTITY_TYPE + ";)Lnet/minecraft/resources/Identifier;";
    static final String PEARL_GL = "com/mojang/renderpearl/backend/opengl/GlStateManager";
    static final String BLAZE_GL = "com/mojang/blaze3d/opengl/GlStateManager";
    static final String VIDEO_MODE = "com/mojang/blaze3d/platform/VideoMode";
    static final String CAMERA = "net/minecraft/client/Camera";
    static final String TINT_GETTER = "net/minecraft/client/renderer/block/BlockAndTintGetter";
    static final String SHADE = "(Lnet/minecraft/core/Direction;Z)F";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String CONTAINER_SCREEN = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
    static final String SELECTION_LIST = "net/minecraft/client/gui/components/AbstractSelectionList";
    static final String SCROLL_AREA = "net/minecraft/client/gui/components/AbstractScrollArea";
    static final String WORLD_LIST = "net/minecraft/client/gui/screens/worldselection/WorldSelectionList";
    static final String SELECT_WORLD = "()Lnet/minecraft/client/gui/screens/worldselection/SelectWorldScreen;";
    static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String SOURCE = "()Lnet/minecraft/commands/CommandSourceStack;";
    static final String VILLAGER_TYPE = "net/minecraft/world/entity/npc/villager/VillagerType";
    static final String BY_BIOME = "(Lnet/minecraft/core/Holder;)L" + VILLAGER_TYPE + ";";
    static final String LEVEL_DATA = "net/minecraft/client/multiplayer/ClientLevel$ClientLevelData";
    static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    static final String CLIENT_LEVEL = "Lnet/minecraft/client/multiplayer/ClientLevel;";
    static final String POTION = "net/minecraft/world/item/alchemy/Potion";
    static final String POTION_NAME = "(Ljava/util/Optional;Ljava/lang/String;)Ljava/lang/String;";
    static final String SHEETS = "net/minecraft/client/renderer/Sheets";
    static final String SPRITE_ID = "Lnet/minecraft/client/resources/model/sprite/SpriteId;";
    /** Sheets getters 26.1 renamed from material to sprite, with their parameters; each is gated on the host having it. */
    static final String[][] SHEETS_GETTERS = {
            {"getBannerMaterial", "getBannerSprite", "Lnet/minecraft/core/Holder;"},
            {"getShieldMaterial", "getShieldSprite", "Lnet/minecraft/core/Holder;"},
            {"getShulkerBoxMaterial", "getShulkerBoxSprite", "Lnet/minecraft/world/item/DyeColor;"},
            {"createShulkerMaterial", "createShulkerSprite", "Lnet/minecraft/world/item/DyeColor;"},
            {"getBedMaterial", "getBedSprite", "Lnet/minecraft/world/item/DyeColor;"},
            {"createBedMaterial", "createBedSprite", "Lnet/minecraft/world/item/DyeColor;"},
            {"getSignMaterial", "getSignSprite", "Lnet/minecraft/world/level/block/state/properties/WoodType;"},
            {"getHangingSignMaterial", "getHangingSignSprite", "Lnet/minecraft/world/level/block/state/properties/WoodType;"},
            {"getDecoratedPotMaterial", "getDecoratedPotSprite", "Lnet/minecraft/resources/ResourceKey;"},
            {"chooseMaterial", "chooseSprite", "Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState$ChestMaterialType;"
                    + "Lnet/minecraft/world/level/block/state/properties/ChestType;"}};
    /** The sprite getters 26.1, 26.2 and 26.3 all have. */
    static final java.util.Set<String> SHEETS_GETTERS_KEPT = java.util.Set.of("getBannerSprite", "getShieldSprite",
            "getShulkerBoxSprite", "createShulkerSprite", "chooseSprite");
    /** Sheets maps 26.1 renamed the same way. */
    static final String[][] SHEETS_MAPS = {{"SIGN_MATERIALS", "SIGN_SPRITES"}, {"HANGING_SIGN_MATERIALS", "HANGING_SIGN_SPRITES"},
            {"DECORATED_POT_MATERIALS", "DECORATED_POT_SPRITES"}};

    private LegacyScatteredMembersBridge() {}

    /** Registers each bridge whose old member the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // 26.2 gave teams their own color type in place of a chat formatting.
        if (lacks(host.apply(TEAM), "getColor", "()" + FORMATTING, "26.2")) {
            RebuiltApiClasses.registerTree(t, TEAM_COLORS);
            t.registerMethodRedirect(TEAM, "getColor", "()" + FORMATTING, TEAM_COLORS, "getColor", "(L" + TEAM + ";)" + FORMATTING, true);
            t.registerMethodRedirect(TEAM, "setColor", "(" + FORMATTING + ")V", TEAM_COLORS, "setColor",
                    "(L" + TEAM + ";" + FORMATTING + ")V", true);
        }
        if (lacks(host.apply(PROFILE), "properties", PROPERTIES, "1.21.11")) {
            RebuiltApiClasses.registerTree(t, PROFILES);
            t.registerMethodRedirect(PROFILE, "properties", PROPERTIES, PROFILES, "properties", "(L" + PROFILE + ";)" + PROPERTIES.substring(2), true);
        }
        // 26.1 renamed the cloud setting read.
        ClassNode options = host.apply(OPTIONS);
        if (lacks(options, "getCloudsType", CLOUDS, "26.1") && (options == null || !lacks(options, "getCloudStatus", CLOUDS, "26.1"))) {
            t.registerMethodRedirect(OPTIONS, "getCloudsType", CLOUDS, OPTIONS, "getCloudStatus", CLOUDS);
        }
        // CONSUME_PARTIAL became CONSUME when the result stopped being an enum in 1.21.2.
        ClassNode result = host.apply(RESULT);
        if (result == null || !hasField(result, "CONSUME_PARTIAL")) {
            t.registerFieldRedirect(RESULT, "CONSUME_PARTIAL", null, RESULT, "CONSUME", "L" + RESULT + "$Success;");
        }
        if (lacks(host.apply(HOVER), "getValue", GET_VALUE, "26.1") && targetAtLeast("26.1")) {
            RebuiltApiClasses.registerTree(t, HOVER_VALUES);
            t.registerMethodRedirect(HOVER, "getValue", GET_VALUE, HOVER_VALUES, "getValue", "(L" + HOVER + ";" + GET_VALUE.substring(1), true);
        }
        if (lacks(host.apply(BLOCK_ENTITY_TYPE), "getKey", GET_KEY, "26.1")) {
            RebuiltApiClasses.registerTree(t, BLOCK_ENTITY_TYPES);
            t.registerMethodRedirect(BLOCK_ENTITY_TYPE, "getKey", GET_KEY, BLOCK_ENTITY_TYPES, "getKey", GET_KEY);
        }
        registerBlend(t, host);
        registerSheetsSprites(t, host.apply(SHEETS));
        registerClient(t, host);
        registerGameplay(t, host);
        if (lacks(host.apply(VIDEO_MODE), "getRefreshRate", "()I", "26.3")) {
            String helper = ROOT + "client/LegacyVideoModes";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(VIDEO_MODE, "getRefreshRate", "()I", helper, "getRefreshRate", "(L" + VIDEO_MODE + ";)I", true);
        }
        // 1.21.11 made the camera's vectors read-only under new names; each old read is gated on its own.
        ClassNode camera = host.apply(CAMERA);
        for (String name : new String[]{"getLookVector", "getUpVector", "getLeftVector"}) {
            if (lacks(camera, name, "()Lorg/joml/Vector3f;", "1.21.11")) {
                String helper = ROOT + "client/LegacyCameraVectors";
                RebuiltApiClasses.registerTree(t, helper);
                t.registerMethodRedirect(CAMERA, name, "()Lorg/joml/Vector3f;", helper, name, "(L" + CAMERA + ";)Lorg/joml/Vector3f;", true);
            }
        }
        // 26.1 replaced the shade read with per-face lighting; the getter moved to the client package.
        if (lacks(host.apply(TINT_GETTER), "getShade", SHADE, "26.1") && targetAtLeast("26.1")) {
            String helper = ROOT + "client/renderer/block/LegacyBlockShades";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(TINT_GETTER, "getShade", SHADE, helper, "getShade", "(L" + TINT_GETTER + ";" + SHADE.substring(1), true);
        }
        // The remainder became a stack template in 26.1; 1.21.11's stack form is left to the host.
        String remainder = "()L" + ITEM + ";";
        if (lacks(host.apply(ITEM), "getCraftingRemainder", remainder, "26.1") && targetAtLeast("26.1")) {
            String helper = ROOT + "world/item/LegacyCraftingRemainders";
            RebuiltApiClasses.registerTree(t, helper);
            // Forge 1.21.1 spelled the same read getCraftingRemainingItem.
            for (String name : new String[]{"getCraftingRemainder", "getCraftingRemainingItem"}) {
                t.registerMethodRedirect(ITEM, name, remainder, helper, "getCraftingRemainder", "(L" + ITEM + ";)L" + ITEM + ";", true);
            }
        }
    }

    /** 26.1 renamed Sheets' material getters and maps to sprites; 26.2 dropped the sign and pot ones, so each needs the host's. */
    static void registerSheetsSprites(RetromodTransformer t, ClassNode sheets) {
        // Offline, only the getters every host from 26.1 still has are renamed.
        if (sheets == null && !targetAtLeast("26.1")) return;
        for (String[] getter : SHEETS_GETTERS) {
            String spriteDesc = "(" + getter[2] + ")" + SPRITE_ID;
            boolean renamed = sheets == null ? SHEETS_GETTERS_KEPT.contains(getter[1])
                    : hasMethod(sheets, getter[1], spriteDesc) && !hasMethod(sheets, getter[0], spriteDesc);
            if (!renamed) continue;
            // The class move to SpriteId has already rewritten the call's descriptor by the time this redirect matches it.
            t.registerMethodRedirect(SHEETS, getter[0], spriteDesc, SHEETS, getter[1], spriteDesc);
        }
        for (String[] map : SHEETS_MAPS) {
            if (sheets == null || !hasField(sheets, map[1]) || hasField(sheets, map[0])) continue;
            t.registerFieldRedirect(SHEETS, map[0], "Ljava/util/Map;", SHEETS, map[1], "Ljava/util/Map;");
        }
    }

    /** Screen, list and debug members of the client that mods read, each rebuilt on what replaced it. */
    static void registerClient(RetromodTransformer t, Function<String, ClassNode> host) {
        registerContainerPositions(t, host.apply(CONTAINER_SCREEN));
        // 1.21.4 moved the scroll position to the scroll area and dropped the get prefix.
        ClassNode list = host.apply(SELECTION_LIST);
        ClassNode area = host.apply(SCROLL_AREA);
        if (lacks(list, "getScrollAmount", "()D", "1.21.4") && (area == null || !lacks(area, "scrollAmount", "()D", "1.21.4"))) {
            for (String owner : new String[]{SELECTION_LIST, "net/minecraft/client/gui/components/ObjectSelectionList",
                    "net/minecraft/client/gui/components/ContainerObjectSelectionList", WORLD_LIST,
                    "net/minecraft/client/gui/screens/multiplayer/ServerSelectionList"}) {
                t.registerMethodRedirect(owner, "getScrollAmount", "()D", owner, "scrollAmount", "()D");
            }
            t.registerInheritedMethodRedirect(SELECTION_LIST, "getScrollAmount", "()D", SELECTION_LIST, "scrollAmount", "()D");
        }
        // The world list serves more than one screen now, so its screen is a plain one.
        if (lacks(host.apply(WORLD_LIST), "getScreen", SELECT_WORLD, "1.21.11")) {
            String helper = ROOT + "client/gui/LegacyWorldLists";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(WORLD_LIST, "getScreen", SELECT_WORLD, helper, "getScreen", "(L" + WORLD_LIST + ";)" + SELECT_WORLD.substring(2), true);
        }
        // The hitbox switch became the entity hitbox debug entry.
        ClassNode dispatcher = host.apply(DISPATCHER);
        if (lacks(dispatcher, "shouldRenderHitBoxes", "()Z", "1.21.11")) {
            String helper = ROOT + "client/renderer/entity/LegacyHitBoxes";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(DISPATCHER, "shouldRenderHitBoxes", "()Z", helper, "shouldRenderHitBoxes", "(L" + DISPATCHER + ";)Z", true);
            t.registerMethodRedirect(DISPATCHER, "setRenderHitBoxes", "(Z)V", helper, "setRenderHitBoxes", "(L" + DISPATCHER + ";Z)V", true);
        }
        // 26.1 moved the weather off the client level data.
        if (lacks(host.apply(LEVEL_DATA), "isRaining", "()Z", "26.1") && targetAtLeast("26.1")) {
            String helper = ROOT + "client/multiplayer/LegacyClientWeather";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(LEVEL_DATA, "isRaining", "()Z", helper, "isRaining", "(L" + LEVEL_DATA + ";)Z", true);
        }
        // The local player's own client level field went; the level it reports is that level.
        ClassNode localPlayer = host.apply(LOCAL_PLAYER);
        if (localPlayer == null || !hasField(localPlayer, "clientLevel")) {
            String helper = ROOT + "client/player/LegacyLocalPlayers";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerFieldRedirect(LOCAL_PLAYER, "clientLevel", CLIENT_LEVEL, helper, "clientLevel", "(L" + LOCAL_PLAYER + ";)" + CLIENT_LEVEL);
        }
    }

    /** NeoForge 26.3 renamed its container screen position getters; vanilla and Forge hosts keep theirs or never had them. */
    static void registerContainerPositions(RetromodTransformer t, ClassNode screen) {
        boolean renamed = screen != null
                ? !hasMethod(screen, "getGuiLeft", "()I") && hasMethod(screen, "getLeftPos", "()I")
                : com.retromod.util.McReflect.isNeoForge() && targetAtLeast("26.3");
        if (!renamed) return;
        String[][] names = {{"getGuiLeft", "getLeftPos"}, {"getGuiTop", "getTopPos"}, {"getXSize", "getImageWidth"}, {"getYSize", "getImageHeight"}};
        for (String[] name : names) {
            // Mods call these through their own screen or a vanilla one, so the hierarchy is followed too.
            t.registerMethodRedirect(CONTAINER_SCREEN, name[0], "()I", CONTAINER_SCREEN, name[1], "()I");
            t.registerInheritedMethodRedirect(CONTAINER_SCREEN, name[0], "()I", CONTAINER_SCREEN, name[1], "()I");
        }
    }

    /** Entity, villager and potion members of the game logic, rebuilt on their replacements. */
    static void registerGameplay(RetromodTransformer t, Function<String, ClassNode> host) {
        // The sweep effect became private to the attack.
        if (lacks(host.apply(PLAYER), "sweepAttack", "()V", "1.21.11")) {
            String helper = ROOT + "world/entity/player/LegacySweepAttacks";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(PLAYER, "sweepAttack", "()V", helper, "sweepAttack", "(L" + PLAYER + ";)V", true);
            t.registerInheritedMethodRedirect(PLAYER, "sweepAttack", "()V", helper, "sweepAttack", "(L" + PLAYER + ";)V");
        }
        // A command source now names the server level it runs in.
        if (lacks(host.apply(ENTITY), "createCommandSourceStack", SOURCE, "1.21.11")) {
            String helper = ROOT + "world/entity/LegacyEntityCommandSources";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(ENTITY, "createCommandSourceStack", SOURCE, helper, "createCommandSourceStack",
                    "(L" + ENTITY + ";)" + SOURCE.substring(2), true);
            // A player still has its own form, which a call naming a player type keeps.
            t.registerInheritedMethodRedirect(ENTITY, "createCommandSourceStack", SOURCE, helper, "createCommandSourceStack",
                    "(L" + ENTITY + ";)" + SOURCE.substring(2));
        }
        if (lacks(host.apply(VILLAGER_TYPE), "byBiome", BY_BIOME, "1.21.11")) {
            String helper = ROOT + "world/entity/LegacyVillagerBiomes";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(VILLAGER_TYPE, "byBiome", BY_BIOME, helper, "byBiome", BY_BIOME);
        }
        if (lacks(host.apply(POTION), "getName", POTION_NAME, "1.21.11")) {
            String helper = ROOT + "world/item/LegacyPotionNames";
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(POTION, "getName", POTION_NAME, helper, "getName", POTION_NAME);
        }
    }

    /** The blend switches took a draw buffer from 26.2; 26.3 moved the state manager to the renderpearl backend. */
    static void registerBlend(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode pearl = host.apply(PEARL_GL);
        ClassNode blaze = host.apply(BLAZE_GL);
        boolean offline = pearl == null && blaze == null;
        String owner;
        String helper;
        if ((pearl != null && lacks(pearl, "_enableBlend", "()V", "26.3")) || (offline && targetAtLeast("26.3"))) {
            owner = PEARL_GL;
            helper = ROOT + "client/renderer/LegacyBlendCalls";
        } else if ((blaze != null && lacks(blaze, "_enableBlend", "()V", "26.2")) || (offline && targetAtLeast("26.2"))) {
            owner = BLAZE_GL;
            helper = ROOT + "client/renderer/LegacyBlendCalls262";
        } else {
            return;
        }
        RebuiltApiClasses.registerTree(t, helper);
        t.registerMethodRedirect(owner, "_enableBlend", "()V", helper, "enableBlend", "()V");
        t.registerMethodRedirect(owner, "_disableBlend", "()V", helper, "disableBlend", "()V");
    }

    private static boolean lacks(ClassNode owner, String name, String desc, String since) {
        if (owner == null) return targetAtLeast(since);
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        return !lacks(owner, name, desc, "0");
    }

    private static boolean hasField(ClassNode owner, String name) {
        for (FieldNode field : owner.fields) if (field.name.equals(name)) return true;
        return false;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
