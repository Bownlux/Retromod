/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyRandomView;
import com.retromod.util.McReflect;

/**
 * Follows Forge 1.19's event, packet, and registry renames for mods built on Forge 1.18.2 or older.
 *
 * <p>Forge 1.19 moved {@code net.minecraftforge.event.world} to {@code event.level}, renamed the
 * entity join and leave events, {@code LivingUpdateEvent}, and {@code PotionEvent}, and renamed
 * the getters that went with them ({@code getWorld} to {@code getLevel}, {@code getEntityLiving}
 * and {@code getPlayer} to {@code getEntity}). One handler parameter naming a deleted class makes
 * Forge reject every automatic subscriber in that class, so a single stale event stops a whole
 * mod from constructing (#311).
 *
 * <p>{@code BiomeLoadingEvent} has no 1.19 equivalent: biome edits became data-driven biome
 * modifiers. Its subscribers now register against a stand-in event, which
 * {@link LegacyBiomeLoadingBridge} posts from a generated biome modifier.
 *
 * <p>The same release renamed {@code ForgeRegistries.PAINTING_TYPES} and {@code PROFESSIONS}, and
 * the painting type {@code Motive} became {@code PaintingVariant}.
 *
 * <p>The packet builder's {@code consumer} became {@code consumerNetworkThread} in the same
 * release, with the same handler contract.
 *
 * <p>Forge host only. NeoForge has its own package for these events, which
 * {@code ForgeEventApiShim} handles.
 */
public final class LegacyForge119Bridge {

    private static final String FORGE_EVENT = "net/minecraftforge/event/";
    private static final String OLD_WORLD = FORGE_EVENT + "world/";
    private static final String NEW_LEVEL = FORGE_EVENT + "level/";
    private static final String LIVING = FORGE_EVENT + "entity/living/";
    private static final String PLAYER_INTERACT = FORGE_EVENT + "entity/player/PlayerInteractEvent";

    static final String BIOME_LOADING_EVENT = OLD_WORLD + "BiomeLoadingEvent";
    static final String BIOME_LOADING_STAND_IN = "com/retromod/generated/forge118/BiomeLoadingEvent";

    /** Event classes that kept their simple name and inner classes but moved to event/level. */
    private static final String[][] WORLD_PACKAGE_EVENTS = {
        {"BlockEvent", "$BreakEvent", "$EntityPlaceEvent", "$EntityMultiPlaceEvent",
            "$NeighborNotifyEvent", "$CreateFluidSourceEvent", "$FluidPlaceBlockEvent",
            "$CropGrowEvent", "$CropGrowEvent$Pre", "$CropGrowEvent$Post",
            "$FarmlandTrampleEvent", "$PortalSpawnEvent", "$BlockToolModificationEvent"},
        {"ChunkEvent", "$Load", "$Unload"},
        {"ChunkDataEvent", "$Load", "$Save"},
        {"ChunkWatchEvent", "$Watch", "$UnWatch"},
        {"ExplosionEvent", "$Start", "$Detonate"},
        {"NoteBlockEvent", "$Play", "$Change", "$Note", "$Octave"},
        {"PistonEvent", "$Pre", "$Post", "$PistonMoveType"},
        {"SaplingGrowTreeEvent"},
        {"SleepFinishedTimeEvent"},
    };

    private LegacyForge119Bridge() {
    }

    public static void register(RetromodTransformer transformer) {
        if (McReflect.isNeoForge()) {
            return;
        }
        registerClassMoves(transformer);
        registerGetterRenames(transformer);
        registerBiomeLoadingStandIn(transformer);
        LegacyForgeClientEvents119.register(transformer);

        // Same handler contract (network thread, Supplier<Context>), new name.
        String builder = "net/minecraftforge/network/simple/SimpleChannel$MessageBuilder";
        String consumerDesc = "(Ljava/util/function/BiConsumer;)L" + builder + ";";
        transformer.registerMethodRedirect(builder, "consumer", consumerDesc,
                builder, "consumerNetworkThread", consumerDesc);

        registerRegistryRenames(transformer);
        registerCapabilityConstants(transformer);
        LegacyForge119Calls.register(transformer);
        // Entity and Level randoms became RandomSource; LegacyRandomSourceAdapter wraps the reads.
        SyntheticEmbedder.registerClassResource(transformer, LegacyRandomSourceAdapter.VIEW,
                LegacyRandomView.class);
    }

    /**
     * Forge 1.19.2 gathered the built-in capability tokens into {@code ForgeCapabilities} and
     * then removed the per-capability holder classes, so a 1.18.2 item or block entity that asked
     * for an item handler failed with {@code NoClassDefFoundError}. The token objects are the
     * same, only the field moved.
     */
    private static void registerCapabilityConstants(RetromodTransformer transformer) {
        String capabilities = "net/minecraftforge/common/capabilities/ForgeCapabilities";
        String capability = "Lnet/minecraftforge/common/capabilities/Capability;";
        String[][] moves = {
            {"net/minecraftforge/items/CapabilityItemHandler", "ITEM_HANDLER_CAPABILITY", "ITEM_HANDLER"},
            {"net/minecraftforge/energy/CapabilityEnergy", "ENERGY", "ENERGY"},
            {"net/minecraftforge/fluids/capability/CapabilityFluidHandler", "FLUID_HANDLER_CAPABILITY",
                "FLUID_HANDLER"},
            {"net/minecraftforge/fluids/capability/CapabilityFluidHandler",
                "FLUID_HANDLER_ITEM_CAPABILITY", "FLUID_HANDLER_ITEM"},
        };
        for (String[] move : moves) {
            transformer.registerFieldRedirect(move[0], move[1], capability, capabilities, move[2], capability);
        }
    }

    /** Forge 1.19 renamed two registries; paintings also became {@code PaintingVariant}. */
    private static void registerRegistryRenames(RetromodTransformer transformer) {
        String registries = "net/minecraftforge/registries/ForgeRegistries";
        String registry = "Lnet/minecraftforge/registries/IForgeRegistry;";
        transformer.registerFieldRedirect(registries, "PAINTING_TYPES", registry,
                registries, "PAINTING_VARIANTS", registry);
        transformer.registerFieldRedirect(registries, "PROFESSIONS", registry,
                registries, "VILLAGER_PROFESSIONS", registry);
        // Same (width, height) constructor, so registrations carry over unchanged.
        transformer.registerClassRedirect("net/minecraft/world/entity/decoration/Motive",
                "net/minecraft/world/entity/decoration/PaintingVariant");
    }

    private static void registerClassMoves(RetromodTransformer transformer) {
        for (String[] event : WORLD_PACKAGE_EVENTS) {
            transformer.registerClassRedirect(OLD_WORLD + event[0], NEW_LEVEL + event[0]);
            for (int i = 1; i < event.length; i++) {
                transformer.registerClassRedirect(OLD_WORLD + event[0] + event[i],
                        NEW_LEVEL + event[0] + event[i]);
            }
        }
        transformer.registerClassRedirect(OLD_WORLD + "WorldEvent", NEW_LEVEL + "LevelEvent");
        for (String inner : new String[]{"$Load", "$Unload", "$Save", "$CreateSpawnPosition",
                "$PotentialSpawns"}) {
            transformer.registerClassRedirect(OLD_WORLD + "WorldEvent" + inner,
                    NEW_LEVEL + "LevelEvent" + inner);
        }
        transformer.registerClassRedirect(FORGE_EVENT + "entity/EntityJoinWorldEvent",
                FORGE_EVENT + "entity/EntityJoinLevelEvent");
        transformer.registerClassRedirect(FORGE_EVENT + "entity/EntityLeaveWorldEvent",
                FORGE_EVENT + "entity/EntityLeaveLevelEvent");
        transformer.registerClassRedirect(LIVING + "LivingEvent$LivingUpdateEvent",
                LIVING + "LivingEvent$LivingTickEvent");
        transformer.registerClassRedirect(FORGE_EVENT + "TickEvent$WorldTickEvent",
                FORGE_EVENT + "TickEvent$LevelTickEvent");
        transformer.registerClassRedirect(LIVING + "PotionEvent", LIVING + "MobEffectEvent");
        transformer.registerClassRedirect(LIVING + "PotionEvent$PotionAddedEvent",
                LIVING + "MobEffectEvent$Added");
        transformer.registerClassRedirect(LIVING + "PotionEvent$PotionApplicableEvent",
                LIVING + "MobEffectEvent$Applicable");
        transformer.registerClassRedirect(LIVING + "PotionEvent$PotionExpiryEvent",
                LIVING + "MobEffectEvent$Expired");
        transformer.registerClassRedirect(LIVING + "PotionEvent$PotionRemoveEvent",
                LIVING + "MobEffectEvent$Remove");
    }

    private static void registerGetterRenames(RetromodTransformer transformer) {
        String level = "()Lnet/minecraft/world/level/Level;";
        String levelAccessor = "()Lnet/minecraft/world/level/LevelAccessor;";
        String living = "()Lnet/minecraft/world/entity/LivingEntity;";
        String player = "()Lnet/minecraft/world/entity/player/Player;";

        rename(transformer, FORGE_EVENT + "entity/EntityJoinWorldEvent",
                FORGE_EVENT + "entity/EntityJoinLevelEvent", "getWorld", "getLevel", level);
        rename(transformer, FORGE_EVENT + "entity/EntityLeaveWorldEvent",
                FORGE_EVENT + "entity/EntityLeaveLevelEvent", "getWorld", "getLevel", level);
        rename(transformer, OLD_WORLD + "WorldEvent", NEW_LEVEL + "LevelEvent",
                "getWorld", "getLevel", levelAccessor);
        rename(transformer, OLD_WORLD + "BlockEvent", NEW_LEVEL + "BlockEvent",
                "getWorld", "getLevel", levelAccessor);
        rename(transformer, OLD_WORLD + "ChunkEvent", NEW_LEVEL + "ChunkEvent",
                "getWorld", "getLevel", levelAccessor);
        rename(transformer, OLD_WORLD + "ExplosionEvent", NEW_LEVEL + "ExplosionEvent",
                "getWorld", "getLevel", level);
        rename(transformer, PLAYER_INTERACT, PLAYER_INTERACT, "getWorld", "getLevel", level);
        rename(transformer, LIVING + "LivingEvent", LIVING + "LivingEvent",
                "getEntityLiving", "getEntity", living);
        rename(transformer, FORGE_EVENT + "entity/player/PlayerEvent",
                FORGE_EVENT + "entity/player/PlayerEvent", "getPlayer", "getEntity", player);
        registerSpawnCheckMove(transformer);
    }

    /**
     * {@code LivingSpawnEvent.CheckSpawn} became {@code MobSpawnEvent.PositionCheck}. Both are
     * posted before a mob spawns at a position, and a {@code DENY} result cancels the spawn in
     * both. The new getters return subtypes of the old ones, so the calls retarget directly.
     */
    private static void registerSpawnCheckMove(RetromodTransformer transformer) {
        String oldEvent = LIVING + "LivingSpawnEvent$CheckSpawn";
        String newEvent = LIVING + "MobSpawnEvent$PositionCheck";
        transformer.registerClassRedirect(oldEvent, newEvent);
        transformer.registerClassRedirect(LIVING + "LivingSpawnEvent$AllowDespawn",
                LIVING + "MobSpawnEvent$AllowDespawn");
        String[][] getters = {
            {"getEntityLiving", "()Lnet/minecraft/world/entity/LivingEntity;",
                "getEntity", "()Lnet/minecraft/world/entity/Mob;"},
            {"getWorld", "()Lnet/minecraft/world/level/LevelAccessor;",
                "getLevel", "()Lnet/minecraft/world/level/ServerLevelAccessor;"},
            {"getSpawnReason", "()Lnet/minecraft/world/entity/MobSpawnType;",
                "getSpawnType", "()Lnet/minecraft/world/entity/MobSpawnType;"},
        };
        for (String[] getter : getters) {
            for (String owner : new String[]{oldEvent, newEvent}) {
                transformer.registerMethodRedirect(owner, getter[0], getter[1],
                        newEvent, getter[2], getter[3]);
            }
        }
    }

    /**
     * Renames a getter on the old and the new owner, including calls whose bytecode owner is a
     * subclass, since javac names the static type a handler was declared with.
     */
    private static void rename(RetromodTransformer transformer, String oldOwner, String newOwner,
            String oldName, String newName, String descriptor) {
        transformer.registerInheritedMethodRedirect(oldOwner, oldName, descriptor,
                newOwner, newName, descriptor);
        if (!oldOwner.equals(newOwner)) {
            transformer.registerInheritedMethodRedirect(newOwner, oldName, descriptor,
                    newOwner, newName, descriptor);
        }
    }

    private static void registerBiomeLoadingStandIn(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(BIOME_LOADING_STAND_IN, generateBiomeLoadingStandIn());
        transformer.registerClassRedirect(BIOME_LOADING_EVENT, BIOME_LOADING_STAND_IN);
        LegacyBiomeLoadingBridge.register(transformer);
    }

    /** The Forge-bus stand-in that the generated biome modifier posts. */
    static byte[] generateBiomeLoadingStandIn() {
        return LegacyBiomeLoadingBridge.generateEvent();
    }
}
