/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** Trinkets accessory-slot API (Fabric): component retrieval and trinket registration go through the shim. */
public class TrinketsApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Trinkets API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "2.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "3.7.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Trinket callbacks: SlotType arg became SlotReference.
        transformer.registerMethodRedirect(
            "dev/emi/trinkets/api/Trinket",
            "tick",
            "(Lnet/minecraft/item/ItemStack;Ldev/emi/trinkets/api/SlotType;Lnet/minecraft/entity/LivingEntity;)V",
            "dev/emi/trinkets/api/Trinket",
            "tick",
            "(Lnet/minecraft/item/ItemStack;Ldev/emi/trinkets/api/SlotReference;Lnet/minecraft/entity/LivingEntity;)V"
        );

        transformer.registerMethodRedirect(
            "dev/emi/trinkets/api/Trinket",
            "onEquip",
            "(Lnet/minecraft/item/ItemStack;Ldev/emi/trinkets/api/SlotType;Lnet/minecraft/entity/LivingEntity;)V",
            "dev/emi/trinkets/api/Trinket",
            "onEquip",
            "(Lnet/minecraft/item/ItemStack;Ldev/emi/trinkets/api/SlotReference;Lnet/minecraft/entity/LivingEntity;)V"
        );

        transformer.registerMethodRedirect(
            "dev/emi/trinkets/api/Trinket",
            "onUnequip",
            "(Lnet/minecraft/item/ItemStack;Ldev/emi/trinkets/api/SlotType;Lnet/minecraft/entity/LivingEntity;)V",
            "dev/emi/trinkets/api/Trinket",
            "onUnequip",
            "(Lnet/minecraft/item/ItemStack;Ldev/emi/trinkets/api/SlotReference;Lnet/minecraft/entity/LivingEntity;)V"
        );

        // Component retrieval moved to a static accessor.
        transformer.registerMethodRedirect(
            "dev/emi/trinkets/api/TrinketsApi",
            "getTrinketComponent",
            "(Lnet/minecraft/entity/LivingEntity;)Ljava/util/Optional;",
            "com/retromod/shim/api/fabric/embedded/TrinketsShim",
            "getTrinketComponent",
            "(Lnet/minecraft/entity/LivingEntity;)Ljava/util/Optional;"
        );

        // SlotType stays: Trinkets 3.0.0 to 3.10.0 ship it beside SlotReference, so moving it broke every
        // current dependent. TrinketComponent.getInventory() returns a Map in every 3.x, so getSlots() has no
        // single-call successor either.

        transformer.registerMethodRedirect(
            "dev/emi/trinkets/api/TrinketsApi",
            "registerTrinket",
            "(Lnet/minecraft/item/Item;Ldev/emi/trinkets/api/Trinket;)V",
            "com/retromod/shim/api/fabric/embedded/TrinketsShim",
            "registerTrinket",
            "(Lnet/minecraft/item/Item;Ldev/emi/trinkets/api/Trinket;)V"
        );

        // getModifiers now takes Object slot/group args (routed through the shim).
        transformer.registerMethodRedirect(
            "dev/emi/trinkets/api/Trinket",
            "getModifiers",
            "(Lnet/minecraft/item/ItemStack;Ldev/emi/trinkets/api/SlotType;Lnet/minecraft/entity/LivingEntity;Ljava/util/UUID;)Lcom/google/common/collect/Multimap;",
            "com/retromod/shim/api/fabric/embedded/TrinketsShim",
            "getModifiers",
            "(Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Ljava/lang/Object;Lnet/minecraft/entity/LivingEntity;Ljava/util/UUID;)Lcom/google/common/collect/Multimap;"
        );

        transformer.registerMethodRedirect(
            "dev/emi/trinkets/api/SlotGroups",
            "getSlotGroup",
            "(Ljava/lang/String;)Ldev/emi/trinkets/api/SlotGroup;",
            "com/retromod/shim/api/fabric/embedded/TrinketsShim",
            "getSlotGroup",
            "(Ljava/lang/String;)Ljava/lang/Object;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.TrinketsShim"
        };
    }
}
