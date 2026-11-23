/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import java.lang.reflect.Field;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.Level;

/** The old minecart constructors that took a position, built with the entity type the current ones need. */
public final class LegacyMinecarts {

    private LegacyMinecarts() {}

    /** {@code new MinecartChest(level, x, y, z)}. */
    public static MinecartChest chest(Level level, double x, double y, double z) {
        MinecartChest cart = new MinecartChest(type("CHEST_MINECART"), level);
        place(cart, x, y, z);
        return cart;
    }

    /** {@code new MinecartTNT(level, x, y, z)}. */
    public static MinecartTNT tnt(Level level, double x, double y, double z) {
        MinecartTNT cart = new MinecartTNT(type("TNT_MINECART"), level);
        place(cart, x, y, z);
        return cart;
    }

    // The old constructors placed the cart and set its previous position too, so it does not slide in on its first frame.
    private static void place(net.minecraft.world.entity.Entity cart, double x, double y, double z) {
        cart.setPos(x, y, z);
        cart.setOldPosAndRot();
    }

    // 26.2 moved the type constants from EntityType to EntityTypes.
    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.world.entity.Entity> EntityType<T> type(String name) {
        for (String holder : new String[]{"net.minecraft.world.entity.EntityTypes", "net.minecraft.world.entity.EntityType"}) {
            try {
                Field field = Class.forName(holder, true, LegacyMinecarts.class.getClassLoader()).getField(name);
                return (EntityType<T>) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException next) {
                // Try the older holder.
            }
        }
        throw new IllegalStateException("Retromod could not find the entity type " + name + " for an old mod's minecart");
    }
}
