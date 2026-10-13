/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2 Forge's {@code CompoundDataFixer}, which a mod asked for its own
 * {@code ModFixs} to upgrade its saved block entities between its own versions. 1.13 replaced
 * that with Mojang's DataFixerUpper, and a 1.12 save cannot be loaded by a current host anyway, so
 * the fixes are accepted and never run.
 */
public final class LegacyCompoundDataFixer {

    public static final LegacyCompoundDataFixer INSTANCE = new LegacyCompoundDataFixer();

    private LegacyCompoundDataFixer() {}

    /**
     * {@code FMLCommonHandler.instance().getDataFixer()}. The handler stand-in loads from Retromod
     * itself, so it cannot name this per-mod class; the call comes here instead.
     */
    public static LegacyCompoundDataFixer of(Object handler) {
        return INSTANCE;
    }

    /** {@code init(modId, version)}: a fix list that keeps nothing. */
    public LegacyModFixs init(Object modId, int version) {
        return new LegacyModFixs();
    }
}
