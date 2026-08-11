/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** NeoForge 1.20.4 to 1.20.5: NBT item data became components, LazyOptional became Optional. */
public class NeoForge_1_20_4_to_1_20_5 implements VersionShim {

    @Override public String getShimName() { return "NeoForge 1.20.4 to 1.20.5"; }
    @Override public String getSourceVersion() { return "1.20.4"; }
    @Override public String getTargetVersion() { return "1.20.5"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // NBT item tags route through the component bridge
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack", "getTag",
            "()Lnet/minecraft/nbt/CompoundTag;",
            "com/retromod/shim/neoforge/embedded/ComponentBridgeShim", "getTag",
            "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/nbt/CompoundTag;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack", "getOrCreateTag",
            "()Lnet/minecraft/nbt/CompoundTag;",
            "com/retromod/shim/neoforge/embedded/ComponentBridgeShim", "getOrCreateTag",
            "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/nbt/CompoundTag;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack", "setTag",
            "(Lnet/minecraft/nbt/CompoundTag;)V",
            "com/retromod/shim/neoforge/embedded/ComponentBridgeShim", "setTag",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/nbt/CompoundTag;)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack", "hasTag",
            "()Z",
            "com/retromod/shim/neoforge/embedded/ComponentBridgeShim", "hasTag",
            "(Lnet/minecraft/world/item/ItemStack;)Z"
        );
        // capabilities moved package, LazyOptional became Optional
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/common/capabilities/ICapabilityProvider",
            "net/neoforged/neoforge/capabilities/ICapabilityProvider"
        );
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/common/util/LazyOptional",
            "java/util/Optional"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/food/FoodProperties$Builder", "nutrition",
            "(I)Lnet/minecraft/world/food/FoodProperties$Builder;",
            "com/retromod/shim/neoforge/embedded/FoodPropertiesShim", "nutrition",
            "(Ljava/lang/Object;I)Ljava/lang/Object;"
        );
        transformer.registerConstructorRedirect(
            "net/minecraft/world/entity/ai/attributes/AttributeModifier",
            "(Ljava/util/UUID;Ljava/lang/String;DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)V",
            "com/retromod/shim/neoforge/embedded/AttributeShim", "createModifier",
            "(Ljava/util/UUID;Ljava/lang/String;DLjava/lang/Object;)Ljava/lang/Object;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
