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
        // 1.20.5 split Properties.copy into ofFullCopy and ofLegacyCopy; the legacy one copies what copy did.
        transformer.registerMethodRedirect("net/minecraft/world/level/block/state/BlockBehaviour$Properties", "copy",
                "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
                "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "ofLegacyCopy",
                "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;");
        com.retromod.shim.forge.LegacyEntityTemplateAdapter.register(transformer);
        com.retromod.shim.forge.LegacyTierBridge.register(transformer);
        // DFU 8 (1.20.5) removed DataResult.get(); its Either is rebuilt from the result.
        transformer.registerMethodRedirect("com/mojang/serialization/DataResult", "get",
                "()Lcom/mojang/datafixers/util/Either;",
                "com/retromod/polyfill/minecraft/DataResultPolyfill", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        // 1.20.5 made ArmorMaterial a registered record, replaced particle deserializers with
        // codecs, deleted PotionUtils, and folded SimpleFoiledItem into the glint component.
        // Each bridge probes the host.
        com.retromod.shim.fabric.Pre1_20_5MojangArmorMaterialBridge.register(transformer);
        com.retromod.shim.fabric.Pre1_20_5MojangParticleTypeBridge.register(transformer);
        com.retromod.shim.common.LegacyPotionUtilsBridge.register(transformer);
        com.retromod.shim.common.LegacyHolderConstantBridge.register(transformer);
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.20.5");

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
        // Food and attributes go through the host-checked bridges. The redirects that stood here
        // named FoodPropertiesShim and AttributeShim, which were never written, and nutrition(I)
        // still exists, so they broke working calls with NoClassDefFoundError.
        com.retromod.shim.common.LegacyFoodPropertiesBridge.register(transformer);
        // NeoForge stopped opening MenuScreens.register; mods from before still call it.
        com.retromod.shim.forge.LegacyMenuScreensBridge.register(transformer);
        com.retromod.shim.common.LegacyAttributeApiBridge.register(transformer);
        com.retromod.shim.common.LegacyItemAttributeOverrideAdapter.register(transformer);
        com.retromod.shim.common.LegacyDispenserBridge.register(transformer);
        com.retromod.shim.common.LegacyToolItemBridge.register(transformer);
        com.retromod.shim.common.Legacy1205MemberBridge.register(transformer);
        com.retromod.shim.common.LegacyCodecTypeBridge.register(transformer);
        com.retromod.shim.common.LegacyRegistryNbtBridge.register(transformer);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
