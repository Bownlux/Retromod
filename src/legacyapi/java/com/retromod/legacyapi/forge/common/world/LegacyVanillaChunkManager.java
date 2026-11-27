/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common.world;

import com.mojang.datafixers.util.Pair;
import com.retromod.shim.common.embedded.LegacyLog;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Forge's {@code ForgeChunkManager} on a Forge host that removed it, rebuilt on the level's own forced chunks. */
public final class LegacyVanillaChunkManager {

    private LegacyVanillaChunkManager() {}

    /** Forge's check of a mod's saved tickets when a level loads, which a level's own forced chunks never ask for. */
    @FunctionalInterface
    public interface LoadingValidationCallback {
        void validateTickets(ServerLevel level, TicketHelper ticketHelper);
    }

    /** {@code setForcedChunkLoadingCallback(modId, callback)}: the level keeps its forced chunks without asking. */
    public static void setForcedChunkLoadingCallback(String modId, LoadingValidationCallback callback) {
        LegacyLog.warnOnce("chunk-callback-" + modId, modId + " keeps its forced chunks as the level saved them: this Forge has"
                + " no forced chunk tickets, so its callback is not asked.");
    }

    /** {@code forceChunk(level, modId, owner, x, z, add, ticking)}: the level forces the chunk, whoever owns it. */
    public static boolean forceChunk(ServerLevel level, String modId, BlockPos owner, int chunkX, int chunkZ, boolean add,
            boolean ticking) {
        return level.setChunkForced(chunkX, chunkZ, add);
    }

    public static boolean forceChunk(ServerLevel level, String modId, Entity owner, int chunkX, int chunkZ, boolean add,
            boolean ticking) {
        return level.setChunkForced(chunkX, chunkZ, add);
    }

    public static boolean forceChunk(ServerLevel level, String modId, UUID owner, int chunkX, int chunkZ, boolean add,
            boolean ticking) {
        return level.setChunkForced(chunkX, chunkZ, add);
    }

    /** Forge's view of saved tickets, which this host never hands out: it holds none. */
    public static final class TicketHelper {
        private TicketHelper() {}

        public Map<BlockPos, Pair<LongSet, LongSet>> getBlockTickets() {
            return Map.of();
        }

        public Map<UUID, Pair<LongSet, LongSet>> getEntityTickets() {
            return Map.of();
        }

        public void removeAllTickets(BlockPos owner) {
        }

        public void removeAllTickets(UUID owner) {
        }

        public void removeTicket(BlockPos owner, long chunk, boolean ticking) {
        }

        public void removeTicket(UUID owner, long chunk, boolean ticking) {
        }
    }
}
