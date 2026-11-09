/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Stand-in for the host Level: its entities, and how often it was searched. */
public class Level {
    public final List<Entity> entities = new ArrayList<>();
    public int searches;

    public Entity getEntity(int id) {
        for (Entity entity : entities) {
            if (entity.id == id) return entity;
        }
        return null;
    }

    public List<Entity> getEntities(Entity except, AABB area, Predicate<? super Entity> filter) {
        searches++;
        List<Entity> found = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity != except && area.contains(entity) && filter.test(entity)) found.add(entity);
        }
        return found;
    }
}
