/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Sends old calls and constants of the chunk, biome, noise, spawn and villager classes to their rebuilt forms. */
public final class LegacyWorldgenMembersBridge {

    static final String MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/LegacyWorldgenMembers";

    static final String CHUNK = "net/minecraft/world/level/chunk/ChunkAccess";
    static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
    static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    static final String SET_BLOCK_OLD = "(" + BLOCK_POS + BLOCK_STATE + "Z)" + BLOCK_STATE;
    static final String SET_BLOCK_NEW = "(" + BLOCK_POS + BLOCK_STATE + "I)" + BLOCK_STATE;
    static final String SPAWNER_DATA = "net/minecraft/world/level/biome/MobSpawnSettings$SpawnerData";
    static final String VILLAGER_TYPE = "net/minecraft/world/entity/npc/villager/VillagerType";
    static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    static final String CODEC = "Lcom/mojang/serialization/Codec;";
    static final String MAP_CODEC = "Lcom/mojang/serialization/MapCodec;";
    static final String RANDOM_STATE = "net/minecraft/world/level/levelgen/RandomState";
    static final String SAMPLER = "Lnet/minecraft/world/level/biome/Climate$Sampler;";
    static final String BIOME_SOURCE = "net/minecraft/world/level/biome/BiomeSource";
    static final String NOISE_BIOME_OLD = "(III" + SAMPLER + ")Lnet/minecraft/core/Holder;";
    static final String LEGACY_BIOME_SOURCE = RebuiltApiClasses.ROOT + "minecraft/world/level/biome/LegacyBiomeSource";
    /** The base the codec bridge moves biome sources onto, generated in {@link LegacyCodecTypeBridge}. */
    static final String CODEC_BIOME_SOURCE = "com/retromod/generated/LegacyBiomeSource";
    static final String CONFIGURED_FEATURE = "net/minecraft/world/level/levelgen/feature/ConfiguredFeature";
    static final String FEATURE = "net/minecraft/world/level/levelgen/feature/Feature";

    // Vanilla classes a mod calls these members on; a mod's own subclass keeps its own methods.
    private static final String[] CHUNKS = {CHUNK, "net/minecraft/world/level/chunk/LevelChunk",
            "net/minecraft/world/level/chunk/ProtoChunk", "net/minecraft/world/level/chunk/ImposterProtoChunk"};
    private static final String[] BIOME_SOURCES = {BIOME_SOURCE, "net/minecraft/world/level/biome/MultiNoiseBiomeSource",
            "net/minecraft/world/level/biome/FixedBiomeSource", "net/minecraft/world/level/biome/CheckerboardColumnBiomeSource",
            "net/minecraft/world/level/biome/TheEndBiomeSource"};

    private LegacyWorldgenMembersBridge() {}

    /** The members 1.21.5 to 26.2 reshaped; registered from the 26.1 to 26.2 moves, for each one the host changed. */
    public static void registerOlder(RetromodTransformer t) {
        ClassNode chunk = ClassResourceInspector.read(CHUNK);
        ClassNode spawner = ClassResourceInspector.read(SPAWNER_DATA);
        ClassNode villager = ClassResourceInspector.read(VILLAGER_TYPE);
        boolean chunkFlags = chunk == null || (!hasMethod(chunk, "setBlockState", SET_BLOCK_OLD)
                && hasMethod(chunk, "setBlockState", SET_BLOCK_NEW));
        boolean spawnerMapCodec = spawner == null || fieldIs(spawner, "CODEC", MAP_CODEC);
        boolean villagerKeys = villager == null || fieldIs(villager, "PLAINS", RESOURCE_KEY);
        registerOlder(t, chunkFlags, spawnerMapCodec, villagerKeys);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerOlder(RetromodTransformer t, boolean chunkFlags, boolean spawnerMapCodec, boolean villagerKeys) {
        if (!chunkFlags && !spawnerMapCodec && !villagerKeys) return;
        RebuiltApiClasses.registerTree(t, MEMBERS);
        // A chunk's moved-by-piston boolean became one bit of the update flags.
        if (chunkFlags) {
            for (String owner : CHUNKS) {
                t.registerMethodRedirect(owner, "setBlockState", SET_BLOCK_OLD,
                        MEMBERS, "setBlockState", "(L" + CHUNK + ";" + BLOCK_POS + BLOCK_STATE + "Z)" + BLOCK_STATE, true);
            }
        }
        // The spawn entry's codec became a map codec; an old read takes the codec it wraps.
        if (spawnerMapCodec) {
            t.registerStaticFieldTypeDrift(SPAWNER_DATA, CODEC, MAP_CODEC,
                    MEMBERS, "codecOf", "(" + MAP_CODEC + ")" + CODEC);
        }
        // The villager type constants became registry keys; an old read takes the registered type.
        if (villagerKeys) {
            t.registerStaticFieldTypeDrift(VILLAGER_TYPE, "L" + VILLAGER_TYPE + ";", RESOURCE_KEY,
                    MEMBERS, "villagerType", "(" + RESOURCE_KEY + ")L" + VILLAGER_TYPE + ";");
        }
    }

    /** The members 26.3 reshaped; registered from the 26.2 to 26.3 moves, when the host lost the old ones. */
    public static void register263(RetromodTransformer t) {
        ClassNode randomState = ClassResourceInspector.read(RANDOM_STATE);
        ClassNode source = ClassResourceInspector.read(BIOME_SOURCE);
        boolean samplerGone = randomState == null || !hasMethod(randomState, "sampler", "()" + SAMPLER);
        boolean noiseBiomeGone = source == null || !hasMethod(source, "getNoiseBiome", NOISE_BIOME_OLD);
        boolean configuredGone = ClassResourceInspector.read(CONFIGURED_FEATURE) == null
                && LegacyFeatureBridge.hostHasFeatureInterface();
        register263(t, samplerGone, noiseBiomeGone, configuredGone);
    }

    /** Unconditional registration for transform-shape tests. */
    static void register263(RetromodTransformer t, boolean samplerGone, boolean noiseBiomeGone, boolean configuredGone) {
        if (samplerGone || noiseBiomeGone) RebuiltApiClasses.registerTree(t, MEMBERS);
        // The climate sampler is now built for a sampling context.
        if (samplerGone) {
            t.registerMethodRedirect(RANDOM_STATE, "sampler", "()" + SAMPLER,
                    MEMBERS, "sampler", "(L" + RANDOM_STATE + ";)" + SAMPLER, true);
        }
        // A biome lookup now goes through the resolver a source builds for a sampler. Only vanilla sources are
        // redirected, because a mod's own source still declares the old method.
        if (noiseBiomeGone) {
            for (String owner : BIOME_SOURCES) {
                t.registerMethodRedirect(owner, "getNoiseBiome", NOISE_BIOME_OLD,
                        MEMBERS, "getNoiseBiome", "(L" + BIOME_SOURCE + ";III" + SAMPLER + ")Lnet/minecraft/core/Holder;",
                        true);
            }
        }
        // A mod's own source moves onto a rebuilt base that answers the resolver from its old lookup. Where the codec
        // bridge already moves sources onto its generated base, that base sits on the rebuilt one instead.
        if (noiseBiomeGone) {
            RebuiltApiClasses.registerTree(t, LEGACY_BIOME_SOURCE);
            if (!t.getSyntheticClasses().containsKey(CODEC_BIOME_SOURCE)) t.registerSuperclassRebase(BIOME_SOURCE, LEGACY_BIOME_SOURCE);
        }
        // A configured feature is the 26.3 feature itself, with the same place method.
        if (configuredGone) {
            t.registerClassRedirect(CONFIGURED_FEATURE, FEATURE);
            t.limitClassRedirectToModsBefore(CONFIGURED_FEATURE, "26.3");
        }
    }

    /** Whether the host's biome source dropped the old lookup, as 26.3's did; offline, the target version decides. */
    static boolean hostDropsNoiseBiome() {
        ClassNode source = ClassResourceInspector.read(BIOME_SOURCE);
        if (source == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        return !hasMethod(source, "getNoiseBiome", NOISE_BIOME_OLD);
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean fieldIs(ClassNode owner, String name, String desc) {
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name)) return field.desc.equals(desc);
        }
        return false;
    }
}
