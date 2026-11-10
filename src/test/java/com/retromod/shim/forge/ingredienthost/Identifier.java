/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

/** Stands in for Minecraft's Identifier: a namespaced id compared by value. */
public final class Identifier {
    private final String id;

    public Identifier(String id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Identifier identifier && identifier.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
