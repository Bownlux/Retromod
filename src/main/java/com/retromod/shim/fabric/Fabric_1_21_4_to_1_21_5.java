/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Shim for Fabric 1.21.4 to 1.21.5. */
public class Fabric_1_21_4_to_1_21_5 implements VersionShim {
    @Override public String getShimName() { return "Fabric 1.21.4 to 1.21.5"; }
    @Override public String getSourceVersion() { return "1.21.4"; }
    @Override public String getTargetVersion() { return "1.21.5"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.5");
        // The tooltip adapter matches Mojang names, which a Fabric mod only has after the 26.x remap.
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.LegacyTooltipSynthetic.register(transformer);
            // RenderTarget.bindWrite went too, and DynamicTexture's constructors gained a debug label.
            com.retromod.shim.common.LegacyRenderCallBridge.registerMainTargetBind(transformer);
            com.retromod.shim.common.LegacyRenderCallBridge.registerDynamicTextureLabels(transformer);
            // LeavesBlock became abstract with a (float, Properties) constructor.
            com.retromod.shim.common.LegacyLeavesBlockBridge.register(transformer);
            // ChestBlock's AABB constants went when block shapes were rebuilt.
            com.retromod.shim.common.LegacyChestShapesBridge.register(transformer);
            com.retromod.shim.common.LegacyMobEffectNames.register(transformer);
            // Region tickets became tickets with a radius and no value.
            com.retromod.shim.common.LegacyChunkTicketBridge.register(transformer);
            // WeightedEntry and its wrapper went; the wrapper is the host's Weighted record now.
            com.retromod.shim.common.LegacyWeightedEntryBridge.register(transformer);
            // VillagerData.getProfession() became profession(), which returns a holder.
            com.retromod.shim.common.LegacyVillagerBridge.registerProfessionGetter(transformer);
        } else {
            // Before 26.x the same calls keep their intermediary names.
            com.retromod.shim.common.LegacyChunkTicketBridge.registerIntermediary(transformer);
            // The NBT getters returned Optional from 1.21.5 under the same intermediary names.
            com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerNbtReadRefactor1215Intermediary(transformer);
            // ItemStack.parseOptional and saveOptional went with them.
            com.retromod.shim.common.LegacyItemNbtApi.register1215Intermediary(transformer);
            // PoseStack, Transformation, and ItemTransform took JOML's read-only interfaces.
            com.retromod.shim.common.JomlParameterWidening.registerIntermediary1_21_5(transformer);
            com.retromod.shim.common.LegacyWeightedEntryBridge.registerIntermediary(transformer);
            com.retromod.shim.common.LegacyVillagerBridge.registerProfessionGetterIntermediary(transformer);
        }

        // The tool and armour classes are not redirected to Item. Redirecting them won the extends
        // slot ahead of the removed-base rebase, so a sword became "extends Item" with a super call
        // to an Item(Tier, int, float, Properties) constructor that does not exist. The rebase
        // registered from 26.1 handles SwordItem, PickaxeItem, DiggerItem and ArmorItem; AxeItem,
        // ShovelItem and HoeItem still exist until 26.3, whose shim rebases them.

        transformer.registerClassRedirect(
            "net/minecraft/world/level/saveddata/maps/ForcedChunksSavedData",
            "net/minecraft/world/level/saveddata/maps/TicketStorage"
        );

        // blockUsingShield became blockUsingItem (any blocking item, not just shields).
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/LivingEntity", "blockUsingShield",
            "(Lnet/minecraft/world/entity/LivingEntity;)V",
            "net/minecraft/world/entity/LivingEntity", "blockUsingItem",
            "(Lnet/minecraft/world/entity/LivingEntity;)V"
        );
    }
    
    @Override public String[] getShimClasses() { return new String[0]; }
}
