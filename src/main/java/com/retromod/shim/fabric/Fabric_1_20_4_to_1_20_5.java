/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/**
 * Fabric 1.20.4 to 1.20.5: NBT-to-components migration on ItemStacks, plus FoodComponent,
 * attribute, and Item.Settings reworks.
 */
public class Fabric_1_20_4_to_1_20_5 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.20.4 to 1.20.5"; }
    @Override public String getSourceVersion() { return "1.20.4"; }
    @Override public String getTargetVersion() { return "1.20.5"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // ItemStack NBT methods removed; use components
        transformer.registerMethodRedirect(
            "net/minecraft/item/ItemStack", "getNbt",
            "()Lnet/minecraft/nbt/NbtCompound;",
            "com/retromod/shim/fabric/embedded/ComponentBridgeShim", "getNbt",
            "(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/nbt/NbtCompound;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/item/ItemStack", "getOrCreateNbt",
            "()Lnet/minecraft/nbt/NbtCompound;",
            "com/retromod/shim/fabric/embedded/ComponentBridgeShim", "getOrCreateNbt",
            "(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/nbt/NbtCompound;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/item/ItemStack", "setNbt",
            "(Lnet/minecraft/nbt/NbtCompound;)V",
            "com/retromod/shim/fabric/embedded/ComponentBridgeShim", "setNbt",
            "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/nbt/NbtCompound;)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/item/ItemStack", "hasNbt",
            "()Z",
            "com/retromod/shim/fabric/embedded/ComponentBridgeShim", "hasNbt",
            "(Lnet/minecraft/item/ItemStack;)Z"
        );
        // FoodComponent changes
        transformer.registerMethodRedirect(
            "net/minecraft/item/FoodComponent$Builder", "hunger",
            "(I)Lnet/minecraft/item/FoodComponent$Builder;",
            "com/retromod/shim/fabric/embedded/FoodComponentShim", "nutrition",
            "(Ljava/lang/Object;I)Ljava/lang/Object;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/item/FoodComponent$Builder", "saturationModifier",
            "(F)Lnet/minecraft/item/FoodComponent$Builder;",
            "com/retromod/shim/fabric/embedded/FoodComponentShim", "saturationModifier",
            "(Ljava/lang/Object;F)Ljava/lang/Object;"
        );
        // Attribute system changes
        transformer.registerConstructorRedirect(
            "net/minecraft/entity/attribute/EntityAttributeModifier",
            "(Ljava/util/UUID;Ljava/lang/String;DLnet/minecraft/entity/attribute/EntityAttributeModifier$Operation;)V",
            "com/retromod/shim/fabric/embedded/AttributeShim", "createModifier",
            "(Ljava/util/UUID;Ljava/lang/String;DLjava/lang/Object;)Ljava/lang/Object;"
        );
        // Item.Settings changes
        transformer.registerMethodRedirect(
            "net/minecraft/item/Item$Settings", "food",
            "(Lnet/minecraft/item/FoodComponent;)Lnet/minecraft/item/Item$Settings;",
            "com/retromod/shim/fabric/embedded/FoodComponentShim", "food",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        );

        // BlockPathTypes renamed to PathType
        transformer.registerClassRedirect(
            "net/minecraft/world/level/pathfinder/BlockPathTypes",
            "net/minecraft/world/level/pathfinder/PathType"
        );
        // BootstapContext typo fixed to BootstrapContext
        transformer.registerClassRedirect(
            "net/minecraft/data/worldgen/BootstapContext",
            "net/minecraft/data/worldgen/BootstrapContext"
        );
        // Entity.setSecondsOnFire() renamed to igniteForSeconds()
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity", "setSecondsOnFire",
            "(I)V",
            "net/minecraft/world/entity/Entity", "igniteForSeconds",
            "(I)V"
        );
        // isSameItemSameTags renamed to isSameItemSameComponents
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack", "isSameItemSameTags",
            "(Lnet/minecraft/world/item/ItemStack;)Z",
            "net/minecraft/world/item/ItemStack", "isSameItemSameComponents",
            "(Lnet/minecraft/world/item/ItemStack;)Z"
        );
        // renderDirtBackground renamed to renderMenuBackground
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screens/Screen", "renderDirtBackground",
            "()V",
            "net/minecraft/client/gui/screens/Screen", "renderMenuBackground",
            "()V"
        );
        // load renamed to loadAdditional; covers the old single-arg CompoundTag form
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/block/entity/BlockEntity", "load",
            "(Lnet/minecraft/nbt/CompoundTag;)V",
            "net/minecraft/world/level/block/entity/BlockEntity", "loadAdditional",
            "(Lnet/minecraft/nbt/CompoundTag;)V"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
