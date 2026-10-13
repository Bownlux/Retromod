/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Stands in for 1.12.2's {@code EnumEnchantmentType} and 1.20.1's {@code EnchantmentCategory},
 * which 1.20.5 replaced with item tags. A vanilla type names the {@code enchantable} item tag that
 * covers the same items, which the legacy enchantment pack writes as the enchantment's supported
 * items. A type a mod made with {@code EnumHelper.addEnchantmentType} or
 * {@code EnchantmentCategory.create} keeps its item predicate, which the pack runs over every item.
 */
public final class LegacyEnchantmentType {
    public static final LegacyEnchantmentType ALL = new LegacyEnchantmentType("ALL", null);
    public static final LegacyEnchantmentType ARMOR = new LegacyEnchantmentType("ARMOR", null);
    public static final LegacyEnchantmentType ARMOR_FEET = new LegacyEnchantmentType("ARMOR_FEET", null);
    public static final LegacyEnchantmentType ARMOR_LEGS = new LegacyEnchantmentType("ARMOR_LEGS", null);
    public static final LegacyEnchantmentType ARMOR_CHEST = new LegacyEnchantmentType("ARMOR_CHEST", null);
    public static final LegacyEnchantmentType ARMOR_HEAD = new LegacyEnchantmentType("ARMOR_HEAD", null);
    public static final LegacyEnchantmentType WEAPON = new LegacyEnchantmentType("WEAPON", null);
    public static final LegacyEnchantmentType DIGGER = new LegacyEnchantmentType("DIGGER", null);
    public static final LegacyEnchantmentType FISHING_ROD = new LegacyEnchantmentType("FISHING_ROD", null);
    public static final LegacyEnchantmentType TRIDENT = new LegacyEnchantmentType("TRIDENT", null);
    public static final LegacyEnchantmentType BREAKABLE = new LegacyEnchantmentType("BREAKABLE", null);
    public static final LegacyEnchantmentType BOW = new LegacyEnchantmentType("BOW", null);
    public static final LegacyEnchantmentType WEARABLE = new LegacyEnchantmentType("WEARABLE", null);
    public static final LegacyEnchantmentType CROSSBOW = new LegacyEnchantmentType("CROSSBOW", null);
    public static final LegacyEnchantmentType VANISHABLE = new LegacyEnchantmentType("VANISHABLE", null);

    /** The {@code minecraft:enchantable} tag for each vanilla type; it exists from 1.21 through 26.3. */
    private static final Map<String, String> TAGS = Map.ofEntries(
            Map.entry("ARMOR", "armor"), Map.entry("ARMOR_FEET", "foot_armor"), Map.entry("ARMOR_LEGS", "leg_armor"),
            Map.entry("ARMOR_CHEST", "chest_armor"), Map.entry("ARMOR_HEAD", "head_armor"),
            Map.entry("WEAPON", "weapon"), Map.entry("DIGGER", "mining"), Map.entry("FISHING_ROD", "fishing"),
            Map.entry("TRIDENT", "trident"), Map.entry("BREAKABLE", "durability"), Map.entry("BOW", "bow"),
            Map.entry("WEARABLE", "equippable"), Map.entry("CROSSBOW", "crossbow"), Map.entry("VANISHABLE", "vanishing"));

    private final String name;
    private final Object predicate;

    private LegacyEnchantmentType(String name, Object predicate) {
        this.name = name;
        this.predicate = predicate;
    }

    /** {@code EnchantmentCategory.create(name, predicate)} and {@code EnumHelper.addEnchantmentType}. */
    public static LegacyEnchantmentType create(String name, Object itemPredicate) {
        return new LegacyEnchantmentType(name, itemPredicate);
    }

    /** The same call after the jar's calls on this class are erased to {@code Object}. */
    public static LegacyEnchantmentType create(Object name, Object itemPredicate) {
        return create(String.valueOf(name), itemPredicate);
    }

    /** The vanilla item tag this type covers, such as {@code #minecraft:enchantable/mining}, or null. */
    public String itemTag() {
        String tag = predicate == null ? TAGS.get(name) : null;
        return tag == null ? null : "#minecraft:enchantable/" + tag;
    }

    /** Whether the type would enchant {@code item}: its predicate, or yes for a vanilla type. */
    public boolean canEnchant(Object item) {
        if (predicate == null) return true;
        for (Method method : predicate.getClass().getMethods()) {
            String methodName = method.getName();
            if ((methodName.equals("test") || methodName.equals("apply")) && method.getParameterCount() == 1) {
                try {
                    method.setAccessible(true);
                    return Boolean.TRUE.equals(method.invoke(predicate, item));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * {@code CreativeModeTab.setEnchantmentCategories(types)}: a tab listed enchanted books for
     * these types, which tabs no longer do, so the tab is returned unchanged.
     */
    public static Object keepTab(Object tab, Object types) { return tab; }

    public boolean canEnchantItem(Object item) { return canEnchant(item); }
    public boolean func_77557_a(Object item) { return canEnchant(item); }

    public String name() { return name; }

    @Override
    public String toString() { return name; }
}
