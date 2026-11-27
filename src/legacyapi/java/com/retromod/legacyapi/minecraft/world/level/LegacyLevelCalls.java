/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Optional;

/** Level, block, structure and entity calls whose shapes changed after 1.20.1 or 1.21.1, each passed on to their replacement. */
public final class LegacyLevelCalls {

    private LegacyLevelCalls() {}

    /** {@code structures.getStructureWithPieceAt(pos, key)}: the start of that structure whose pieces hold the position. */
    public static StructureStart getStructureWithPieceAt(StructureManager structures, BlockPos pos, ResourceKey<Structure> key) {
        return structures.getStructureWithPieceAt(pos, holder -> holder.is(key));
    }

    /** {@code level.playSound(player, pos, sound, source)}: at full volume and normal pitch, as the old short form played it. */
    public static void playSound(Level level, Player player, BlockPos pos, SoundEvent sound, SoundSource source) {
        level.playSound(player, pos, sound, source, 1.0F, 1.0F);
    }

    /** {@code level.getCurrentDifficultyAt(pos)}: the server's answer; a client level, which lost the call, gets the base difficulty. */
    public static DifficultyInstance getCurrentDifficultyAt(Object level, BlockPos pos) {
        if (level instanceof ServerLevelAccessor server) return server.getCurrentDifficultyAt(pos);
        // Only the server tracks how long a chunk was lived in, so the client's answer leaves that time out.
        long time = level instanceof Level world ? world.getOverworldClockTime() : 0L;
        return new DifficultyInstance(((LevelAccessor) level).getDifficulty(), time, 0L, LegacyLevelTime.getMoonBrightness(level));
    }

    /** {@code type.ultraWarm()}: whether water boils away, which is what 1.21.11 made of the flag. */
    public static boolean ultraWarm(DimensionType type) {
        return dimensionFlag(type, EnvironmentAttributes.WATER_EVAPORATES);
    }

    /** {@code type.natural()}: whether nether portals spawn piglins, the one rule 1.21.11 kept from the flag. */
    public static boolean natural(DimensionType type) {
        return dimensionFlag(type, EnvironmentAttributes.NETHER_PORTAL_SPAWNS_PIGLINS);
    }

    /** {@code type.respawnAnchorWorks()}: the same rule, now an environment attribute. */
    public static boolean respawnAnchorWorks(DimensionType type) {
        return dimensionFlag(type, EnvironmentAttributes.RESPAWN_ANCHOR_WORKS);
    }

    private static boolean dimensionFlag(DimensionType type, EnvironmentAttribute<Boolean> attribute) {
        // The dimension's own value where its data sets one, else the attribute's default.
        return type.attributes().applyModifier(attribute, attribute.defaultValue());
    }

    /** {@code Clearable.tryClear(object)}: empties the object when it can be cleared. */
    public static void tryClear(Object object) {
        if (object instanceof Clearable clearable) clearable.clearContent();
    }

    /** {@code template.fillFromWorld(level, origin, size, entities, ignored)}: one ignored block, or none for null, as a list. */
    public static void fillFromWorld(StructureTemplate template, Level level, BlockPos origin, Vec3i size, boolean entities, Block ignored) {
        template.fillFromWorld(level, origin, size, entities, ignored == null ? List.of() : List.of(ignored));
    }

    /** {@code skull.getOwnerProfile()}: the profile as far as it is known, which a resolvable profile wraps from 1.20.5. */
    public static GameProfile getOwnerProfile(SkullBlockEntity skull) {
        ResolvableProfile profile = skull.getOwnerProfile();
        return profile == null ? null : profile.partialProfile();
    }

    /** {@code profession.name()}: the profession's id path, such as {@code farmer}, which the old record held as its name. */
    public static String professionName(VillagerProfession profession) {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).getPath();
    }

    /** {@code new SpawnData(entity, rules)}: 1.20.5 added the entity's equipment, which an old spawner entry never sets. */
    public static SpawnData spawnData(CompoundTag entity, Optional<SpawnData.CustomSpawnRules> rules) {
        return new SpawnData(entity, rules, Optional.empty());
    }

    /** {@code state.isPathfindable(level, pos, type)}: the level and position stopped mattering in 1.20.5. */
    public static boolean isPathfindable(Object state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return ((BlockState) state).isPathfindable(type);
    }

    /** {@code state.getFaceOcclusionShape(level, pos, face)}: the level and position stopped mattering in 1.21.2. */
    public static VoxelShape getFaceOcclusionShape(Object state, BlockGetter level, BlockPos pos, Direction face) {
        return ((BlockState) state).getFaceOcclusionShape(face);
    }

    /** {@code level.addParticle(particle, force, ...)}: the old force flag is what 1.21.4 calls overriding the limiter. */
    public static void addParticle(Level level, ParticleOptions particle, boolean force,
            double x, double y, double z, double xd, double yd, double zd) {
        level.addParticle(particle, force, false, x, y, z, xd, yd, zd);
    }

    /** {@code level.neighborChanged(state, pos, block, fromPos, moved)}: no orientation, which the host accepts. */
    public static void neighborChanged(Level level, BlockState state, BlockPos pos, Block block, BlockPos fromPos, boolean moved) {
        level.neighborChanged(state, pos, block, null, moved);
    }

    /** {@code level.neighborChanged(pos, block, fromPos)}: the same, read from the block at the position. */
    public static void neighborChanged(Level level, BlockPos pos, Block block, BlockPos fromPos) {
        level.neighborChanged(pos, block, null);
    }
}
