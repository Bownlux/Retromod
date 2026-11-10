/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;
import com.retromod.shim.api.common.LibraryMoves;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cardinal Components API shim. The package and registration layout changed across v2 to v6.
 */
public class CardinalComponentsApiShim implements AuxiliaryVersionShim {

    // Modrinth, 2026-11-07: CCA 5.4.0 for 1.20.4 ships dev.onyxstudios.cca and 6.0.0 for 1.20.5 org.ladysnake.cca,
    // and every CCA for 1.20.5 and newer is 6.x or later. The item factory registry has no row: 6.0.0 dropped it.
    static final LibraryMoves.Table CCA_6 = new LibraryMoves.Table("1.20.5", "1.20.5");
    
    @Override
    public String getShimName() {
        return "Cardinal Components API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "2.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "6.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Map<String, String> moves = new LinkedHashMap<>();
        addOnyxToLadysnakePackageMoves(moves);

        // nerdhub.cardinal.components -> dev.onyxstudios.cca -> org.ladysnake.cca
        moves.put("nerdhub/cardinal/components/api/ComponentType", "org/ladysnake/cca/api/v3/component/ComponentKey");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentKey", "org/ladysnake/cca/api/v3/component/ComponentKey");
        moves.put("nerdhub/cardinal/components/api/Component", "org/ladysnake/cca/api/v3/component/Component");
        moves.put("dev/onyxstudios/cca/api/v3/component/Component", "org/ladysnake/cca/api/v3/component/Component");
        moves.put("nerdhub/cardinal/components/api/component/SyncedComponent", "org/ladysnake/cca/api/v3/component/sync/AutoSyncedComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/sync/AutoSyncedComponent", "org/ladysnake/cca/api/v3/component/sync/AutoSyncedComponent");

        moves.put("nerdhub/cardinal/components/api/util/EntityComponents", "org/ladysnake/cca/api/v3/entity/EntityComponentFactoryRegistry");
        moves.put("dev/onyxstudios/cca/api/v3/entity/EntityComponentFactoryRegistry", "org/ladysnake/cca/api/v3/entity/EntityComponentFactoryRegistry");
        moves.put("nerdhub/cardinal/components/api/util/WorldComponents", "org/ladysnake/cca/api/v3/world/WorldComponentFactoryRegistry");
        moves.put("dev/onyxstudios/cca/api/v3/world/WorldComponentFactoryRegistry", "org/ladysnake/cca/api/v3/world/WorldComponentFactoryRegistry");
        moves.put("nerdhub/cardinal/components/api/util/ChunkComponents", "org/ladysnake/cca/api/v3/chunk/ChunkComponentFactoryRegistry");
        moves.put("dev/onyxstudios/cca/api/v3/chunk/ChunkComponentFactoryRegistry", "org/ladysnake/cca/api/v3/chunk/ChunkComponentFactoryRegistry");

        // Only where the CCA the mods run with has dropped the old names: CCA 5 still ships dev.onyxstudios.
        LibraryMoves.register(transformer, moves, CCA_6);

        // The old members, registered after the moves so each also matches its call once the owner has moved.
        // ComponentRegistry.INSTANCE -> ComponentRegistryV3.INSTANCE via shim accessor
        transformer.registerFieldRedirect(
            "nerdhub/cardinal/components/api/ComponentRegistry",
            "INSTANCE",
            "Lnerdhub/cardinal/components/api/ComponentRegistry;",
            "com/retromod/shim/api/fabric/embedded/CardinalShim",
            "getRegistry",
            "()Ljava/lang/Object;"
        );
        transformer.registerFieldRedirect(
            "dev/onyxstudios/cca/api/v3/component/ComponentRegistry",
            "INSTANCE",
            "Ldev/onyxstudios/cca/api/v3/component/ComponentRegistry;",
            "com/retromod/shim/api/fabric/embedded/CardinalShim",
            "getRegistry",
            "()Ljava/lang/Object;"
        );
        // ComponentType.get/maybeGet -> ComponentKey.get/maybeGet
        transformer.registerMethodRedirect(
            "nerdhub/cardinal/components/api/ComponentType",
            "get",
            "(Ljava/lang/Object;)Lnerdhub/cardinal/components/api/Component;",
            "org/ladysnake/cca/api/v3/component/ComponentKey",
            "get",
            "(Ljava/lang/Object;)Lorg/ladysnake/cca/api/v3/component/Component;"
        );
        transformer.registerMethodRedirect(
            "nerdhub/cardinal/components/api/ComponentType",
            "maybeGet",
            "(Ljava/lang/Object;)Ljava/util/Optional;",
            "org/ladysnake/cca/api/v3/component/ComponentKey",
            "maybeGet",
            "(Ljava/lang/Object;)Ljava/util/Optional;"
        );
        // fromTag/toTag -> readFromNbt/writeToNbt (now take a registry lookup), via shim
        transformer.registerMethodRedirect(
            "nerdhub/cardinal/components/api/Component",
            "fromTag",
            "(Lnet/minecraft/nbt/CompoundTag;)V",
            "com/retromod/shim/api/fabric/embedded/CardinalShim",
            "readFromNbt",
            "(Ljava/lang/Object;Lnet/minecraft/nbt/CompoundTag;)V"
        );
        transformer.registerMethodRedirect(
            "nerdhub/cardinal/components/api/Component",
            "toTag",
            "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
            "com/retromod/shim/api/fabric/embedded/CardinalShim",
            "writeToNbt",
            "(Ljava/lang/Object;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"
        );
    }

    // dev.onyxstudios.cca -> org.ladysnake.cca for every api/ class whose sub-path survived the
    // CCA 5->6 rename, inner classes included. Classes dropped in CCA 6 with no successor (item
    // components, PlayerComponent/PlayerCopyCallback, the block/util Sided*Compound helpers) and
    // internal/mixin classes are left out; a mod using those needs the 6.x API.
    private static void addOnyxToLadysnakePackageMoves(Map<String, String> moves) {
        moves.put("dev/onyxstudios/cca/api/v3/block/BlockComponentFactoryRegistry",
                "org/ladysnake/cca/api/v3/block/BlockComponentFactoryRegistry");
        moves.put("dev/onyxstudios/cca/api/v3/block/BlockComponentFactoryRegistry$Registration",
                "org/ladysnake/cca/api/v3/block/BlockComponentFactoryRegistry$Registration");
        moves.put("dev/onyxstudios/cca/api/v3/block/BlockComponentInitializer",
                "org/ladysnake/cca/api/v3/block/BlockComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/block/BlockComponents",
                "org/ladysnake/cca/api/v3/block/BlockComponents");
        moves.put("dev/onyxstudios/cca/api/v3/block/BlockEntitySyncAroundCallback",
                "org/ladysnake/cca/api/v3/block/BlockEntitySyncAroundCallback");
        moves.put("dev/onyxstudios/cca/api/v3/block/BlockEntitySyncCallback",
                "org/ladysnake/cca/api/v3/block/BlockEntitySyncCallback");
        moves.put("dev/onyxstudios/cca/api/v3/chunk/ChunkComponentInitializer",
                "org/ladysnake/cca/api/v3/chunk/ChunkComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/chunk/ChunkSyncCallback",
                "org/ladysnake/cca/api/v3/chunk/ChunkSyncCallback");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentAccess",
                "org/ladysnake/cca/api/v3/component/ComponentAccess");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentContainer",
                "org/ladysnake/cca/api/v3/component/ComponentContainer");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentContainer$Factory",
                "org/ladysnake/cca/api/v3/component/ComponentContainer$Factory");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentContainer$Factory$Builder",
                "org/ladysnake/cca/api/v3/component/ComponentContainer$Factory$Builder");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentFactory",
                "org/ladysnake/cca/api/v3/component/ComponentFactory");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentProvider",
                "org/ladysnake/cca/api/v3/component/ComponentProvider");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentRegistryV3",
                "org/ladysnake/cca/api/v3/component/ComponentRegistryV3");
        moves.put("dev/onyxstudios/cca/api/v3/component/ComponentV3",
                "org/ladysnake/cca/api/v3/component/ComponentV3");
        moves.put("dev/onyxstudios/cca/api/v3/component/CopyableComponent",
                "org/ladysnake/cca/api/v3/component/CopyableComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/StaticComponentInitializer",
                "org/ladysnake/cca/api/v3/component/StaticComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/component/TransientComponent",
                "org/ladysnake/cca/api/v3/component/TransientComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/TransientComponent$SimpleImpl",
                "org/ladysnake/cca/api/v3/component/TransientComponent$SimpleImpl");
        moves.put("dev/onyxstudios/cca/api/v3/component/load/ClientLoadAwareComponent",
                "org/ladysnake/cca/api/v3/component/load/ClientLoadAwareComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/load/ClientUnloadAwareComponent",
                "org/ladysnake/cca/api/v3/component/load/ClientUnloadAwareComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/load/ServerLoadAwareComponent",
                "org/ladysnake/cca/api/v3/component/load/ServerLoadAwareComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/load/ServerUnloadAwareComponent",
                "org/ladysnake/cca/api/v3/component/load/ServerUnloadAwareComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/sync/ComponentPacketWriter",
                "org/ladysnake/cca/api/v3/component/sync/ComponentPacketWriter");
        moves.put("dev/onyxstudios/cca/api/v3/component/sync/PlayerSyncPredicate",
                "org/ladysnake/cca/api/v3/component/sync/PlayerSyncPredicate");
        moves.put("dev/onyxstudios/cca/api/v3/component/tick/ClientTickingComponent",
                "org/ladysnake/cca/api/v3/component/tick/ClientTickingComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/tick/CommonTickingComponent",
                "org/ladysnake/cca/api/v3/component/tick/CommonTickingComponent");
        moves.put("dev/onyxstudios/cca/api/v3/component/tick/ServerTickingComponent",
                "org/ladysnake/cca/api/v3/component/tick/ServerTickingComponent");
        moves.put("dev/onyxstudios/cca/api/v3/entity/EntityComponentFactoryRegistry$Registration",
                "org/ladysnake/cca/api/v3/entity/EntityComponentFactoryRegistry$Registration");
        moves.put("dev/onyxstudios/cca/api/v3/entity/EntityComponentInitializer",
                "org/ladysnake/cca/api/v3/entity/EntityComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/entity/PlayerSyncCallback",
                "org/ladysnake/cca/api/v3/entity/PlayerSyncCallback");
        moves.put("dev/onyxstudios/cca/api/v3/entity/RespawnCopyStrategy",
                "org/ladysnake/cca/api/v3/entity/RespawnCopyStrategy");
        moves.put("dev/onyxstudios/cca/api/v3/entity/TrackingStartCallback",
                "org/ladysnake/cca/api/v3/entity/TrackingStartCallback");
        moves.put("dev/onyxstudios/cca/api/v3/item/ItemComponentInitializer",
                "org/ladysnake/cca/api/v3/item/ItemComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/level/LevelComponentFactoryRegistry",
                "org/ladysnake/cca/api/v3/level/LevelComponentFactoryRegistry");
        moves.put("dev/onyxstudios/cca/api/v3/level/LevelComponentInitializer",
                "org/ladysnake/cca/api/v3/level/LevelComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/level/LevelComponents",
                "org/ladysnake/cca/api/v3/level/LevelComponents");
        moves.put("dev/onyxstudios/cca/api/v3/scoreboard/ScoreboardComponentFactoryRegistry",
                "org/ladysnake/cca/api/v3/scoreboard/ScoreboardComponentFactoryRegistry");
        moves.put("dev/onyxstudios/cca/api/v3/scoreboard/ScoreboardComponentFactoryV2",
                "org/ladysnake/cca/api/v3/scoreboard/ScoreboardComponentFactoryV2");
        moves.put("dev/onyxstudios/cca/api/v3/scoreboard/ScoreboardComponentInitializer",
                "org/ladysnake/cca/api/v3/scoreboard/ScoreboardComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/scoreboard/ScoreboardSyncCallback",
                "org/ladysnake/cca/api/v3/scoreboard/ScoreboardSyncCallback");
        moves.put("dev/onyxstudios/cca/api/v3/scoreboard/TeamAddCallback",
                "org/ladysnake/cca/api/v3/scoreboard/TeamAddCallback");
        moves.put("dev/onyxstudios/cca/api/v3/scoreboard/TeamComponentFactory",
                "org/ladysnake/cca/api/v3/scoreboard/TeamComponentFactory");
        moves.put("dev/onyxstudios/cca/api/v3/scoreboard/TeamComponentFactoryV2",
                "org/ladysnake/cca/api/v3/scoreboard/TeamComponentFactoryV2");
        moves.put("dev/onyxstudios/cca/api/v3/util/MethodsReturnNonnullByDefault",
                "org/ladysnake/cca/api/v3/util/MethodsReturnNonnullByDefault");
        moves.put("dev/onyxstudios/cca/api/v3/util/NbtSerializable",
                "org/ladysnake/cca/api/v3/util/NbtSerializable");
        moves.put("dev/onyxstudios/cca/api/v3/world/WorldComponentInitializer",
                "org/ladysnake/cca/api/v3/world/WorldComponentInitializer");
        moves.put("dev/onyxstudios/cca/api/v3/world/WorldSyncCallback",
                "org/ladysnake/cca/api/v3/world/WorldSyncCallback");
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.CardinalShim"
        };
    }
}
