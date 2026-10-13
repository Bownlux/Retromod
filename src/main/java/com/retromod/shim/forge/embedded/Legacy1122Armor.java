/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

/**
 * Builds a 1.12.2 mod's armor materials for the armor material bridge on 1.21 and 1.21.1 hosts.
 *
 * <p>1.12.2 made materials with {@code EnumHelper.addArmorMaterial}, which Retromod could not
 * answer because a material is no longer an enum constant, so every armor item built from one
 * failed. The material comes back as the bridge's old-style material, which the armor
 * constructor turns into a registered record. Durability follows 1.12.2: the material's factor
 * times 13, 15, 16, and 11 for boots, leggings, chestplate, and helmet. The texture name the mod
 * gave becomes the armor's texture, as in 1.12.2. The material cannot be repaired, because
 * {@code setRepairItem} came after construction and the record is built from the material once.
 * Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class Legacy1122Armor {

    private static final String SELF = "com.retromod.shim.forge.embedded.Legacy1122Armor";
    /** 1.12.2's per-slot durability, in its slot order: feet, legs, chest, head. */
    private static final int[] DURABILITY = {13, 15, 16, 11};
    private static final String[] SLOTS = {"FEET", "LEGS", "CHEST", "HEAD"};

    private Legacy1122Armor() {}

    /**
     * {@code EnumHelper.addArmorMaterial(name, textureName, durability, reductionAmounts,
     * enchantability, soundOnEquip, toughness)}, or null on a host without the material bridge.
     */
    public static Object material(Object name, Object textureName, int durability, Object reductionAmounts,
            int enchantability, Object soundOnEquip, float toughness) {
        Class<?> material = legacyMaterialInterface();
        if (material == null) return null;
        int[] reductions = reductionAmounts instanceof int[] amounts && amounts.length >= 4 ? amounts : new int[4];
        String texture = String.valueOf(textureName != null ? textureName : name).toLowerCase(Locale.ROOT);
        return Proxy.newProxyInstance(material.getClassLoader(), new Class<?>[] {material}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getDurabilityForType" -> durability * DURABILITY[slotIndex(args[0])];
                    case "getDefenseForType" -> reductions[slotIndex(args[0])];
                    case "getEnchantmentValue" -> enchantability;
                    case "getEquipSound" -> soundOnEquip != null ? soundOnEquip : genericEquipSound(material);
                    case "getRepairIngredient" -> emptyIngredient(material);
                    case "getName" -> texture;
                    case "getToughness" -> toughness;
                    case "getKnockbackResistance" -> 0.0F;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "1.12 armor material " + texture;
                    default -> throw new UnsupportedOperationException("A 1.12 armor material cannot answer "
                            + method.getName() + " on this Minecraft version");
                });
    }

    /** The armor type worn in a 1.12.2 equipment slot. */
    public static Object armorType(Object slot) {
        try {
            Class<?> type = Class.forName("net.minecraft.world.item.ArmorItem$Type", true, slot.getClass().getClassLoader());
            Method getSlot = type.getMethod("getSlot");
            for (Object candidate : type.getEnumConstants()) {
                if (getSlot.invoke(candidate) == slot) return candidate;
            }
            throw new IllegalArgumentException("No armor is worn in the " + slot + " slot");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read this Minecraft version's armor types", e);
        }
    }

    /** The slot an armor type is worn in, in 1.12.2's order; body armor counts as the chest. */
    private static int slotIndex(Object armorType) throws ReflectiveOperationException {
        String slot = String.valueOf(armorType.getClass().getMethod("getSlot").invoke(armorType));
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i].equalsIgnoreCase(slot)) return i;
        }
        return 2;
    }

    private static Object genericEquipSound(Class<?> material) throws ReflectiveOperationException {
        Object holder = Class.forName("net.minecraft.sounds.SoundEvents", true, material.getClassLoader())
                .getField("ARMOR_EQUIP_GENERIC").get(null);
        return holder.getClass().getMethod("value").invoke(holder);
    }

    private static Object emptyIngredient(Class<?> material) throws ReflectiveOperationException {
        return Class.forName("net.minecraft.world.item.crafting.Ingredient", true, material.getClassLoader())
                .getField("EMPTY").get(null);
    }

    /** The bridge's generated material interface, beside this class under the mod's embed prefix. */
    private static Class<?> legacyMaterialInterface() {
        String self = Legacy1122Armor.class.getName();
        String prefix = self.substring(0, self.length() - SELF.length());
        try {
            return Class.forName(prefix + "com.retromod.generated.LegacyArmorMaterial", true,
                    Legacy1122Armor.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
