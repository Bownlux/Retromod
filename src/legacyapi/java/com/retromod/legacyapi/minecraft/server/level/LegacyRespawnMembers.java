/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** The respawn and world spawn members of 1.20.1 that later versions folded into respawn configs and respawn data. */
public final class LegacyRespawnMembers {

    private LegacyRespawnMembers() {}

    /** {@code ServerPlayer.getRespawnPosition()}: null while the player has no respawn point. */
    public static BlockPos getRespawnPosition(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config == null ? null : config.respawnData().pos();
    }

    /** {@code ServerPlayer.getRespawnDimension()}: the overworld while the player has no respawn point. */
    public static ResourceKey<Level> getRespawnDimension(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config == null ? Level.OVERWORLD : config.respawnData().dimension();
    }

    /** {@code ServerPlayer.getRespawnAngle()}. */
    public static float getRespawnAngle(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config == null ? 0.0F : config.respawnData().yaw();
    }

    /** {@code ServerPlayer.isRespawnForced()}. */
    public static boolean isRespawnForced(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config != null && config.forced();
    }

    /** {@code ServerPlayer.setRespawnPosition(dimension, pos, angle, forced, sendMessage)}: a null position clears it. */
    public static void setRespawnPosition(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos, float angle,
            boolean forced, boolean sendMessage) {
        ServerPlayer.RespawnConfig config = pos == null ? null
                : new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(dimension, pos, angle, 0.0F), forced);
        player.setRespawnPosition(config, sendMessage);
    }

    /** {@code Level.getSharedSpawnPos()}: the world spawn the level reports. */
    public static BlockPos getSharedSpawnPos(Level level) {
        return level.getRespawnData().pos();
    }

    /** {@code Level.getSharedSpawnAngle()}. */
    public static float getSharedSpawnAngle(Level level) {
        return level.getRespawnData().yaw();
    }

    /**
     * {@code Player.findRespawnPositionAndUseSpawnBlock(level, pos, angle, forced, keepCharge)} from 1.20.1: where a player
     * respawning at {@code pos} stands, using up one anchor charge unless {@code keepCharge}.
     */
    public static Optional<Vec3> findRespawnPositionAndUseSpawnBlock(ServerLevel level, BlockPos pos, float angle,
            boolean forced, boolean keepCharge) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        EntityType<?> player = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("player"));
        // A charged anchor in a dimension where anchors work.
        if (block instanceof RespawnAnchorBlock) {
            int charge = state.getValue(RespawnAnchorBlock.CHARGE);
            if ((!forced && charge <= 0) || !RespawnAnchorBlock.canSetSpawn(level, pos)) return Optional.empty();
            Optional<Vec3> standing = RespawnAnchorBlock.findStandUpPosition(player, level, pos);
            if (!keepCharge && standing.isPresent()) {
                level.setBlock(pos, (BlockState) state.setValue(RespawnAnchorBlock.CHARGE, charge - 1), 3);
            }
            return standing;
        }
        // A bed, found by the stand-up search bed blocks declare; the bed class differs between hosts.
        Method bedStandUp = bedStandUp(block.getClass());
        if (bedStandUp != null) return bedPosition(bedStandUp, block, state, level, pos, angle, player);
        if (!forced) return Optional.empty();
        // A forced point needs room for the player's feet and head.
        boolean free = block.isPossibleToRespawnInThis(state)
                && level.getBlockState(pos.above()).getBlock().isPossibleToRespawnInThis(level.getBlockState(pos.above()));
        return free ? Optional.of(new Vec3(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5)) : Optional.empty();
    }

    /** The static five-argument stand-up search a bed block class declares, or null for any other block. */
    private static Method bedStandUp(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals("findStandUpPosition") && method.getParameterCount() == 5
                        && Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers())) {
                    return method;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Vec3> bedPosition(Method standUp, Block block, BlockState state, ServerLevel level, BlockPos pos,
            float angle, EntityType<?> player) {
        try {
            // From 26.3 the bed's rule for the dimension decides whether it sets a spawn there.
            Method rule = findPublic(block.getClass(), "getBedRule", 2);
            if (rule != null) {
                Object bedRule = rule.invoke(block, level, pos);
                Method canSet = bedRule.getClass().getMethod("canSetSpawn", Level.class);
                if (!(Boolean) canSet.invoke(bedRule, level)) return Optional.empty();
            }
            Field facing = standUp.getDeclaringClass().getField("FACING");
            Object direction = state.getValue((net.minecraft.world.level.block.state.properties.Property) facing.get(null));
            return (Optional<Vec3>) standUp.invoke(null, player, level, pos, direction, angle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Method findPublic(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        return null;
    }
}
