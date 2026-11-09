/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.entity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Finds the entity a render state was made from, which render events stopped carrying in Minecraft 1.21.2. */
public final class RenderStateEntities {

    // The states that carry their entity's id, and the field that holds it, from 1.21.11 to 26.3.
    private static final Map<String, String> ID_FIELDS = Map.of(
            "net.minecraft.client.renderer.entity.state.AvatarRenderState", "id",
            "net.minecraft.client.renderer.entity.state.WitchRenderState", "entityId");
    private static final ClassValue<Field> ID_FIELD = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            return idField(type);
        }
    };
    // The last answer for each state, with the fields it was found from, so a state filled again is searched again.
    private static final Map<EntityRenderState, Answer> FOUND = Collections.synchronizedMap(new WeakHashMap<>());
    // The match is near exact, so the search only needs the farthest an entity moves in one tick.
    private static final double REACH = 4.0;
    // A state's position is the entity's, interpolated with the same double arithmetic.
    private static final double POSITION_TOLERANCE = 1.0E-4;

    private RenderStateEntities() {}

    /** The living entity the state was made from, or null when no single entity in the client level matches it. */
    public static LivingEntity livingEntity(EntityRenderState state) {
        return entity(state) instanceof LivingEntity living ? living : null;
    }

    /** The entity the state was made from, or null when no single entity in the client level matches it. */
    public static Entity entity(EntityRenderState state) {
        if (state == null) return null;
        Answer answer = FOUND.get(state);
        // An answer counts only while the state holds what it was found from and the entity still matches it.
        if (answer != null && answer.isFor(state) && (answer.entity() == null || matches(state, answer.entity()))) {
            return answer.entity();
        }
        Entity found = search(state);
        FOUND.put(state, new Answer(found, state.entityType, state.x, state.y, state.z, state.ageInTicks));
        return found;
    }

    private static Entity search(EntityRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft == null ? null : minecraft.level;
        if (level == null || state.entityType == null) return null;
        // A player's or a witch's state names its entity by id. A client-made entity, such as a fake player or a
        // spawner's display witch, can share its id with a real one elsewhere, so the named entity must still match.
        Field id = ID_FIELD.get(state.getClass());
        if (id != null) {
            Entity named = level.getEntity(readId(id, state));
            return named != null && named.getType() == state.entityType && matches(state, named) ? named : null;
        }
        // Otherwise only entities of the state's type near its position can have made it.
        AABB around = new AABB(state.x - REACH, state.y - REACH, state.z - REACH,
                state.x + REACH, state.y + REACH, state.z + REACH);
        List<Entity> candidates = level.getEntities((Entity) null, around, entity -> entity.getType() == state.entityType);
        Entity match = null;
        for (Entity candidate : candidates) {
            if (!matches(state, candidate)) continue;
            // Two entities that fit the same state, as mobs pressed into one farm block do, cannot be told apart.
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    /** Whether the entity, at some partial tick between its last two positions, gives the state's position and age. */
    static boolean matches(EntityRenderState state, Entity entity) {
        double dx = entity.getX() - entity.xOld;
        double dy = entity.getY() - entity.yOld;
        double dz = entity.getZ() - entity.zOld;
        // The age is the tick count plus the partial tick, but a float loses the fraction on an old entity,
        // so the axis the entity moved furthest on gives the partial tick when it moved at all.
        double partial = state.ageInTicks - entity.tickCount;
        double along = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (along > POSITION_TOLERANCE) {
            if (along == Math.abs(dx)) partial = (state.x - entity.xOld) / dx;
            else if (along == Math.abs(dy)) partial = (state.y - entity.yOld) / dy;
            else partial = (state.z - entity.zOld) / dz;
        }
        // The age must agree with that partial tick, up to what a float holds of it.
        double ageSlack = Math.ulp(state.ageInTicks) * 2 + 1.0E-3;
        if (partial < -ageSlack || partial > 1 + ageSlack) return false;
        if (Math.abs(entity.tickCount + partial - state.ageInTicks) > ageSlack) return false;
        return Math.abs(entity.xOld + partial * dx - state.x) <= POSITION_TOLERANCE
                && Math.abs(entity.yOld + partial * dy - state.y) <= POSITION_TOLERANCE
                && Math.abs(entity.zOld + partial * dz - state.z) <= POSITION_TOLERANCE;
    }

    // The id field of the first state class up the hierarchy that has one, found by name so a host without it links.
    private static Field idField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = ID_FIELDS.get(current.getName());
            if (name == null) continue;
            try {
                Field field = current.getField(name);
                return field.getType() == int.class && !Modifier.isStatic(field.getModifiers()) ? field : null;
            } catch (NoSuchFieldException absent) {
                return null;
            }
        }
        return null;
    }

    private static int readId(Field field, EntityRenderState state) {
        try {
            return field.getInt(state);
        } catch (IllegalAccessException refused) {
            // An id that cannot be read names no entity.
            return Integer.MIN_VALUE;
        }
    }

    /** One answer, and the state's type, position and age it was found from. */
    private record Answer(Entity entity, Object type, double x, double y, double z, float age) {
        boolean isFor(EntityRenderState state) {
            return state.entityType == type && state.x == x && state.y == y && state.z == z && state.ageInTicks == age;
        }
    }
}
