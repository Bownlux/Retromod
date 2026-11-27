/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import com.retromod.legacyapi.minecraft.LegacyOldOverrides;
import java.lang.reflect.Method;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Forge 1.20.1's item armor texture hook, which Forge-family 26.x hosts removed, answered by the item's own override. */
public final class LegacyArmorTextures {

    static final String TEXTURE = "getArmorTexture";
    private static final Class<?>[] FORGE_SHAPE = {ItemStack.class, Entity.class, EquipmentSlot.class, String.class};

    private LegacyArmorTextures() {}

    /** {@code item.getArmorTexture(stack, entity, slot, type)}, also through Forge's item interface: the item's own override, else null. */
    public static String itemTexture(Object item, ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        Method own = item == null ? null : LegacyOldOverrides.find(item.getClass(), TEXTURE, FORGE_SHAPE);
        if (own == null) return null;
        Object answer = LegacyOldOverrides.call(item, own, stack, entity, slot, type);
        return answer instanceof String texture ? texture : null;
    }

    /** The same call through {@code super}, from the item's own override: null, the old default. */
    public static String itemTextureSuper(Object item, ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return null;
    }

    /** {@code ForgeHooksClient.getArmorTexture(entity, stack, fallback, slot, type)}: the item's texture, else the fallback. */
    public static String hooksTexture(Entity entity, ItemStack stack, String fallback, EquipmentSlot slot, String type) {
        String own = stack == null ? null : itemTexture(stack.getItem(), stack, entity, slot, type);
        return own != null ? own : fallback;
    }

    /** Whether the item still chooses its armor texture in code, through Forge's hook. */
    public static boolean choosesTexture(Item item) {
        return item != null && LegacyOldOverrides.find(item.getClass(), TEXTURE, FORGE_SHAPE) != null;
    }

    /** The item's Forge texture for a worn piece as a texture id, or null when it has none or names no valid one. */
    public static Identifier forgeTexture(ItemStack stack, EquipmentSlot slot, boolean overlay) {
        // The old hook named the overlay of dyeable armor "overlay" and the base texture null.
        String texture = stack == null ? null : itemTexture(stack.getItem(), stack, null, slot, overlay ? "overlay" : null);
        return texture == null ? null : Identifier.tryParse(texture);
    }
}
