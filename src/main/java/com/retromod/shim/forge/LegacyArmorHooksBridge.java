/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

import java.util.List;

/** Old Forge and NeoForge armor model, texture and tint hooks and the ender mask hook, on the Forge-family 26.x hosts that reshaped them. */
public final class LegacyArmorHooksBridge {

    private static final String NEO = "net/neoforged/neoforge/";
    private static final String FORGE = "net/minecraftforge/";
    public static final String EXT = "client/extensions/common/IClientItemExtensions";
    static final String NEO_HOOKS = RebuiltApiClasses.ROOT + "neoforge/client/extensions/LegacyArmorHooks";
    static final String FORGE_HOOKS = RebuiltApiClasses.ROOT + "forge/client/LegacyForgeArmorHooks";
    static final String TEXTURES = RebuiltApiClasses.ROOT + "minecraft/world/item/LegacyArmorTextures";
    static final String DISGUISES = RebuiltApiClasses.ROOT + "minecraft/world/item/LegacyDisguises";

    private static final String STACK = LegacyArmorOverrideAdapter.STACK;
    private static final String SLOT = LegacyArmorOverrideAdapter.SLOT;
    private static final String LIVING = LegacyArmorOverrideAdapter.LIVING;
    private static final String HUMANOID = LegacyArmorOverrideAdapter.HUMANOID;
    private static final String PLAYER = LegacyArmorOverrideAdapter.PLAYER;
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String OBJECT = "Ljava/lang/Object;";
    /** NeoForge 21.1's armor layer, which the 26.x armor material bridge gives each mod as its own class. */
    static final String OLD_LAYER = "Lcom/retromod/generated/LegacyArmorLayer;";
    public static final String OLD_MODEL_ARGS = "(" + LIVING + STACK + SLOT + HUMANOID + ")";
    /** Forge 1.20.1's item hook, {@code getArmorTexture(stack, entity, slot, type)}. */
    static final String OLD_ITEM_TEXTURE = "(" + STACK + ENTITY + SLOT + STRING + ")" + STRING;
    /** Forge 1.20.1's {@code ForgeHooksClient.getArmorTexture(entity, stack, fallback, slot, type)}. */
    static final String OLD_HOOKS_TEXTURE = "(" + ENTITY + STACK + STRING + SLOT + STRING + ")" + STRING;
    // An old armor item's super call names its armor base, which the 26.x equipment bridge replaces with generated ones.
    private static final List<String> ARMOR_ITEMS = List.of("net/minecraft/world/item/ArmorItem", "net/minecraft/world/item/DyeableArmorItem",
            "com/retromod/generated/LegacyArmorItem", "com/retromod/generated/LegacyDyeableArmorItem");
    private static final List<String> ENDERMEN = List.of("Lnet/minecraft/world/entity/monster/EnderMan;", "Lnet/minecraft/world/entity/monster/Enderman;");

    private LegacyArmorHooksBridge() {}

    /**
     * NeoForge 26.x, whose extension hooks take an item and an equipment layer. {@code handTransformGone} is NeoForge 26.3,
     * which replaced the local player in the hand transform with its render state.
     */
    public static void registerNeoForge(RetromodTransformer t, boolean armorGone, boolean disguiseGone, boolean handTransformGone) {
        if (armorGone) {
            RebuiltApiClasses.registerTree(t, NEO_HOOKS);
            RebuiltApiClasses.registerTree(t, TEXTURES);
            for (String owner : List.of(NEO + EXT, FORGE + EXT)) registerNeoForgeExtensionCalls(t, owner, handTransformGone);
            for (String hooks : List.of(NEO + "client/ClientHooks", FORGE + "client/ForgeHooksClient")) {
                t.registerMethodRedirect(hooks, "getArmorModel", OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL,
                        NEO_HOOKS, "getArmorModel", OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL);
            }
            t.registerMethodRedirect(NEO + "client/ClientHooks", "getArmorTexture", "(" + ENTITY + STACK + OLD_LAYER + "Z" + SLOT + ")"
                    + LegacyArmorOverrideAdapter.ID, NEO_HOOKS, "getArmorTexture", "(" + ENTITY + STACK + OBJECT + "Z" + SLOT + ")"
                    + LegacyArmorOverrideAdapter.ID);
            registerForgeTextureCalls(t, List.of("net/minecraft/world/item/Item", NEO + "common/extensions/IItemExtension",
                    FORGE + "common/extensions/IForgeItem"));
            t.enablePostProcess(LegacyArmorOverrideAdapter.NEO_ARMOR);
        }
        if (disguiseGone) {
            registerDisguise(t, NEO + "common/extensions/IItemStackExtension", NEO + "common/extensions/IItemExtension", "isGazeDisguise", LIVING);
            t.enablePostProcess(LegacyArmorOverrideAdapter.NEO_DISGUISE);
        }
    }

    /** Forge 26.2 and newer, whose model hooks take render states and which has no armor texture hook. */
    public static void registerForge(RetromodTransformer t, boolean armorGone, boolean disguiseGone) {
        if (armorGone) {
            RebuiltApiClasses.registerTree(t, FORGE_HOOKS);
            RebuiltApiClasses.registerTree(t, TEXTURES);
            String self = "(L" + FORGE + EXT + ";";
            redirectWithSuper(t, FORGE + EXT, "getHumanoidArmorModel", OLD_MODEL_ARGS + HUMANOID, FORGE_HOOKS, self);
            redirectWithSuper(t, FORGE + EXT, "getGenericArmorModel", OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL, FORGE_HOOKS, self);
            t.registerMethodRedirect(FORGE + "client/ForgeHooksClient", "getArmorModel", OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL,
                    FORGE_HOOKS, "getArmorModel", OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL);
            registerForgeTextureCalls(t, List.of("net/minecraft/world/item/Item", FORGE + "common/extensions/IForgeItem"));
            t.enablePostProcess(LegacyArmorOverrideAdapter.FORGE_ARMOR);
        }
        if (disguiseGone) {
            registerDisguise(t, FORGE + "common/extensions/IForgeItemStack", FORGE + "common/extensions/IForgeItem", "isMonsterDisguise",
                    "Lnet/minecraft/world/entity/monster/Monster;");
            t.enablePostProcess(LegacyArmorOverrideAdapter.FORGE_DISGUISE);
        }
    }

    // Calls through an extension: the model hooks dispatch to the old override, the rest answer the old default.
    private static void registerNeoForgeExtensionCalls(RetromodTransformer t, String owner, boolean handTransformGone) {
        String self = "(L" + NEO + EXT + ";";
        redirectWithSuper(t, owner, "getHumanoidArmorModel", OLD_MODEL_ARGS + HUMANOID, NEO_HOOKS, self);
        redirectWithSuper(t, owner, "getGenericArmorModel", OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL, NEO_HOOKS, self);
        t.registerMethodRedirect(owner, "getArmorLayerTintColor", "(" + STACK + LIVING + OLD_LAYER + "II)I",
                NEO_HOOKS, "getArmorLayerTintColor", self + STACK + LIVING + OBJECT + "II)I", true);
        t.registerSuperCallHelper(NEO_HOOKS, "getArmorLayerTintColor", self + STACK + LIVING + OBJECT + "II)I", "getArmorLayerTintColorSuper");
        t.registerMethodRedirect(owner, "renderHelmetOverlay", "(" + STACK + PLAYER + "IIF)V",
                NEO_HOOKS, "renderHelmetOverlay", self + STACK + PLAYER + "IIF)V", true);
        // NeoForge 21.1's overlay took the GUI graphics, which the 26.x GUI bridge renames.
        for (String graphics : List.of("Lnet/minecraft/client/gui/GuiGraphics;", "Lnet/minecraft/client/gui/GuiGraphicsExtractor;")) {
            t.registerMethodRedirect(owner, "renderHelmetOverlay", "(" + STACK + PLAYER + graphics + "Lnet/minecraft/client/DeltaTracker;)V",
                    NEO_HOOKS, "renderHelmetOverlay", self + STACK + PLAYER + OBJECT + OBJECT + ")V", true);
        }
        if (handTransformGone) {
            t.registerMethodRedirect(owner, "applyForgeHandTransform", "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/player/LocalPlayer;"
                    + "Lnet/minecraft/world/entity/HumanoidArm;" + STACK + "FFF)Z", NEO_HOOKS, "applyForgeHandTransform",
                    self + OBJECT + OBJECT + OBJECT + STACK + "FFF)Z", true);
        }
    }

    /** A call to the old hook goes to the helper, and a {@code super} call from an override of it to the helper's old default. */
    private static void redirectWithSuper(RetromodTransformer t, String owner, String name, String desc, String helper, String self) {
        String helperDesc = self + desc.substring(1);
        t.registerMethodRedirect(owner, name, desc, helper, name, helperDesc, true);
        t.registerSuperCallHelper(helper, name, helperDesc, name + "Super");
    }

    // Forge 1.20.1's item texture hook and ForgeHooksClient's read of it, the same on both hosts.
    private static void registerForgeTextureCalls(RetromodTransformer t, List<String> itemOwners) {
        String itemDesc = "(" + OBJECT + OLD_ITEM_TEXTURE.substring(1);
        List<String> owners = new java.util.ArrayList<>(itemOwners);
        owners.addAll(ARMOR_ITEMS);
        for (String owner : owners) {
            t.registerMethodRedirect(owner, "getArmorTexture", OLD_ITEM_TEXTURE, TEXTURES, "itemTexture", itemDesc, true);
        }
        t.registerSuperCallHelper(TEXTURES, "itemTexture", itemDesc, "itemTextureSuper");
        t.registerMethodRedirect(FORGE + "client/ForgeHooksClient", "getArmorTexture", OLD_HOOKS_TEXTURE, TEXTURES, "hooksTexture", OLD_HOOKS_TEXTURE);
    }

    /** The old {@code isEnderMask} calls on a stack and an item go to the host's disguise hook, which takes any mob of {@code target}. */
    private static void registerDisguise(RetromodTransformer t, String stackExtension, String itemExtension, String hook, String target) {
        RebuiltApiClasses.registerTree(t, DISGUISES);
        String oldStackExtension = FORGE + "common/extensions/IForgeItemStack";
        String oldItemExtension = FORGE + "common/extensions/IForgeItem";
        for (String enderman : ENDERMEN) {
            String stackDesc = "(" + PLAYER + enderman + ")Z";
            String itemDesc = "(" + STACK + PLAYER + enderman + ")Z";
            t.registerMethodRedirect("net/minecraft/world/item/ItemStack", "isEnderMask", stackDesc,
                    "net/minecraft/world/item/ItemStack", hook, "(" + PLAYER + target + ")Z");
            t.registerMethodRedirect("net/minecraft/world/item/Item", "isEnderMask", itemDesc,
                    "net/minecraft/world/item/Item", hook, "(" + STACK + PLAYER + target + ")Z");
            for (String owner : new java.util.LinkedHashSet<>(List.of(oldStackExtension, stackExtension))) {
                t.registerMethodRedirect(owner, "isEnderMask", stackDesc, stackExtension, hook, "(" + PLAYER + target + ")Z");
            }
            for (String owner : new java.util.LinkedHashSet<>(List.of(oldItemExtension, itemExtension))) {
                t.registerMethodRedirect(owner, "isEnderMask", itemDesc, itemExtension, hook, "(" + STACK + PLAYER + target + ")Z");
            }
        }
    }
}
