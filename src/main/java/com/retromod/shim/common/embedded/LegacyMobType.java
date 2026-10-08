/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;

/**
 * Stands in for {@code net.minecraft.world.entity.MobType}, which 1.20.5 removed.
 *
 * <p>Old mods compare {@code entity.getMobType()} against the five constants, override
 * {@code getMobType()} on their own mobs, and sometimes subclass the type to add their own. Since
 * 1.20.5 the game decides "undead" and the others from entity type tags instead. This class keeps
 * those mods loading and answering the same questions:
 * <ul>
 *   <li>The constants are instances of this class, so identity comparisons keep working.</li>
 *   <li>{@link #of} answers a former {@code LivingEntity.getMobType()} call. It prefers the
 *       mob's own legacy override, then falls back to the matching entity type tag.</li>
 * </ul>
 * Vanilla code no longer asks for a mob type, so a mod mob that only overrides
 * {@code getMobType()} is not treated as undead by vanilla potions or enchantments unless its
 * entity type is also in the tag.
 *
 * <p>No compile-time Minecraft dependency: it is copied into each mod that needs it, and every
 * Minecraft lookup is reflective. Lookup failures answer {@link #UNDEFINED}.
 */
public class LegacyMobType {

    public static final LegacyMobType UNDEFINED = new LegacyMobType();
    public static final LegacyMobType UNDEAD = new LegacyMobType();
    public static final LegacyMobType ARTHROPOD = new LegacyMobType();
    public static final LegacyMobType ILLAGER = new LegacyMobType();
    public static final LegacyMobType WATER = new LegacyMobType();

    /** Tag field names on {@code EntityTypeTags}, in the order they are checked. */
    private static final String[][] TAGGED_TYPES = {
        {"UNDEAD"}, {"ARTHROPOD"}, {"ILLAGER"}, {"AQUATIC"},
    };

    public LegacyMobType() {}

    /** The mob type an old {@code getMobType()} call would have returned for {@code entity}. */
    public static LegacyMobType of(Object entity) {
        if (entity == null) return UNDEFINED;
        // An override that ends in super.getMobType() comes back here for the same mob. The inner
        // ask answers what vanilla would have, where calling the override again would never end.
        java.util.Set<Object> asking = ASKING.get();
        if (asking.add(entity)) {
            try {
                LegacyMobType declared = declaredByLegacyOverride(entity);
                if (declared != null) return declared;
            } finally {
                asking.remove(entity);
            }
        }
        return fromTags(entity);
    }

    private static final ThreadLocal<java.util.Set<Object>> ASKING = ThreadLocal.withInitial(
            () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    private static LegacyMobType fromTags(Object entity) {
        try {
            Object type = entity.getClass().getMethod("getType").invoke(entity);
            Class<?> tags = Class.forName("net.minecraft.tags.EntityTypeTags", false,
                    entity.getClass().getClassLoader());
            Method is = findIs(type.getClass());
            if (is == null) return UNDEFINED;
            LegacyMobType[] answers = {UNDEAD, ARTHROPOD, ILLAGER, WATER};
            for (int i = 0; i < TAGGED_TYPES.length; i++) {
                Object tag = tags.getField(TAGGED_TYPES[i][0]).get(null);
                if (Boolean.TRUE.equals(is.invoke(type, tag))) return answers[i];
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Fall through to UNDEFINED, which is what most mobs were.
        }
        return UNDEFINED;
    }

    /** A mob's own {@code getMobType()} override that returns this class, if it has one. */
    private static LegacyMobType declaredByLegacyOverride(Object entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals("getMobType") && method.getParameterCount() == 0
                        && LegacyMobType.class.isAssignableFrom(method.getReturnType())) {
                    try {
                        method.setAccessible(true);
                        return (LegacyMobType) method.invoke(entity);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static Method findIs(Class<?> entityType) {
        for (Method method : entityType.getMethods()) {
            if (method.getName().equals("is") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].getName().equals("net.minecraft.tags.TagKey")) {
                return method;
            }
        }
        return null;
    }
}
