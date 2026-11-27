/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common;

import java.util.Set;

/** NeoForge 1.21.1's {@code EffectCures}: the cures vanilla items and the totem applied. */
public final class LegacyEffectCures {

    public static final LegacyEffectCure MILK = LegacyEffectCure.get("milk");
    public static final LegacyEffectCure HONEY = LegacyEffectCure.get("honey");
    public static final LegacyEffectCure PROTECTED_BY_TOTEM = LegacyEffectCure.get("protected_by_totem");
    /** The cures every effect had unless it chose others. */
    public static final Set<LegacyEffectCure> DEFAULT_CURES = Set.of(MILK, PROTECTED_BY_TOTEM);

    private LegacyEffectCures() {}
}
