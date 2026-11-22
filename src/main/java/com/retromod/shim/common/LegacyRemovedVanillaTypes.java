/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

import static com.retromod.shim.common.LegacyRemovedMemberBridge.CHECKED_1_21_11;
import static com.retromod.shim.common.LegacyRemovedMemberBridge.lacksClass;

/** Rebuilds the profile cache, FlyingMob, MossBlock and Saddleable, which 1.21.2 to 1.21.9 removed, for mods built before each removal. */
public final class LegacyRemovedVanillaTypes {

    static final String MOB = "net/minecraft/world/entity/Mob";
    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String SERVICES = "net/minecraft/server/Services";
    static final String PROFILE_CACHE = "net/minecraft/server/players/GameProfileCache";
    static final String NAME_CACHE = "net/minecraft/server/players/CachedUserNameToIdResolver";
    static final String FLYING_MOB = "net/minecraft/world/entity/FlyingMob";
    static final String MOSS_BLOCK = "net/minecraft/world/level/block/MossBlock";
    static final String SADDLEABLE = "net/minecraft/world/entity/Saddleable";

    // The first version without each type, read from Mojang's mappings for 1.21.1 to 1.21.10.
    static final String FIRST_WITHOUT_MOSS_BLOCK = "1.21.2";
    static final String FIRST_WITHOUT_SADDLEABLE = "1.21.5";
    static final String FIRST_WITHOUT_FLYING_MOB = "1.21.6";
    static final String FIRST_WITHOUT_PROFILE_CACHE = "1.21.9";

    static final String PROFILES = RebuiltApiClasses.ROOT + "minecraft/server/players/LegacyProfileCache";
    static final String LEGACY_FLYING_MOB = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacyFlyingMob";
    static final String LEGACY_MOSS_BLOCK = RebuiltApiClasses.ROOT + "minecraft/world/level/block/LegacyMossBlock";
    static final String LEGACY_SADDLEABLE = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacySaddleable";
    static final String SADDLEABLES = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacySaddleables";

    // The server classes javac names as the owner of getProfileCache.
    static final List<String> SERVERS = List.of(SERVER, "net/minecraft/server/dedicated/DedicatedServer",
            "net/minecraft/client/server/IntegratedServer");
    // The old cache's members by name and descriptor; each helper takes the cache first.
    static final String[][] CACHE_MEMBERS = {
            {"get", "(Ljava/lang/String;)Ljava/util/Optional;"},
            {"get", "(Ljava/util/UUID;)Ljava/util/Optional;"},
            {"add", "(Lcom/mojang/authlib/GameProfile;)V"},
            {"getAsync", "(Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;"},
            {"setExecutor", "(Ljava/util/concurrent/Executor;)V"},
            {"clearExecutor", "()V"}};

    // Saddleable's members, then the vanilla mobs that implemented it under their 1.21.1 and current names.
    static final String[][] SADDLE_MEMBERS = {
            {"isSaddled", "()Z"},
            {"isSaddleable", "()Z"},
            {"equipSaddle", "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/sounds/SoundSource;)V"},
            {"getSaddleSoundEvent", "()Lnet/minecraft/sounds/SoundEvent;"}};
    static final List<String> SADDLED_MOBS = List.of("net/minecraft/world/entity/animal/horse/AbstractHorse",
            "net/minecraft/world/entity/animal/equine/AbstractHorse", "net/minecraft/world/entity/animal/Pig",
            "net/minecraft/world/entity/animal/pig/Pig", "net/minecraft/world/entity/monster/Strider",
            "net/minecraft/world/entity/animal/camel/Camel");

    private LegacyRemovedVanillaTypes() {}

    /** Mojang names: Forge and NeoForge from 1.21.11, and Fabric from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        registerProfileCache(t, host);
        registerFlyingMob(t, host);
        registerMossBlock(t, host);
        registerSaddleable(t, host);
    }

    // 1.21.9 turned the profile cache into a name and id cache that the server's services hold.
    private static void registerProfileCache(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!lacksClass(host, PROFILE_CACHE, SERVER, CHECKED_1_21_11)) return;
        RebuiltApiClasses.registerTree(t, PROFILES);
        // The 26.1 class moves rename the type too; a 1.21.11 host needs it as well.
        t.registerClassRedirect(PROFILE_CACHE, NAME_CACHE);
        t.limitClassRedirectToModsBefore(PROFILE_CACHE, FIRST_WITHOUT_PROFILE_CACHE);
        String getter = "(Ljava/lang/Object;)L" + NAME_CACHE + ";";
        // Under the old type and the name the class move gives it, whichever the call shows when it is matched.
        for (String cache : List.of(PROFILE_CACHE, NAME_CACHE)) {
            for (String server : SERVERS) {
                redirect(t, server, "getProfileCache", "()L" + cache + ";", "getProfileCache", getter);
            }
            redirect(t, SERVICES, "profileCache", "()L" + cache + ";", "profileCache", getter);
        }
        // The cache's own members. The two get calls still link on the new cache but answer with a name and id.
        for (String cache : List.of(PROFILE_CACHE, NAME_CACHE)) {
            for (String[] member : CACHE_MEMBERS) {
                redirect(t, cache, member[0], member[1], member[0], "(Ljava/lang/Object;" + member[1].substring(1));
            }
        }
    }

    private static void redirect(RetromodTransformer t, String owner, String name, String desc, String helperName,
            String helperDesc) {
        t.registerMethodRedirect(owner, name, desc, PROFILES, helperName, helperDesc, true);
        t.limitRedirectToModsBefore(owner, name, desc, FIRST_WITHOUT_PROFILE_CACHE);
    }

    // The ghast and the phantom extend Mob directly since 1.21.6, so a mod's flying mob gets the rebuilt base.
    private static void registerFlyingMob(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!lacksClass(host, FLYING_MOB, MOB, CHECKED_1_21_11)) return;
        RebuiltApiClasses.registerTree(t, LEGACY_FLYING_MOB);
        t.registerClassRedirect(FLYING_MOB, LEGACY_FLYING_MOB);
        t.limitClassRedirectToModsBefore(FLYING_MOB, FIRST_WITHOUT_FLYING_MOB);
        t.registerInstanceofHelper(LEGACY_FLYING_MOB, LEGACY_FLYING_MOB, "isFlyingMob");
        // A mixin's selector for the old inherited getter names Mob, which the former subclasses still call.
        String dimensions = "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;";
        for (String owner : List.of(FLYING_MOB, LEGACY_FLYING_MOB)) {
            t.registerMethodRedirect(owner, "getDefaultDimensions", dimensions, MOB, "getDefaultDimensions", dimensions);
        }
    }

    // 1.21.2 made the moss block a block that places the moss patch feature when bonemealed.
    private static void registerMossBlock(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!lacksClass(host, MOSS_BLOCK, BLOCK, CHECKED_1_21_11)) return;
        RebuiltApiClasses.registerTree(t, LEGACY_MOSS_BLOCK);
        t.registerClassRedirect(MOSS_BLOCK, LEGACY_MOSS_BLOCK);
        t.limitClassRedirectToModsBefore(MOSS_BLOCK, FIRST_WITHOUT_MOSS_BLOCK);
        t.registerInstanceofHelper(LEGACY_MOSS_BLOCK, LEGACY_MOSS_BLOCK, "isMossBlock");
    }

    // 1.21.5 moved the saddle into an equipment slot every mob has, and vanilla's mobs dropped the interface.
    private static void registerSaddleable(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!lacksClass(host, SADDLEABLE, MOB, CHECKED_1_21_11)) return;
        RebuiltApiClasses.registerTree(t, SADDLEABLES);
        t.registerClassRedirect(SADDLEABLE, LEGACY_SADDLEABLE);
        t.limitClassRedirectToModsBefore(SADDLEABLE, FIRST_WITHOUT_SADDLEABLE);
        // A vanilla mob would fail a cast to the rebuilt interface, so the casts go and the helpers answer.
        t.registerInterfaceInstanceofHelper(LEGACY_SADDLEABLE, SADDLEABLES, "is");
        for (String[] member : SADDLE_MEMBERS) {
            String helperDesc = "(Ljava/lang/Object;" + member[1].substring(1);
            for (String owner : List.of(SADDLEABLE, LEGACY_SADDLEABLE)) {
                t.registerMethodRedirect(owner, member[0], member[1], SADDLEABLES, member[0], helperDesc, true);
                t.limitRedirectToModsBefore(owner, member[0], member[1], FIRST_WITHOUT_SADDLEABLE);
            }
        }
        // The mobs kept isSaddled, so only the members they lost follow them.
        for (String[] member : List.of(SADDLE_MEMBERS[1], SADDLE_MEMBERS[2])) {
            String helperDesc = "(Ljava/lang/Object;" + member[1].substring(1);
            for (String owner : SADDLED_MOBS) {
                t.registerMethodRedirect(owner, member[0], member[1], SADDLEABLES, member[0], helperDesc, true);
                t.limitRedirectToModsBefore(owner, member[0], member[1], FIRST_WITHOUT_SADDLEABLE);
            }
        }
    }
}
