/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Server, chunk and save calls whose shapes changed after 1.20.1, read from their replacements. */
public final class LegacyServerCalls {

    private LegacyServerCalls() {}

    /** {@code server.getProfilePermissions(profile)}: the player's permission level as the number 0 to 4. */
    public static int getProfilePermissions(MinecraftServer server, GameProfile profile) {
        return server.getProfilePermissions(new NameAndId(profile)).level().id();
    }

    /** {@code server.getOperatorUserPermissionLevel()}: the level a new operator gets, as a number. */
    public static int getOperatorUserPermissionLevel(MinecraftServer server) {
        return server.operatorUserPermissions().level().id();
    }

    /** {@code player.playNotifySound(sound, source, volume, pitch)}: a sound at the player that only that player hears. */
    public static void playNotifySound(ServerPlayer player, SoundEvent sound, SoundSource source, float volume, float pitch) {
        // A fake player has no connection, and the old call sent nothing for one either.
        if (player.connection == null) return;
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source,
                player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

    /** {@code holder.getOrScheduleFuture(status, map)}: the chunk on the left once generated, the failed result on the right. */
    public static CompletableFuture<Either<ChunkAccess, Object>> getOrScheduleFuture(GenerationChunkHolder holder, ChunkStatus status,
            ChunkMap map) {
        // 1.20.1 completed with an Either; 1.20.5 and newer complete with a ChunkResult instead.
        return holder.scheduleChunkGenerationTask(status, map)
                .thenApply(result -> result.isSuccess() ? Either.left(result.orElse(null)) : Either.right(result));
    }

    /** {@code access.getDataTag()}: the saved level data, upgraded to this version as the old call returned it. */
    public static Dynamic<?> getDataTag(LevelStorageSource.LevelStorageAccess access) throws IOException {
        return getDataTag(access, false);
    }

    /** {@code access.getDataTag(fallback)}: the same, read from the backup file when {@code fallback} is set. */
    public static Dynamic<?> getDataTag(LevelStorageSource.LevelStorageAccess access, boolean fallback) throws IOException {
        // 26.1 stopped upgrading the data in this call; the game runs the level fixer itself, as here.
        return upgraded(access.getUnfixedDataTag(fallback));
    }

    private static <T> Dynamic<T> upgraded(Dynamic<T> data) {
        return DataFixTypes.LEVEL.updateToCurrentVersion(DataFixers.getDataFixer(), data, NbtUtils.getDataVersion(data));
    }
}
