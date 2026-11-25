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

import java.util.List;
import java.util.function.Function;

/** Members mods for 1.19.4 and older called that 26.x removed, each rebuilt on its replacement, for every loader. */
public final class LegacyEraCallsBridge {

    static final String WORLD = RebuiltApiClasses.ROOT + "minecraft/world/LegacyEraWorldCalls";
    static final String CLIENT = RebuiltApiClasses.ROOT + "minecraft/client/LegacyEraClientCalls";

    static final String SOURCE = "net/minecraft/commands/CommandSourceStack";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String INVENTORY = "net/minecraft/world/entity/player/Inventory";
    static final String VILLAGER_DATA = "net/minecraft/world/entity/npc/villager/VillagerData";
    static final String LLAMA = "net/minecraft/world/entity/animal/equine/Llama";
    static final String STORAGE = "net/minecraft/world/level/storage/LevelStorageSource";
    static final String ATTRIBUTE = "net/minecraft/world/entity/ai/attributes/Attribute";
    static final String RANGED_ATTRIBUTE = "net/minecraft/world/entity/ai/attributes/RangedAttribute";
    static final String PLAYER_INFO = "net/minecraft/client/multiplayer/PlayerInfo";
    static final String CLIENT_PLAYER = "net/minecraft/client/player/AbstractClientPlayer";
    static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    static final String TOAST_ID = "net/minecraft/client/gui/components/toasts/SystemToast$SystemToastId";
    static final String AO_STATUS = "net/minecraft/client/AmbientOcclusionStatus";
    static final String NARRATOR = "net/minecraft/client/GameNarrator";
    static final String TEXTS = RebuiltApiClasses.ROOT + "minecraft/network/chat/LegacyOldTexts";
    static final String COMPONENT = "net/minecraft/network/chat/Component";
    static final String VEC3I = "net/minecraft/core/Vec3i";
    static final String OLD_LITERAL = "net/minecraft/network/chat/contents/LiteralContents";
    static final String LITERAL = "net/minecraft/network/chat/contents/PlainTextContents$LiteralContents";

    static final String L_STACK = "L" + ITEM_STACK + ";";
    static final String SEND_SUCCESS = "(Lnet/minecraft/network/chat/Component;Z)V";
    static final String SAME_ITEM = "(" + L_STACK + ")Z";
    static final String STATIC_SAME_ITEM = "(" + L_STACK + L_STACK + ")Z";
    static final String CARRIED = "()" + L_STACK;
    static final String SET_CARRIED = "(" + L_STACK + ")V";
    static final String VILLAGER_TYPE = "()Lnet/minecraft/world/entity/npc/villager/VillagerType;";
    static final String VILLAGER_PROFESSION = "()Lnet/minecraft/world/entity/npc/villager/VillagerProfession;";
    static final String SWAG = "()Lnet/minecraft/world/item/DyeColor;";
    static final String LEVEL_LIST = "()Ljava/util/List;";
    static final String NAME = "()Ljava/lang/String;";
    static final String SKIN = "()Lnet/minecraft/resources/Identifier;";
    static final String BIOME = "net/minecraft/world/level/biome/Biome";
    static final String PRECIPITATION = "()L" + BIOME + "$Precipitation;";
    static final String ENCHANTMENT = "net/minecraft/world/item/enchantment/Enchantment";
    static final String ENCHANTMENT_HELPER = "net/minecraft/world/item/enchantment/EnchantmentHelper";
    static final String ENCHANTMENTS = "(" + L_STACK + ")Ljava/util/Map;";
    static final String PACK = "net/minecraft/server/packs/PackResources";
    static final String OLD_RESOURCE = "(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/Identifier;)Ljava/io/InputStream;";
    /** The pack types javac names for an old resource read. */
    static final List<String> PACKS = List.of(PACK, "net/minecraft/server/packs/VanillaPackResources",
            "net/minecraft/server/packs/PathPackResources", "net/minecraft/server/packs/FilePackResources",
            "net/minecraft/server/packs/AbstractPackResources");
    static final String PORTAL_FORCER = "net/minecraft/world/level/portal/PortalForcer";
    static final String FIND_PORTAL = "(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/level/border/WorldBorder;)Ljava/util/Optional;";

    /** The client player types javac names for an old skin call. */
    static final List<String> CLIENT_PLAYERS = List.of(CLIENT_PLAYER, LOCAL_PLAYER, "net/minecraft/client/player/RemotePlayer");

    private LegacyEraCallsBridge() {}

    /** Registers each bridge whose old member the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Every helper is built on 26.x shapes, so an older target keeps its own members.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") < 0) return;
        registerWorld(t, host);
        registerClient(t, host);
    }

    /** World, item, command, data and text members, each answered by the member that replaced it. */
    static void registerWorld(RetromodTransformer t, Function<String, ClassNode> host) {
        RebuiltApiClasses.registerTree(t, WORLD);
        if (lacks(host.apply(SOURCE), "sendSuccess", SEND_SUCCESS)) {
            receiverCall(t, SOURCE, "sendSuccess", SEND_SUCCESS, WORLD, "sendSuccess", "1.20");
        }
        // 1.20 made the item comparison static; the old instance call asked the same question of the receiver.
        if (lacks(host.apply(ITEM_STACK), "isSameItem", SAME_ITEM)) {
            for (String name : new String[] {"isSameItem", "sameItem"}) {
                t.registerMethodRedirect(ITEM_STACK, name, SAME_ITEM, ITEM_STACK, "isSameItem", STATIC_SAME_ITEM, true);
                t.limitRedirectToModsBefore(ITEM_STACK, name, SAME_ITEM, "1.20");
            }
        }
        ClassNode inventory = host.apply(INVENTORY);
        if (lacks(inventory, "getCarried", CARRIED)) {
            receiverCall(t, INVENTORY, "getCarried", CARRIED, WORLD, "getCarried", "1.17");
        }
        if (lacks(inventory, "setCarried", SET_CARRIED)) {
            receiverCall(t, INVENTORY, "setCarried", SET_CARRIED, WORLD, "setCarried", "1.17");
        }
        ClassNode villager = host.apply(VILLAGER_DATA);
        if (lacks(villager, "getType", VILLAGER_TYPE)) {
            receiverCall(t, VILLAGER_DATA, "getType", VILLAGER_TYPE, WORLD, "getType", "1.21.5");
        }
        if (lacks(villager, "getProfession", VILLAGER_PROFESSION)) {
            receiverCall(t, VILLAGER_DATA, "getProfession", VILLAGER_PROFESSION, WORLD, "getProfession", "1.21.5");
        }
        // A llama's carpet became body armor; the helper takes the llama as the mob that wears it.
        if (lacks(host.apply(LLAMA), "getSwag", SWAG)) {
            t.registerMethodRedirect(LLAMA, "getSwag", SWAG, WORLD, "getSwag", "(Lnet/minecraft/world/entity/Mob;)"
                    + SWAG.substring(2), true);
        }
        if (lacks(host.apply(BIOME), "getPrecipitation", PRECIPITATION)) {
            receiverCall(t, BIOME, "getPrecipitation", PRECIPITATION, WORLD, "getPrecipitation", "1.19.4");
        }
        // 1.21 made enchantments data; a mod that only reads them gets the stack's enchantments and their name keys.
        if (lacks(host.apply(ENCHANTMENT_HELPER), "getEnchantments", ENCHANTMENTS)) {
            t.registerMethodRedirect(ENCHANTMENT_HELPER, "getEnchantments", ENCHANTMENTS, WORLD, "getEnchantments", ENCHANTMENTS);
            t.limitRedirectToModsBefore(ENCHANTMENT_HELPER, "getEnchantments", ENCHANTMENTS, "1.21");
        }
        if (lacks(host.apply(ENCHANTMENT), "getDescriptionId", NAME)) {
            receiverCall(t, ENCHANTMENT, "getDescriptionId", NAME, WORLD, "enchantmentDescriptionId", "1.21");
        }
        if (lacks(host.apply(PORTAL_FORCER), "findPortalAround", FIND_PORTAL)) {
            receiverCall(t, PORTAL_FORCER, "findPortalAround", FIND_PORTAL, WORLD, "findPortalAround", "1.21");
        }
        if (lacks(host.apply(PACK), "getResource", OLD_RESOURCE)) {
            for (String owner : PACKS) {
                t.registerInheritedMethodRedirect(owner, "getResource", OLD_RESOURCE, WORLD, "getResource",
                        "(L" + PACK + ";" + OLD_RESOURCE.substring(1));
                t.limitRedirectToModsBefore(owner, "getResource", OLD_RESOURCE, "1.19.3");
            }
        }
        // The old form returned the folder list itself; only its return type differs, so newer calls never match.
        if (lacks(host.apply(STORAGE), "findLevelCandidates", LEVEL_LIST)) {
            t.registerMethodRedirect(STORAGE, "findLevelCandidates", LEVEL_LIST, WORLD, "findLevelCandidates",
                    "(L" + STORAGE + ";)Ljava/util/List;", true);
        }
        if (lacks(host.apply(VEC3I), "<init>", "(DDD)V")) {
            t.registerConstructorRedirect(VEC3I, "(DDD)V", WORLD, "vec3i", "(DDD)L" + VEC3I + ";");
            t.limitRedirectToModsBefore(VEC3I, "<init>", "(DDD)V", "1.19.4");
        }
        // A component's own text, which getContents() returned before 1.19 made the contents an object.
        if (lacks(host.apply(COMPONENT), "getContents", "()Ljava/lang/String;")) {
            RebuiltApiClasses.registerTree(t, TEXTS);
            for (String owner : new String[] {COMPONENT, "net/minecraft/network/chat/MutableComponent"}) {
                t.registerMethodRedirect(owner, "getContents", "()Ljava/lang/String;", TEXTS, "contentsText",
                        "(L" + COMPONENT + ";)Ljava/lang/String;", true);
                t.limitRedirectToModsBefore(owner, "getContents", "()Ljava/lang/String;", "1.19");
            }
        }
        // 1.20.3 nested the literal text contents record into PlainTextContents, with the same constructor and text().
        if (host.apply(OLD_LITERAL) == null) {
            t.registerClassRedirect(OLD_LITERAL, LITERAL);
            t.limitClassRedirectToModsBefore(OLD_LITERAL, "1.20.3");
        }
        // Mods before 1.16 named an attribute by getName; its description id is the name the host gives it now.
        if (lacks(host.apply(ATTRIBUTE), "getName", NAME)) {
            for (String owner : new String[] {ATTRIBUTE, RANGED_ATTRIBUTE}) {
                t.registerInheritedMethodRedirect(owner, "getName", NAME, owner, "getDescriptionId", NAME);
                t.limitRedirectToModsBefore(owner, "getName", NAME, "1.16");
            }
        }
    }

    /** Skin textures, the jumping mount check, the ambient occlusion option, the empty narration and the tutorial toast id. */
    static void registerClient(RetromodTransformer t, Function<String, ClassNode> host) {
        RebuiltApiClasses.registerTree(t, CLIENT);
        if (lacks(host.apply(PLAYER_INFO), "getSkinLocation", SKIN)) {
            receiverCall(t, PLAYER_INFO, "getSkinLocation", SKIN, CLIENT, "getSkinLocation", "1.20.2");
        }
        if (lacks(host.apply(CLIENT_PLAYER), "getSkinTextureLocation", SKIN)) {
            for (String owner : CLIENT_PLAYERS) {
                t.registerMethodRedirect(owner, "getSkinTextureLocation", SKIN, CLIENT, "getSkinTextureLocation",
                        "(L" + CLIENT_PLAYER + ";)" + SKIN.substring(2), true);
                t.limitRedirectToModsBefore(owner, "getSkinTextureLocation", SKIN, "1.20.2");
            }
        }
        if (lacks(host.apply(LOCAL_PLAYER), "isRidingJumpable", "()Z")) {
            receiverCall(t, LOCAL_PLAYER, "isRidingJumpable", "()Z", CLIENT, "isRidingJumpable", "1.19.3");
        }
        // 1.19 made ambient occlusion an on and off option, so an old mod that keeps and restores its value holds a Boolean.
        if (host.apply(AO_STATUS) == null) {
            t.registerClassRedirect(AO_STATUS, "java/lang/Boolean");
            for (String[] level : new String[][] {{"OFF", "FALSE"}, {"MIN", "TRUE"}, {"MAX", "TRUE"}}) {
                for (String owner : new String[] {AO_STATUS, "java/lang/Boolean"}) {
                    t.registerFieldRedirect(owner, level[0], "java/lang/Boolean", level[1]);
                }
            }
        }
        // The narrator's empty message became the shared empty component.
        if (lacksField(host.apply(NARRATOR), "EMPTY")) {
            t.registerFieldRedirect(NARRATOR, "EMPTY", "net/minecraft/network/chat/CommonComponents", "EMPTY");
        }
        if (lacksField(host.apply(TOAST_ID), "TUTORIAL_HINT")) {
            t.registerStandInStaticFieldBridge(TOAST_ID, "TUTORIAL_HINT", CLIENT, "tutorialHint", "()L" + TOAST_ID + ";");
        }
    }

    private static void receiverCall(RetromodTransformer t, String owner, String name, String desc, String helper,
            String helperName, String firstWithout) {
        t.registerMethodRedirect(owner, name, desc, helper, helperName, "(L" + owner + ";" + desc.substring(1), true);
        t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
    }

    /** Whether the host class lacks the method; an unreadable class counts as lacking it, since this bridge starts at 26.1. */
    static boolean lacks(ClassNode owner, String name, String desc) {
        if (owner == null) return true;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    static boolean lacksField(ClassNode owner, String name) {
        if (owner == null) return true;
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name)) return false;
        }
        return true;
    }
}
