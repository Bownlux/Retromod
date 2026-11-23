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
        // test, and, negate and or; also checked against NeoForge 26.3. Spawn placement tests are built on it.
        {"net/minecraftforge/common/util/TriPredicate",
         "net/neoforged/neoforge/common/util/TriPredicate"},
        // register(type, placement, heightmap, predicate, operation) keeps its shape once the
        // placement enum is renamed by LegacySpawnPlacementTypeBridge; AND, OR, REPLACE are kept.
        {"net/minecraftforge/event/entity/SpawnPlacementRegisterEvent",
         "net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent"},
        {"net/minecraftforge/event/entity/SpawnPlacementRegisterEvent$Operation",
         "net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent$Operation"},
        // All four constructors and getOffer(Entity, RandomSource).
        {"net/minecraftforge/common/BasicItemListing",
         "net/neoforged/neoforge/common/BasicItemListing"},
        // A fluid type's bucket sounds: the three constants, get(String) and name(); also checked against 26.3.
        {"net/minecraftforge/common/SoundAction", "net/neoforged/neoforge/common/SoundAction"},
        {"net/minecraftforge/common/SoundActions", "net/neoforged/neoforge/common/SoundActions"},
        // A key mapping's conflict context: the three constants, isActive and conflicts; checked against NeoForge 26.3.
        {"net/minecraftforge/client/settings/IKeyConflictContext",
         "net/neoforged/neoforge/client/settings/IKeyConflictContext"},
        {"net/minecraftforge/client/settings/KeyConflictContext",
         "net/neoforged/neoforge/client/settings/KeyConflictContext"},
        // The modifiers keep their constants, isActive and matches; the three lookups that changed are bridged below.
        {"net/minecraftforge/client/settings/KeyModifier", "net/neoforged/neoforge/client/settings/KeyModifier"},
    };

    static final String KEY_MODIFIERS = com.retromod.shim.common.RebuiltApiClasses.ROOT + "forge/client/settings/LegacyKeyModifiers";

    static final String KEY_MODIFIER = "net/neoforged/neoforge/client/settings/KeyModifier";

    private ForgeNeoForgeSameShapeMoves() {}

    /**
     * NeoForge renamed the single-key lookup and answers the active modifiers as a list; both are bridged only where the
     * host declares them, which NeoForge 1.21.11 and 26.3 do. Offline, a target from 1.21.11 has them.
     */
    static void registerKeyModifierLookups(RetromodTransformer transformer, org.objectweb.asm.tree.ClassNode host) {
        String key = "Lcom/mojang/blaze3d/platform/InputConstants$Key;";
        String single = "(" + key + ")L" + KEY_MODIFIER + ";";
        boolean present = host == null
                ? com.retromod.core.RetromodVersion.compareMcVersions(com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.11") >= 0
                : declares(host, "getKeyModifier", single) && declares(host, "getActiveModifiers", "()Ljava/util/List;");
        if (!present) return;
        transformer.registerMethodRedirect(KEY_MODIFIER, "getModifier", single, KEY_MODIFIER, "getKeyModifier", single);
        com.retromod.shim.common.RebuiltApiClasses.registerTree(transformer, KEY_MODIFIERS);
        transformer.registerMethodRedirect(KEY_MODIFIER, "getActiveModifier", "()L" + KEY_MODIFIER + ";", KEY_MODIFIERS,
                "getActiveModifier", "()L" + KEY_MODIFIER + ";");
        transformer.registerMethodRedirect(KEY_MODIFIER, "getValues", "(Z)Ljava/util/List;", KEY_MODIFIERS, "getValues", "(Z)Ljava/util/List;");
    }

    private static boolean declares(org.objectweb.asm.tree.ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    /** Registers the renames. Callers apply this only on a NeoForge host. */
    static void register(RetromodTransformer transformer) {
        for (String[] move : MOVES) {
            transformer.registerClassRedirect(move[0], move[1]);
        }
        registerKeyModifierLookups(transformer, com.retromod.core.ClassResourceInspector.read(KEY_MODIFIER));
        // Renamed too, but its buffer type changed, so it also needs per-class forwarders.
        LegacySpawnDataAdapter.register(transformer);
    }
}
