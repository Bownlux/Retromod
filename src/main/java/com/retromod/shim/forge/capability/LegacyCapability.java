/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

/**
 * Stand-in for Forge's {@code Capability} token, embedded under Forge's class name.
 *
 * <p>A mod-defined capability is a pure identity token: its own providers answer for it. The
 * built-in Forge capabilities ({@code ITEM_HANDLER}, {@code FLUID_HANDLER}, {@code ENERGY}) also
 * carry NeoForge's block, entity and item capability objects so a query against a vanilla or
 * NeoForge-native holder still reaches NeoForge's capability system.
 */
public final class LegacyCapability<T> {

    private final String name;
    private final Object neoBlock;
    private final Object neoEntity;
    private final Object neoItem;

    public LegacyCapability(String name) {
        this(name, null, null, null);
    }

    public LegacyCapability(String name, Object neoBlock, Object neoEntity, Object neoItem) {
        this.name = name;
        this.neoBlock = neoBlock;
        this.neoEntity = neoEntity;
        this.neoItem = neoItem;
    }

    public String getName() {
        return name;
    }

    public boolean isRegistered() {
        return true;
    }

    /** Forge's {@code cap.orEmpty(requested, instance)}: the instance only for this capability. */
    public <R> LegacyLazyOptional<R> orEmpty(LegacyCapability<R> requested, LegacyLazyOptional<T> instance) {
        return this == requested ? instance.cast() : LegacyLazyOptional.empty();
    }

    public Object neoBlock() {
        return neoBlock;
    }

    public Object neoEntity() {
        return neoEntity;
    }

    public Object neoItem() {
        return neoItem;
    }

    @Override
    public String toString() {
        return "Capability[" + name + "]";
    }
}
