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
        com.retromod.shim.common.LegacyBlockPropertiesBridge.register(transformer);
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
        // Block methods later made protected, called through the state's public delegate.
        com.retromod.shim.common.LegacyProtectedBlockCallBridge.register(transformer);
        com.retromod.shim.common.LegacyItemAttributeOverrideAdapter.register(transformer);
        com.retromod.shim.common.LegacyDispenserBridge.register(transformer);
        com.retromod.shim.common.LegacyToolItemBridge.register(transformer);
        com.retromod.shim.common.Legacy1205MemberBridge.register(transformer);
        // 1.20.5 priced trades in item costs, so the old item stack trade constructors are gone.
        com.retromod.shim.common.LegacyMerchantOfferBridge.register(transformer);
        // 1.20.5 also replaced the Gson loot serializers that custom loot functions and conditions used.
        com.retromod.shim.common.LegacyLootSerializerBridge.register(transformer);
        com.retromod.shim.common.LegacyLootDataBridge.register(transformer);
        // Loot tables became registry keys; old reads and calls keep getting the table's id.
        com.retromod.shim.common.LegacyLootTableKeyBridge.register(transformer);
        // An old mob's own getDefaultLootTable() id, which the host stopped asking for, picks its death drops again.
        com.retromod.shim.common.LegacyMobLootBridge.register(transformer);
        com.retromod.shim.common.LegacyCodecTypeBridge.register(transformer);
        // KeyDispatchDataCodec moved to map codecs in the same release.
        com.retromod.shim.common.LegacyKeyDispatchCodecBridge.register(transformer);
        // So did the modifier serializer, chunk generator, biome source and surface rule registries.
        com.retromod.shim.common.LegacyRegistryMapCodecBridge.register(transformer);
        com.retromod.shim.common.LegacyRegistryNbtBridge.register(transformer);
        // 1.20.5 removed MobType and SpawnPlacements.Type, moved entity data onto a builder,
        // and renamed the attribute operations. Each bridge checks the host itself.
        com.retromod.shim.common.LegacyMobTypeBridge.register(transformer);
        com.retromod.shim.common.LegacySpawnPlacementTypeBridge.register(transformer);
        com.retromod.shim.common.LegacySynchedDataBridge.register(transformer);
        com.retromod.shim.forge.ForgeNeoForgeApiBridge.registerAttributeOperationRenames(transformer);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
