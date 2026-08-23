/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** Redirects for Fabric API changes across the common modules. */
public class FabricApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Fabric API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "0.50.0"; // Fabric API for ~1.18
    }
    
    @Override
    public String getTargetVersion() {
        return "0.100.0"; // Fabric API for 1.21.x
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Networking: send(player, id, buf) -> send(player, payload) in 1.20.5+
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking",
            "send",
            "(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/util/Identifier;Lnet/minecraft/network/PacketByteBuf;)V",
            "com/retromod/shim/api/fabric/embedded/NetworkingShim",
            "sendLegacy",
            "(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/util/Identifier;Lnet/minecraft/network/PacketByteBuf;)V"
        );

        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/networking/v1/ClientPlayNetworking",
            "send",
            "(Lnet/minecraft/util/Identifier;Lnet/minecraft/network/PacketByteBuf;)V",
            "com/retromod/shim/api/fabric/embedded/NetworkingShim",
            "clientSendLegacy",
            "(Lnet/minecraft/util/Identifier;Lnet/minecraft/network/PacketByteBuf;)V"
        );
        
        // PacketByteBufs.create() -> new PacketByteBuf(Unpooled.buffer())
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/networking/v1/PacketByteBufs",
            "create",
            "()Lnet/minecraft/network/PacketByteBuf;",
            "com/retromod/shim/api/fabric/embedded/NetworkingShim",
            "createBuf",
            "()Lnet/minecraft/network/PacketByteBuf;"
        );
        
        // Item groups: modifyEntriesEvent changed signature in 1.20+
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/itemgroup/v1/ItemGroupEvents",
            "modifyEntriesEvent",
            "(Lnet/minecraft/item/ItemGroup;)Lnet/fabricmc/fabric/api/event/Event;",
            "com/retromod/shim/api/fabric/embedded/ItemGroupShim",
            "modifyEntriesEvent",
            "(Lnet/minecraft/item/ItemGroup;)Lnet/fabricmc/fabric/api/event/Event;"
        );
        
        // FabricItemGroupBuilder removed in 1.19.3
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/itemgroup/FabricItemGroupBuilder",
            "com/retromod/shim/api/fabric/embedded/FabricItemGroupBuilderShim"
        );
        
        // FabricBlockSettings.of(material) -> AbstractBlock.Settings.create(); Material removed in 1.20
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings",
            "of",
            "(Lnet/minecraft/block/Material;)Lnet/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings;",
            "com/retromod/shim/api/fabric/embedded/BlockSettingsShim",
            "of",
            "(Ljava/lang/Object;)Lnet/minecraft/block/AbstractBlock$Settings;"
        );
        
        // FabricBlockSettings.copy(block) signature changed
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings",
            "copy",
            "(Lnet/minecraft/block/AbstractBlock;)Lnet/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings;",
            "net/minecraft/block/AbstractBlock$Settings",
            "copy",
            "(Lnet/minecraft/block/AbstractBlock;)Lnet/minecraft/block/AbstractBlock$Settings;"
        );
        
        // FabricItemSettings removed -> Item.Settings
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/item/v1/FabricItemSettings",
            "net/minecraft/item/Item$Settings"
        );

        // Registries overhaul (1.19.3+): Registry.register -> Registries
        transformer.registerMethodRedirect(
            "net/minecraft/util/registry/Registry",
            "register",
            "(Lnet/minecraft/util/registry/Registry;Lnet/minecraft/util/Identifier;Ljava/lang/Object;)Ljava/lang/Object;",
            "net/minecraft/registry/Registry",
            "register",
            "(Lnet/minecraft/registry/Registry;Lnet/minecraft/util/Identifier;Ljava/lang/Object;)Ljava/lang/Object;"
        );
        
        transformer.registerClassRedirect(
            "net/minecraft/util/registry/Registry",
            "net/minecraft/registry/Registries"
        );

        // BuiltinRegistries -> BuiltInRegistries
        transformer.registerClassRedirect(
            "net/minecraft/util/registry/BuiltinRegistries",
            "net/minecraft/registry/BuiltInRegistries"
        );

        // Rendering
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/blockrenderlayer/v1/BlockRenderLayerMap",
            "putBlock",
            "(Lnet/minecraft/block/Block;Lnet/minecraft/client/render/RenderLayer;)V",
            "com/retromod/shim/api/fabric/embedded/RenderLayerShim",
            "putBlock",
            "(Lnet/minecraft/block/Block;Lnet/minecraft/client/render/RenderLayer;)V"
        );
        
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry",
            "BLOCK",
            "Lnet/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry;",
            "com/retromod/shim/api/fabric/embedded/ColorProviderShim",
            "getBlockRegistry",
            "()Ljava/lang/Object;"
        );

        // Resource loader
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/resource/ResourceManagerHelper",
            "get",
            "(Lnet/minecraft/resource/ResourceType;)Lnet/fabricmc/fabric/api/resource/ResourceManagerHelper;",
            "com/retromod/shim/api/fabric/embedded/ResourceShim",
            "getHelper",
            "(Lnet/minecraft/resource/ResourceType;)Lnet/fabricmc/fabric/api/resource/ResourceManagerHelper;"
        );

        // Transfer/storage API (1.20+)
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/transfer/v1/item/ItemStorage",
            "SIDED",
            "Lnet/fabricmc/fabric/api/lookup/v1/block/BlockApiLookup;",
            "com/retromod/shim/api/fabric/embedded/TransferApiShim",
            "getItemStorageSided",
            "()Lnet/fabricmc/fabric/api/lookup/v1/block/BlockApiLookup;"
        );

        // Loot v1 -> v2: LootTableLoadingCallback removed
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/loot/v1/LootTableLoadingCallback",
            "com/retromod/shim/api/fabric/embedded/LootTableShim"
        );

        // Player interaction events
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/event/player/UseBlockCallback",
            "EVENT",
            "Lnet/fabricmc/fabric/api/event/Event;",
            "com/retromod/shim/api/fabric/embedded/PlayerEventsShim",
            "getUseBlockEvent",
            "()Lnet/fabricmc/fabric/api/event/Event;"
        );

        // Biome modification (1.19+)
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/biome/v1/BiomeModifications",
            "addFeature",
            "(Ljava/util/function/Predicate;Lnet/minecraft/world/gen/GenerationStep$Feature;Lnet/minecraft/util/registry/RegistryKey;)V",
            "com/retromod/shim/api/fabric/embedded/BiomeShim",
            "addFeature",
            "(Ljava/util/function/Predicate;Lnet/minecraft/world/gen/GenerationStep$Feature;Lnet/minecraft/registry/RegistryKey;)V"
        );

        // Screen events
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents",
            "beforeRender",
            "(Lnet/minecraft/client/gui/screen/Screen;)Lnet/fabricmc/fabric/api/event/Event;",
            "com/retromod/shim/api/fabric/embedded/ScreenEventsShim",
            "beforeRender",
            "(Lnet/minecraft/client/gui/screen/Screen;)Lnet/fabricmc/fabric/api/event/Event;"
        );

        // FabricDimensions.teleport signature changed
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/dimension/v1/FabricDimensions",
            "teleport",
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/server/world/ServerWorld;Lnet/fabricmc/fabric/api/dimension/v1/FabricDimensions$TeleportTarget;)Lnet/minecraft/entity/Entity;",
            "com/retromod/shim/api/fabric/embedded/DimensionShim",
            "teleport",
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/server/world/ServerWorld;Ljava/lang/Object;)Lnet/minecraft/entity/Entity;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.NetworkingShim",
            "com.retromod.shim.api.fabric.embedded.ItemGroupShim",
            "com.retromod.shim.api.fabric.embedded.FabricItemGroupBuilderShim",
            "com.retromod.shim.api.fabric.embedded.BlockSettingsShim",
            "com.retromod.shim.api.fabric.embedded.RenderLayerShim",
            "com.retromod.shim.api.fabric.embedded.ColorProviderShim",
            "com.retromod.shim.api.fabric.embedded.ResourceShim",
            "com.retromod.shim.api.fabric.embedded.TransferApiShim",
            "com.retromod.shim.api.fabric.embedded.LootTableShim",
            "com.retromod.shim.api.fabric.embedded.PlayerEventsShim",
            "com.retromod.shim.api.fabric.embedded.BiomeShim",
            "com.retromod.shim.api.fabric.embedded.ScreenEventsShim",
            "com.retromod.shim.api.fabric.embedded.DimensionShim"
        };
    }
}
