/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

/**
 * Stands in for the {@code CompoundTag tradeOffers} field of {@code ZombieVillager}.
 *
 * <p>1.20.5 changed that field to a {@code MerchantOffers} object and made the cure path pass it
 * on directly, so the old NBT form no longer exists. Encoding offers back to NBT needs the level's
 * registry access and the new codec, and an old mod has no way to read that tag back into offers
 * on the new version anyway. The getter therefore reports no saved offers, which the field
 * already allowed (it was nullable), and the setter ignores its argument. A villager converted by
 * such a mod starts with fresh trades instead of the zombie's remembered ones.
 * {@link com.retromod.mixin.MixinAccessorFieldMigration} points the old accessors here.
 */
public final class ZombieVillagerBridge {

    private ZombieVillagerBridge() {}

    /** No saved trade offers in the old NBT form. */
    public static Object tradeOffersTag(Object zombieVillager) {
        return null;
    }

    /** Ignores the old NBT form; the zombie keeps its current offers. */
    public static void setTradeOffersTag(Object zombieVillager, Object tag) {
        // Intentionally empty: see the class comment.
    }
}
