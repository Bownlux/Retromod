/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;

/**
 * Forge 1.20.1 classes that NeoForge kept with the same members under its own package.
 *
 * <p>Each pair was checked member by member against NeoForge 21.1: the static entry points,
 * constructors, and interface methods an old mod calls have identical names and descriptors once
 * the owner is renamed. A plain class rename is therefore the whole bridge. Classes whose API
 * changed shape (capabilities, networking, item extensions) are deliberately absent; those need
 * real adapters.
 */
final class ForgeNeoForgeSameShapeMoves {

    private static final String[][] MOVES = {
        // EntitySelectorManager.register(String, IEntitySelectorType) and the two interface methods.
        {"net/minecraftforge/common/command/IEntitySelectorType",
         "net/neoforged/neoforge/common/command/IEntitySelectorType"},
        {"net/minecraftforge/common/command/EntitySelectorManager",
         "net/neoforged/neoforge/common/command/EntitySelectorManager"},
        // EnumArgument.enumArgument(Class) and the ArgumentType methods.
        {"net/minecraftforge/server/command/EnumArgument",
         "net/neoforged/neoforge/server/command/EnumArgument"},
        // WORKQUEUE, CLIENTWORLD, and get(LogicalSide); LogicalSide is already renamed.
        {"net/minecraftforge/common/util/LogicalSidedProvider",
         "net/neoforged/neoforge/common/util/LogicalSidedProvider"},
        // register(type, placement, heightmap, predicate, operation) keeps its shape once the
        // placement enum is renamed by LegacySpawnPlacementTypeBridge; AND, OR, REPLACE are kept.
        {"net/minecraftforge/event/entity/SpawnPlacementRegisterEvent",
         "net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent"},
        {"net/minecraftforge/event/entity/SpawnPlacementRegisterEvent$Operation",
         "net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent$Operation"},
        // All four constructors and getOffer(Entity, RandomSource).
        {"net/minecraftforge/common/BasicItemListing",
         "net/neoforged/neoforge/common/BasicItemListing"},
    };

    private ForgeNeoForgeSameShapeMoves() {}

    /** Registers the renames. Callers apply this only on a NeoForge host. */
    static void register(RetromodTransformer transformer) {
        for (String[] move : MOVES) {
            transformer.registerClassRedirect(move[0], move[1]);
        }
        // Renamed too, but its buffer type changed, so it also needs per-class forwarders.
        LegacySpawnDataAdapter.register(transformer);
    }
}
