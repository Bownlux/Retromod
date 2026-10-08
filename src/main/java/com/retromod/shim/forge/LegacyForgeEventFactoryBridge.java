/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;

import java.util.function.Predicate;

/**
 * Moves Forge's {@code ForgeEventFactory} calls onto NeoForge's {@code EventHooks}.
 *
 * <p>NeoForge renamed the class and kept most hooks with the same name and descriptor, such as
 * {@code onExplosionStart}, {@code onExplosionDetonate}, {@code onAnimalTame},
 * {@code onEntityDestroyBlock}, {@code getExperienceDrop}, and the living conversion hooks. Two
 * hooks a mob mod commonly calls changed: the mob griefing check is now {@code canEntityGrief},
 * and the finalize-spawn hook is {@code finalizeMobSpawn} without the NBT argument 1.20.5 removed
 * from {@code finalizeSpawn}. A hook NeoForge removed still fails when it is called.
 */
final class LegacyForgeEventFactoryBridge {

    static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
    static final String NEO = "net/neoforged/neoforge/event/EventHooks";

    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String SPAWN_ARGS = "Lnet/minecraft/world/entity/Mob;"
            + "Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;"
            + "Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;";
    private static final String SPAWN_DATA = "Lnet/minecraft/world/entity/SpawnGroupData;";

    private LegacyForgeEventFactoryBridge() {}

    static boolean register(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE) || !hostHasClass.test(NEO)) return false;
        transformer.registerClassRedirect(FORGE, NEO);
        String grief = "(" + LEVEL + ENTITY + ")Z";
        transformer.registerMethodRedirect(FORGE, "getMobGriefingEvent", grief, NEO, "canEntityGrief", grief);
        // Arg-drop redirects have no owner alias, and the class rename reaches the call first.
        for (String owner : new String[] {FORGE, NEO}) {
            transformer.registerArgDropMethodRedirect(owner, "onFinalizeSpawn",
                    "(" + SPAWN_ARGS + "Lnet/minecraft/nbt/CompoundTag;)" + SPAWN_DATA,
                    NEO, "finalizeMobSpawn", "(" + SPAWN_ARGS + ")" + SPAWN_DATA);
        }
        return true;
    }
}
