/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;

/**
 * Redirects the {@code MobType} class that 1.20.5 removed to
 * {@link com.retromod.shim.common.embedded.LegacyMobType}, and former
 * {@code LivingEntity.getMobType()} calls to its static lookup.
 *
 * <p>The class rename carries every constant read, subclass, and {@code getMobType()} override
 * descriptor. Calls on {@code LivingEntity}, on the common vanilla mob bases, and on a mod class
 * proven to extend {@code LivingEntity} become the static lookup, which still asks the mob's own
 * override first. Mojang names only.
 */
public final class LegacyMobTypeBridge {

    static final String OLD_TYPE = "net/minecraft/world/entity/MobType";
    static final String BRIDGE = "com/retromod/shim/common/embedded/LegacyMobType";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";

    /**
     * {@code LivingEntity} and the vanilla bases old mobs extend. A {@code super.getMobType()} call
     * names the direct superclass and is an {@code INVOKESPECIAL}, which the inherited redirect
     * does not rewrite, so these owners are matched exactly.
     */
    static final String[] VANILLA_OWNERS = {
        LIVING_ENTITY,
        "net/minecraft/world/entity/Mob",
        "net/minecraft/world/entity/PathfinderMob",
        "net/minecraft/world/entity/AgeableMob",
        "net/minecraft/world/entity/TamableAnimal",
        "net/minecraft/world/entity/FlyingMob",
        "net/minecraft/world/entity/monster/Monster",
        "net/minecraft/world/entity/monster/Zombie",
        "net/minecraft/world/entity/monster/AbstractSkeleton",
        "net/minecraft/world/entity/monster/Spider",
        "net/minecraft/world/entity/monster/AbstractIllager",
        "net/minecraft/world/entity/monster/SpellcasterIllager",
        "net/minecraft/world/entity/monster/PatrollingMonster",
        "net/minecraft/world/entity/raid/Raider",
        "net/minecraft/world/entity/animal/Animal",
        "net/minecraft/world/entity/animal/WaterAnimal",
        "net/minecraft/world/entity/animal/AbstractFish",
        "net/minecraft/world/entity/animal/AbstractGolem",
        "net/minecraft/world/entity/ambient/AmbientCreature",
        "net/minecraft/world/entity/animal/horse/AbstractHorse",
        "net/minecraft/world/entity/npc/AbstractVillager",
        "net/minecraft/world/entity/boss/wither/WitherBoss",
    };

    private LegacyMobTypeBridge() {}

    /** Registers the bridge on hosts at or past 1.20.5. */
    public static void register(RetromodTransformer transformer) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.5") < 0) return;
        if (!SyntheticEmbedder.registerClassResource(transformer, BRIDGE, LegacyMobTypeBridge.class)) return;
        transformer.registerClassRedirect(OLD_TYPE, BRIDGE);
        String lookup = "(Ljava/lang/Object;)L" + BRIDGE + ";";
        // A call on the mod's own mob class reaches the method through inheritance. The visitor
        // sees the descriptor after the class rename, so the inherited form is keyed on it.
        transformer.registerInheritedMethodRedirect(LIVING_ENTITY, "getMobType", "()L" + BRIDGE + ";",
                BRIDGE, "of", lookup);
        // Registered after the inherited form, which also writes a plain LivingEntity entry, so
        // that super calls are devirtualized too.
        for (String owner : VANILLA_OWNERS) {
            transformer.registerMethodRedirect(owner, "getMobType", "()L" + OLD_TYPE + ";",
                    BRIDGE, "of", lookup, true);
        }
    }
}
