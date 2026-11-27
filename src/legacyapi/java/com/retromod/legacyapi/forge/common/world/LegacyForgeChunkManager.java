/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common.world;

import com.mojang.datafixers.util.Pair;
import com.retromod.shim.common.embedded.LegacyLog;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketSet;

/** Forge's {@code ForgeChunkManager}, rebuilt on the ticket controller NeoForge registers for each mod. */
public final class LegacyForgeChunkManager {

    // Each mod's callback as it set it, and the controller NeoForge registered for the mod.
    private static final Map<String, LoadingValidationCallback> CALLBACKS = new ConcurrentHashMap<>();
    private static final Map<String, TicketController> CONTROLLERS = new ConcurrentHashMap<>();
    private static final Set<String> DECLARED = ConcurrentHashMap.newKeySet();

    private LegacyForgeChunkManager() {}

    /** Forge's check of a mod's saved tickets when a level loads. */
    @FunctionalInterface
    public interface LoadingValidationCallback {
        void validateTickets(ServerLevel level, TicketHelper ticketHelper);
    }

    /** Called from each translated mod's constructor, so its controller is registered even if it never sets a callback. */
    public static void declare(String modId) {
        if (!DECLARED.add(modId)) return;
        // Forge needed no registration; NeoForge accepts a controller only from the registration event.
        ModList.get().getModContainerById(modId).map(ModContainer::getEventBus).ifPresent(bus ->
                bus.addListener(RegisterTicketControllersEvent.class, event -> event.register(registerController(modId))));
    }

    /** {@code setForcedChunkLoadingCallback(modId, callback)}: the mod's controller checks its saved tickets with it. */
    public static void setForcedChunkLoadingCallback(String modId, LoadingValidationCallback callback) {
        if (CONTROLLERS.containsKey(modId)) {
            // NeoForge reads the callback once, so one set after registration cannot reach it.
            LegacyLog.warnOnce("chunk-callback-" + modId, modId + " set its forced chunk callback after the game loaded,"
                    + " so its saved forced chunks are kept as they are when a level loads.");
            return;
        }
        CALLBACKS.put(modId, callback);
        declare(modId);
    }

    /** {@code forceChunk(level, modId, owner, x, z, add, ticking)} for a block owner. */
    public static boolean forceChunk(ServerLevel level, String modId, BlockPos owner, int chunkX, int chunkZ, boolean add,
            boolean ticking) {
        try {
            TicketController controller = CONTROLLERS.get(modId);
            if (controller == null) return unregistered(modId, "no controller was registered for it");
            return controller.forceChunk(level, owner, chunkX, chunkZ, add, ticking);
        } catch (IllegalArgumentException refused) {
            return unregistered(modId, refused.getMessage());
        }
    }

    /** The same for an entity owner. */
    public static boolean forceChunk(ServerLevel level, String modId, Entity owner, int chunkX, int chunkZ, boolean add,
            boolean ticking) {
        try {
            TicketController controller = CONTROLLERS.get(modId);
            if (controller == null) return unregistered(modId, "no controller was registered for it");
            return controller.forceChunk(level, owner, chunkX, chunkZ, add, ticking);
        } catch (IllegalArgumentException refused) {
            return unregistered(modId, refused.getMessage());
        }
    }

    /** The same for an owner known by its id. */
    public static boolean forceChunk(ServerLevel level, String modId, UUID owner, int chunkX, int chunkZ, boolean add,
            boolean ticking) {
        try {
            TicketController controller = CONTROLLERS.get(modId);
            if (controller == null) return unregistered(modId, "no controller was registered for it");
            return controller.forceChunk(level, owner, chunkX, chunkZ, add, ticking);
        } catch (IllegalArgumentException refused) {
            return unregistered(modId, refused.getMessage());
        }
    }

    /** The mod's controller, made when NeoForge asks for controllers, with the callback the mod set by then. */
    private static TicketController registerController(String modId) {
        // Forge saved a mod's tickets without a controller name, and NeoForge reads those back as <modid>:default.
        Identifier name = Identifier.fromNamespaceAndPath(modId, "default");
        LoadingValidationCallback callback = CALLBACKS.get(modId);
        TicketController controller = callback == null ? new TicketController(name)
                : new TicketController(name, (level, helper) -> validate(modId, callback, level, helper));
        CONTROLLERS.put(modId, controller);
        return controller;
    }

    /** Runs the mod's check of its saved tickets; if it fails, the tickets stay as NeoForge does without a check. */
    private static void validate(String modId, LoadingValidationCallback callback, ServerLevel level,
            net.neoforged.neoforge.common.world.chunk.TicketHelper helper) {
        try {
            callback.validateTickets(level, new TicketHelper(helper));
        } catch (LinkageError | RuntimeException failed) {
            LegacyLog.warnOnce("chunk-validate-" + modId, modId + " failed to check its saved forced chunks, so they are kept"
                    + " as they are: " + failed);
        }
    }

    /** A mod Retromod could not declare in time, or an id no mod has, has no registered controller. */
    private static boolean unregistered(String modId, String reason) {
        // Before the game has loaded, the controller can still be registered for the next call.
        declare(modId);
        LegacyLog.warnOnce("chunk-controller-" + modId, modId + " forced a chunk without a ticket controller NeoForge registered"
                + " while the game loaded, so the chunk is not kept loaded: " + reason);
        return false;
    }

    /** Forge's view of a mod's saved tickets: each owner's non-ticking and ticking chunks as a pair. */
    public static final class TicketHelper {
        private final net.neoforged.neoforge.common.world.chunk.TicketHelper host;

        TicketHelper(net.neoforged.neoforge.common.world.chunk.TicketHelper host) {
            this.host = host;
        }

        public Map<BlockPos, Pair<LongSet, LongSet>> getBlockTickets() {
            return pairs(host.getBlockTickets());
        }

        public Map<UUID, Pair<LongSet, LongSet>> getEntityTickets() {
            return pairs(host.getEntityTickets());
        }

        public void removeAllTickets(BlockPos owner) {
            host.removeAllTickets(owner);
        }

        public void removeAllTickets(UUID owner) {
            host.removeAllTickets(owner);
        }

        public void removeTicket(BlockPos owner, long chunk, boolean ticking) {
            host.removeTicket(owner, chunk, ticking);
        }

        public void removeTicket(UUID owner, long chunk, boolean ticking) {
            host.removeTicket(owner, chunk, ticking);
        }

        private static <K> Map<K, Pair<LongSet, LongSet>> pairs(Map<K, TicketSet> sets) {
            Map<K, Pair<LongSet, LongSet>> pairs = new HashMap<>();
            sets.forEach((owner, set) -> pairs.put(owner, Pair.of(set.nonTicking(), set.ticking())));
            return Collections.unmodifiableMap(pairs);
        }
    }
}
