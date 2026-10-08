/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.attrstub;

/** {@code LivingEntity.getAttributes()}, under its intermediary name. */
public final class LivingEntity {
    private final AttributeMap attributes;

    public LivingEntity(AttributeMap attributes) {
        this.attributes = attributes;
    }

    public AttributeMap method_6127() {
        return attributes;
    }
}
