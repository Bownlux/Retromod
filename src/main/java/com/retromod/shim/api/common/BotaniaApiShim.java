/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.MinecraftVersionedApiShim;

/** Botania (Fabric and Forge/NeoForge): mana, flower, corporea, brew, and wand API renames. */
public class BotaniaApiShim implements MinecraftVersionedApiShim {
    
    @Override
    public String getShimName() {
        return "Botania API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.18.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "common";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "vazkii/botania/api/BotaniaAPI",
            "vazkii/botania/api/BotaniaAPI"
        );

        // mana system: drop the I prefix
        transformer.registerClassRedirect(
            "vazkii/botania/api/mana/IManaReceiver",
            "vazkii/botania/api/mana/ManaReceiver"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/mana/IManaPool",
            "vazkii/botania/api/mana/ManaPool"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/mana/IManaCollector",
            "vazkii/botania/api/mana/ManaCollector"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/mana/IManaSpreader",
            "vazkii/botania/api/mana/ManaSpreader"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/mana/IManaItem",
            "vazkii/botania/api/mana/ManaItem"
        );

        transformer.registerMethodRedirect(
            "vazkii/botania/api/mana/ManaItemHandler",
            "requestMana",
            "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/PlayerEntity;IZ)I",
            "vazkii/botania/api/mana/ManaItemHandler",
            "requestMana",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;IZ)I"
        );

        // subtile.TileEntity*Flower -> block_entity.*FlowerBlockEntity
        transformer.registerClassRedirect(
            "vazkii/botania/api/subtile/TileEntityFunctionalFlower",
            "vazkii/botania/api/block_entity/FunctionalFlowerBlockEntity"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/subtile/TileEntityGeneratingFlower",
            "vazkii/botania/api/block_entity/GeneratingFlowerBlockEntity"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/subtile/TileEntitySpecialFlower",
            "vazkii/botania/api/block_entity/SpecialFlowerBlockEntity"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/corporea/ICorporeaNode",
            "vazkii/botania/api/corporea/CorporeaNode"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/corporea/ICorporeaSpark",
            "vazkii/botania/api/corporea/CorporeaSpark"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/corporea/ICorporeaRequestMatcher",
            "vazkii/botania/api/corporea/CorporeaRequestMatcher"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/brew/Brew",
            "vazkii/botania/api/brew/Brew"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/brew/IBrewContainer",
            "vazkii/botania/api/brew/BrewContainer"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/recipe/IRuneAltarRecipe",
            "vazkii/botania/api/recipe/RuneAltarRecipe"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/recipe/IElvenTradeRecipe",
            "vazkii/botania/api/recipe/ElvenTradeRecipe"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/subtile/TileEntityBindableSpecialFlower",
            "vazkii/botania/api/block_entity/BindableSpecialFlowerBlockEntity"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/wand/IWandable",
            "vazkii/botania/api/block/Wandable"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/wand/IWandBindable",
            "vazkii/botania/api/block/WandBindable"
        );

        transformer.registerClassRedirect(
            "vazkii/botania/api/mana/ILens",
            "vazkii/botania/api/mana/Lens"
        );
        
        transformer.registerClassRedirect(
            "vazkii/botania/api/mana/ICompositableLens",
            "vazkii/botania/api/mana/CompositableLens"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.common.embedded.BotaniaShim"
        };
    }
}
