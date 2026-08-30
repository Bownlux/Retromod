/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 * 
 * NeoForge 1.21.9 changes:
 * https://neoforged.net/news/21.9release/
 * https://neoforged.net/news/21.9-transfer-rework/
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/**
 * Shim for NeoForge 1.21.8 mods on 1.21.9+: Transfer API rework, FML state access,
 * KeyMapping category records, and the RenderHighlightEvent removal.
 */
public class NeoForge_1_21_8_to_1_21_9 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "NeoForge 1.21.8 to 1.21.9";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21.8";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.9";
    }
    
    @Override
    public String getModLoaderType() {
        return "neoforge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {

        // Transfer API rework: route deprecated IItemHandler calls through the shim
        // (IItemHandler -> ResourceHandler<ItemResource>).
        transformer.registerMethodRedirect(
            "net/neoforged/neoforge/items/IItemHandler", "getSlots", "()I",
            "com/retromod/shim/neoforge/embedded/IItemHandlerShim", "getSlots",
            "(Ljava/lang/Object;)I"
        );
        
        transformer.registerMethodRedirect(
            "net/neoforged/neoforge/items/IItemHandler", "getStackInSlot",
            "(I)Lnet/minecraft/world/item/ItemStack;",
            "com/retromod/shim/neoforge/embedded/IItemHandlerShim", "getStackInSlot",
            "(Ljava/lang/Object;I)Lnet/minecraft/world/item/ItemStack;"
        );
        
        transformer.registerMethodRedirect(
            "net/neoforged/neoforge/items/IItemHandler", "insertItem",
            "(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            "com/retromod/shim/neoforge/embedded/IItemHandlerShim", "insertItem",
            "(Ljava/lang/Object;ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"
        );
        
        transformer.registerMethodRedirect(
            "net/neoforged/neoforge/items/IItemHandler", "extractItem",
            "(IIZ)Lnet/minecraft/world/item/ItemStack;",
            "com/retromod/shim/neoforge/embedded/IItemHandlerShim", "extractItem",
            "(Ljava/lang/Object;IIZ)Lnet/minecraft/world/item/ItemStack;"
        );
        
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/items/ItemStackHandler",
            "com/retromod/shim/neoforge/embedded/ItemStackHandlerShim"
        );

        transformer.registerClassRedirect(
            "net/neoforged/neoforge/items/ComponentItemHandler",
            "com/retromod/shim/neoforge/embedded/ComponentItemHandlerShim"
        );

        // FML state moved behind FMLLoader.getCurrent(). A mod that still calls the old static
        // accessor dies with IncompatibleClassChangeError on its first call (#248).
        transformer.registerMethodRedirect(
            "net/neoforged/fml/loading/FMLLoader", "getLoadingModList",
            "()Lnet/neoforged/fml/loading/LoadingModList;",
            "com/retromod/shim/neoforge/embedded/FMLLoaderShim", "getLoadingModList",
            "()Ljava/lang/Object;"
        );
        transformer.registerMethodRedirect(
            "net/neoforged/fml/loading/FMLLoader", "getDist",
            "()Lnet/neoforged/api/distmarker/Dist;",
            "com/retromod/shim/neoforge/embedded/FMLLoaderShim", "getDist",
            "()Ljava/lang/Object;"
        );

        // KeyMapping dropped its String-category constructor for a Category record;
        // the shim builds the record from the old string.
        transformer.registerConstructorRedirect(
            "net/minecraft/client/KeyMapping",
            "(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V",
            "com/retromod/shim/neoforge/embedded/KeyMappingShim", "create",
            "(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/Object;"
        );

        // RenderHighlightEvent was replaced by ExtractBlockOutlineRenderStateEvent.
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/client/event/RenderHighlightEvent",
            "com/retromod/shim/neoforge/embedded/RenderHighlightEventShim"
        );

        transformer.registerClassRedirect(
            "net/neoforged/neoforge/client/event/RenderHighlightEvent$Block",
            "com/retromod/shim/neoforge/embedded/RenderHighlightEventShim$Block"
        );

        // Entity.getWorld() -> getEntityWorld().
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity", "getWorld", "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/Entity", "getEntityWorld", "()Lnet/minecraft/world/level/Level;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/LivingEntity", "getWorld", "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/LivingEntity", "getEntityWorld", "()Lnet/minecraft/world/level/Level;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/player/Player", "getWorld", "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/player/Player", "getEntityWorld", "()Lnet/minecraft/world/level/Level;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.neoforge.embedded.IItemHandlerShim",
            "com.retromod.shim.neoforge.embedded.ItemStackHandlerShim",
            "com.retromod.shim.neoforge.embedded.ComponentItemHandlerShim",
            "com.retromod.shim.neoforge.embedded.FMLLoaderShim",
            "com.retromod.shim.neoforge.embedded.KeyMappingShim",
            "com.retromod.shim.neoforge.embedded.RenderHighlightEventShim"
        };
    }
}
