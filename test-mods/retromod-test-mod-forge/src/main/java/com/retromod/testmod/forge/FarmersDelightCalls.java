/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.registries.RegisterEvent;
import vectorwing.farmersdelight.common.item.KnifeItem;
import vectorwing.farmersdelight.common.registry.ModBlocks;
import vectorwing.farmersdelight.common.registry.ModEffects;
import vectorwing.farmersdelight.common.registry.ModItems;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The #302 calls, written against the Farmer's Delight 1.20.1 API (stand-ins in {@code src/fdstubs}).
 * Loading this class needs the real library, so only {@link FarmersDelightAddon} may load it.
 */
final class FarmersDelightCalls {

    private static final String ENTRY_IDS =
            "farmersdelight:nourishment farmersdelight:tomato farmersdelight:tomatoes";

    private FarmersDelightCalls() {}

    static String registerKnifeAndReadEntries(RegisterEvent.RegisterHelper<Item> helper)
            throws ReflectiveOperationException {
        Item knife = new KnifeItem(Tiers.IRON, 0.5F, -2.0F, new Item.Properties());
        helper.register(new ResourceLocation("retromod_test_mod_forge", "legacy_knife"), knife);
        String ids = ModEffects.NOURISHMENT.getId() + " " + ModItems.TOMATO.getId() + " "
                + ModBlocks.TOMATO_CROP.getId();
        if (!ids.equals(ENTRY_IDS)) return "fail: read the entries " + ids;
        // Iron adds 2 to the knife's 0.5, as it did before 1.20.5 moved damage into a component.
        Double damage = attackDamage(knife);
        return damage == null || damage == 2.5 ? "success" : "fail: the knife deals " + damage;
    }

    /** The knife's base attack damage on a host with item components, or null on an older one. */
    private static Double attackDamage(Item knife) throws ReflectiveOperationException {
        Method components;
        try {
            components = Item.class.getMethod("components");
        } catch (NoSuchMethodException beforeComponents) {
            return null;
        }
        Class<?> type = Class.forName("net.minecraft.core.component.DataComponentType");
        Object attributes = Class.forName("net.minecraft.core.component.DataComponents")
                .getField("ATTRIBUTE_MODIFIERS").get(null);
        Object modifiers = Class.forName("net.minecraft.core.component.DataComponentMap")
                .getMethod("get", type).invoke(components.invoke(knife), attributes);
        for (Object entry : (List<?>) modifiers.getClass().getMethod("modifiers").invoke(modifiers)) {
            Object modifier = entry.getClass().getMethod("modifier").invoke(entry);
            if (String.valueOf(modifier.getClass().getMethod("id").invoke(modifier)).endsWith("base_attack_damage")) {
                return (Double) modifier.getClass().getMethod("amount").invoke(modifier);
            }
        }
        return 0.0;
    }
}
