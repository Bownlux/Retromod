/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.projectile;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Arrow setters that 1.21 removed or hid when it moved knockback and piercing into the weapon's enchantments. */
public final class LegacyArrowMembers {

    static final int PROTOCOL = 1;

    // Every translated jar's copy shares the table, so a bow from one mod can set it on another mod's arrow.
    @SuppressWarnings("unchecked")
    private static final Map<Object, Object> KNOCKBACK = LegacySharedSlots.get("arrow-knockback", PROTOCOL, Map.class,
            () -> Collections.synchronizedMap(new WeakHashMap<>()));

    // The bow or crossbow firing through an old arrow item, which the arrow it builds takes as its weapon.
    @SuppressWarnings("unchecked")
    private static final ThreadLocal<Object> WEAPON = LegacySharedSlots.get("arrow-weapon", PROTOCOL, ThreadLocal.class,
            ThreadLocal::new);

    private LegacyArrowMembers() {}

    /** Makes {@code weapon} the weapon of arrows built on this thread, and returns the one it replaces for {@link #exitWeapon}. */
    public static Object enterWeapon(Object weapon) {
        Object previous = WEAPON.get();
        // The host refuses an empty weapon, which a missing one is not.
        WEAPON.set(weapon instanceof ItemStack stack && !stack.isEmpty() ? weapon : null);
        return previous;
    }

    /** Puts back what {@link #enterWeapon} returned. */
    public static void exitWeapon(Object previous) {
        if (previous == null) {
            WEAPON.remove();
        } else {
            WEAPON.set(previous);
        }
    }

    /** The weapon of an arrow built on this thread, or null outside an old arrow item's firing. */
    public static ItemStack weapon() {
        return WEAPON.get() instanceof ItemStack stack ? stack : null;
    }

    /** {@code arrow.setKnockback(strength)}; zero or less clears it. */
    public static void setKnockback(Object arrow, int strength) {
        if (arrow == null) return;
        if (strength > 0) {
            KNOCKBACK.put(arrow, strength);
        } else {
            KNOCKBACK.remove(arrow);
        }
    }

    /** The knockback an old mod set on {@code arrow}, or 0. */
    public static int knockback(Object arrow) {
        Object strength = arrow == null ? null : KNOCKBACK.get(arrow);
        return strength instanceof Integer value ? value : 0;
    }

    /** {@code arrow.setPierceLevel(level)}, which 1.21 made private; the arrow keeps its level when that fails. */
    public static void setPierceLevel(Object arrow, byte level) {
        if (arrow == null) return;
        // The setter is private on the host's arrow class, so it is found by walking up from the arrow's own class.
        for (Class<?> type = arrow.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method setter = type.getDeclaredMethod("setPierceLevel", byte.class);
                setter.setAccessible(true);
                setter.invoke(arrow, level);
                return;
            } catch (NoSuchMethodException notHere) {
                // Keep walking: a mod's arrow class does not declare it.
            } catch (ReflectiveOperationException | RuntimeException refused) {
                return;
            }
        }
    }
}
