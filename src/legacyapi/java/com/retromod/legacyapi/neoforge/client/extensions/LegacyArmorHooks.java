/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.extensions;

import com.retromod.legacyapi.minecraft.LegacyOldOverrides;
import com.retromod.legacyapi.minecraft.world.item.LegacyArmorTextures;
import java.lang.reflect.Method;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/** Forge 1.20.1's and NeoForge 21.1's armor model, texture and tint hooks on NeoForge 26.x, answered by old overrides or the host. */
public final class LegacyArmorHooks {

    private static final String HUMANOID = "getHumanoidArmorModel", GENERIC = "getGenericArmorModel";
    private static final String TEXTURE = "getArmorTexture", TINT = "getArmorLayerTintColor";
    private static final Class<?>[] OLD_MODEL = {LivingEntity.class, ItemStack.class, EquipmentSlot.class, HumanoidModel.class};
    // A null type is NeoForge 21.1's armor layer, which each translated mod carries its own copy of.
    private static final Class<?>[] OLD_ITEM_TEXTURE = {ItemStack.class, Entity.class, EquipmentSlot.class, null, boolean.class};
    private static final Class<?>[] OLD_EXT_TEXTURE = {ItemStack.class, Entity.class, EquipmentSlot.class, null, boolean.class, Identifier.class};
    private static final Class<?>[] OLD_TINT = {ItemStack.class, LivingEntity.class, null, int.class, int.class};
    private static final String OVERLAY = "_overlay";

    private LegacyArmorHooks() {}

    // ---- calls an old mod makes ----

    /** {@code ext.getHumanoidArmorModel(entity, stack, slot, original)}: the old override, else the host's hook for the slot. */
    public static HumanoidModel<?> getHumanoidArmorModel(IClientItemExtensions ext, LivingEntity entity, ItemStack stack,
            EquipmentSlot slot, HumanoidModel<?> original) {
        if (ext == null) return original;
        Method own = LegacyOldOverrides.find(ext.getClass(), HUMANOID, OLD_MODEL);
        Object answer = own != null ? LegacyOldOverrides.call(ext, own, entity, stack, slot, original)
                : ext.getHumanoidArmorModel(stack, layerType(slot), original);
        return answer instanceof HumanoidModel<?> model ? model : original;
    }

    /** The same call through {@code super}, from the override itself: the old default, the model it was given. */
    public static HumanoidModel<?> getHumanoidArmorModelSuper(IClientItemExtensions ext, LivingEntity entity, ItemStack stack,
            EquipmentSlot slot, HumanoidModel<?> original) {
        return original;
    }

    /** {@code ext.getGenericArmorModel(entity, stack, slot, original)}: the old override, else the old default. */
    public static Model getGenericArmorModel(IClientItemExtensions ext, LivingEntity entity, ItemStack stack, EquipmentSlot slot,
            HumanoidModel<?> original) {
        Method own = ext == null ? null : LegacyOldOverrides.find(ext.getClass(), GENERIC, OLD_MODEL);
        if (own == null) return getGenericArmorModelSuper(ext, entity, stack, slot, original);
        Object answer = LegacyOldOverrides.call(ext, own, entity, stack, slot, original);
        return answer instanceof Model model ? model : original;
    }

    /** The old default of the generic hook, which asked the humanoid hook. */
    public static Model getGenericArmorModelSuper(IClientItemExtensions ext, LivingEntity entity, ItemStack stack, EquipmentSlot slot,
            HumanoidModel<?> original) {
        return getHumanoidArmorModel(ext, entity, stack, slot, original);
    }

    /** NeoForge 21.1's {@code ext.getArmorLayerTintColor(stack, entity, layer, index, fallback)}: the old override, else the fallback. */
    public static int getArmorLayerTintColor(IClientItemExtensions ext, ItemStack stack, LivingEntity entity, Object layer, int index,
            int fallback) {
        Method own = ext == null ? null : LegacyOldOverrides.find(ext.getClass(), TINT, OLD_TINT);
        Object answer = own == null ? null : LegacyOldOverrides.call(ext, own, stack, entity, layer, index, fallback);
        return answer instanceof Integer color ? color : fallback;
    }

    public static int getArmorLayerTintColorSuper(IClientItemExtensions ext, ItemStack stack, LivingEntity entity, Object layer, int index,
            int fallback) {
        return fallback;
    }

    /** Forge 1.20.1's {@code renderHelmetOverlay}: nothing, the old default; 26.x never calls this form, so an override of it is left alone. */
    public static void renderHelmetOverlay(IClientItemExtensions ext, ItemStack stack, Player player, int width, int height, float partialTick) {}

    /** NeoForge 21.1's {@code renderHelmetOverlay(stack, player, graphics, deltaTracker)}: nothing, for the same reason. */
    public static void renderHelmetOverlay(IClientItemExtensions ext, ItemStack stack, Player player, Object graphics, Object deltaTracker) {}

    /** The hand transform taking the local player, which NeoForge 26.3 no longer calls: false, the old default. */
    public static boolean applyForgeHandTransform(IClientItemExtensions ext, Object poseStack, Object player, Object arm, ItemStack stack,
            float partialTick, float equipProgress, float swingProgress) {
        return false;
    }

    /** NeoForge 21.1's and Forge 1.20.1's {@code getArmorModel(entity, stack, slot, original)}: the item extension's generic hook. */
    public static Model getArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel<?> original) {
        return getGenericArmorModel(stack == null ? null : IClientItemExtensions.of(stack), entity, stack, slot, original);
    }

    /** NeoForge 21.1's {@code ClientHooks.getArmorTexture(entity, stack, layer, inner, slot)}: the item's old texture, else the layer's. */
    public static Identifier getArmorTexture(Entity entity, ItemStack stack, Object layer, boolean inner, EquipmentSlot slot) {
        Identifier own = itemTexture(stack, entity, slot, layer, null, inner, false);
        return own != null ? own : oldLayerTexture(layer, inner);
    }

    // ---- the host's hooks, for an old extension; null tells the added hook to ask the inherited one ----

    /** 26.x's {@code getHumanoidArmorModel(stack, type, original)} of an old extension: its old override, for a humanoid slot. */
    public static Model humanoidModel(IClientItemExtensions ext, ItemStack stack, EquipmentClientInfo.LayerType type, Model original) {
        EquipmentSlot slot = humanoidSlot(stack, type);
        Method own = slot == null || ext == null ? null : LegacyOldOverrides.find(ext.getClass(), HUMANOID, OLD_MODEL);
        if (own == null) return null;
        // The new hook names no entity; most old overrides choose by the item alone.
        Object answer = LegacyOldOverrides.call(ext, own, null, stack, slot, original instanceof HumanoidModel<?> humanoid ? humanoid : null);
        return answer instanceof HumanoidModel<?> model && answer != original ? model : null;
    }

    /** 26.x's {@code getGenericArmorModel(stack, type, original)} of an old extension: its old override's humanoid model. */
    public static Model genericModel(IClientItemExtensions ext, ItemStack stack, EquipmentClientInfo.LayerType type, Model original) {
        EquipmentSlot slot = humanoidSlot(stack, type);
        Method own = slot == null || ext == null ? null : LegacyOldOverrides.find(ext.getClass(), GENERIC, OLD_MODEL);
        HumanoidModel<?> humanoid = original instanceof HumanoidModel<?> model ? model : null;
        Object answer = own == null ? null : LegacyOldOverrides.call(ext, own, null, stack, slot, humanoid);
        // An old plain model drew itself through a method 26.x renamed, so only a humanoid model draws here.
        if (!(answer instanceof HumanoidModel<?> replacement) || answer == original) return null;
        // The host poses a replacement by copying the model it replaces, as its default generic hook does.
        if (humanoid != null) ClientHooks.copyModelProperties(humanoid, replacement);
        return replacement;
    }

    /** 26.x's {@code getArmorTexture(stack, type, layer, fallback)}: the extension's NeoForge 21.1 override, else the item's old one. */
    public static Identifier armorTexture(IClientItemExtensions ext, ItemStack stack, EquipmentClientInfo.LayerType type,
            EquipmentClientInfo.Layer layer, Identifier fallback) {
        EquipmentSlot slot = humanoidSlot(stack, type);
        if (slot == null) return null;
        boolean inner = slot == EquipmentSlot.LEGS;
        Method own = ext == null ? null : LegacyOldOverrides.find(ext.getClass(), TEXTURE, OLD_EXT_TEXTURE);
        if (own != null) {
            Object answer = LegacyOldOverrides.call(ext, own, stack, null, slot, oldLayer(own, 3, layer), inner, fallback);
            if (answer instanceof Identifier texture) return texture;
        }
        return itemTexture(stack, null, slot, null, layer, inner, isOverlay(layer));
    }

    /** 26.x's {@code getArmorLayerTintColor(stack, layer, index, fallback)} of an old extension: its NeoForge 21.1 override. */
    public static Integer tintColor(IClientItemExtensions ext, ItemStack stack, EquipmentClientInfo.Layer layer, int index, int fallback) {
        Method own = ext == null ? null : LegacyOldOverrides.find(ext.getClass(), TINT, OLD_TINT);
        Object answer = own == null ? null : LegacyOldOverrides.call(ext, own, stack, null, oldLayer(own, 2, layer), index, fallback);
        return answer instanceof Integer color ? color : null;
    }

    /** Whether the item still chooses its armor texture in code, in Forge 1.20.1's or NeoForge 21.1's form. */
    public static boolean choosesTexture(net.minecraft.world.item.Item item) {
        return LegacyArmorTextures.choosesTexture(item)
                || item != null && LegacyOldOverrides.find(item.getClass(), TEXTURE, OLD_ITEM_TEXTURE) != null;
    }

    // ---- shapes ----

    // The item's NeoForge 21.1 texture, given the old layer or one made from the host's, else its Forge 1.20.1 texture.
    private static Identifier itemTexture(ItemStack stack, Entity entity, EquipmentSlot slot, Object oldLayer,
            EquipmentClientInfo.Layer layer, boolean inner, boolean overlay) {
        if (stack == null || stack.isEmpty()) return null;
        Method own = LegacyOldOverrides.find(stack.getItem().getClass(), TEXTURE, OLD_ITEM_TEXTURE);
        if (own != null) {
            Object given = oldLayer != null ? oldLayer : oldLayer(own, 3, layer);
            Object answer = given == null ? null : LegacyOldOverrides.call(stack.getItem(), own, stack, entity, slot, given, inner);
            if (answer instanceof Identifier texture) return texture;
        }
        return LegacyArmorTextures.forgeTexture(stack, slot, overlay);
    }

    /** The slot a humanoid layer draws for: leggings from the layer, the rest from the item; null for other layers. */
    static EquipmentSlot humanoidSlot(ItemStack stack, EquipmentClientInfo.LayerType type) {
        if (type == EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS) return EquipmentSlot.LEGS;
        // Baby, wing, saddle and animal body layers never reached the old hooks.
        if (type != EquipmentClientInfo.LayerType.HUMANOID) return null;
        Equippable equippable = stack == null ? null : stack.get(DataComponents.EQUIPPABLE);
        EquipmentSlot slot = equippable == null ? null : equippable.slot();
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.FEET ? slot : EquipmentSlot.CHEST;
    }

    private static EquipmentClientInfo.LayerType layerType(EquipmentSlot slot) {
        return slot == EquipmentSlot.LEGS ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS : EquipmentClientInfo.LayerType.HUMANOID;
    }

    // The overlay layer of dyeable armor, whose texture id ends with the old overlay suffix.
    private static boolean isOverlay(EquipmentClientInfo.Layer layer) {
        return layer != null && layer.textureId().getPath().endsWith(OVERLAY);
    }

    /** The old armor layer an override takes at {@code index}, built from the host layer's texture id, or null. */
    private static Object oldLayer(Method method, int index, EquipmentClientInfo.Layer layer) {
        if (layer == null) return null;
        Class<?> type = method.getParameterTypes()[index];
        Identifier id = layer.textureId();
        try {
            if (!isOverlay(layer)) return type.getConstructor(Identifier.class).newInstance(id);
            // The old overlay layer was the base asset with an "_overlay" suffix, which its texture path keeps.
            String path = id.getPath();
            Identifier asset = id.withPath(path.substring(0, path.length() - OVERLAY.length()));
            return type.getConstructor(Identifier.class, String.class, boolean.class).newInstance(asset, OVERLAY, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError noLayer) {
            return null;
        }
    }

    /** The texture an old layer named, in the old armor folder: {@code textures/models/armor/<name>_layer_1.png} or {@code _2}. */
    private static Identifier oldLayerTexture(Object layer, boolean inner) {
        if (layer == null) return null;
        try {
            Identifier asset = (Identifier) layer.getClass().getMethod("assetName").invoke(layer);
            return asset.withPath("textures/models/armor/" + asset.getPath() + "_layer_" + (inner ? 2 : 1) + ".png");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError noName) {
            return null;
        }
    }
}
