/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** 1.21.5 folded the tool/armor Item subclasses into Item (data components). */
public class NeoForge_1_21_4_to_1_21_5 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.4 to 1.21.5"; }
    @Override public String getSourceVersion() { return "1.21.4"; }
    @Override public String getTargetVersion() { return "1.21.5"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.5");
        // RenderTarget.bindWrite went too, and DynamicTexture's constructors gained a debug label.
        com.retromod.shim.common.LegacyRenderCallBridge.registerMainTargetBind(transformer);
        com.retromod.shim.common.LegacyRenderCallBridge.registerDynamicTextureLabels(transformer);
        // PoseStack, Transformation, and ItemTransform took JOML's read-only interfaces.
        com.retromod.shim.common.JomlParameterWidening.register1_21_5(transformer);
        // Vanilla item bases removed in 1.21.5, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21.5");
        // 1.21.2 made smithing transform ingredients Optional. Patchouli's accessor callers unwrap
        // them from here, the first version the matching Mixin accessor repair runs on.
        com.retromod.shim.common.PatchouliSmithingAccessorBridge.register(transformer);
        // appendHoverText moved to TooltipDisplay + Consumer; keep old tooltip overrides called.
        com.retromod.shim.common.LegacyTooltipSynthetic.register(transformer);
        // LeavesBlock became abstract with a (float, Properties) constructor.
        com.retromod.shim.common.LegacyLeavesBlockBridge.register(transformer);
        // ChestBlock's AABB constants went when block shapes were rebuilt.
        com.retromod.shim.common.LegacyChestShapesBridge.register(transformer);
        com.retromod.shim.common.LegacyMobEffectNames.register(transformer);
        // Region tickets became tickets with a radius and no value.
        com.retromod.shim.common.LegacyChunkTicketBridge.register(transformer);
        // 1.21.5 made the NBT getters return Optional and renamed or reshaped their neighbors.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerNbtReadRefactor1215(transformer);
        // ItemStack.parseOptional and saveOptional went with it.
        com.retromod.shim.common.LegacyItemNbtApi.register1215(transformer);
        // 1.21.5 moved a biome spawn entry's weight into a weighted list; the bridge reads the host.
        com.retromod.shim.common.LegacyBiomeSpawnsBridge.register(transformer);
        // WeightedEntry and its wrapper went; the wrapper is the host's Weighted record now.
        com.retromod.shim.common.LegacyWeightedEntryBridge.register(transformer);
        // VillagerData.getProfession() became profession(), which returns a holder.
        com.retromod.shim.common.LegacyVillagerBridge.registerProfessionGetter(transformer);
        // ClickEvent and HoverEvent became interfaces, so their old constructors go through a factory.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerTextEventBridges26x(transformer);
        // MultiPlayerGameMode.hasInfiniteItems() is gone; the player's own ability answers it.
        com.retromod.shim.common.LegacyClientInteractionSynthetic.registerInfiniteItemsCheck(transformer);

        // The tool and armour classes are not redirected to Item. Redirecting them won the extends
        // slot ahead of the removed-base rebase, so a sword became "extends Item" with a super call
        // to an Item(Tier, int, float, Properties) constructor that does not exist. The rebase
        // registered above handles SwordItem, PickaxeItem, DiggerItem and ArmorItem; AxeItem,
        // ShovelItem and HoeItem still exist until 26.3, whose shim rebases them.
    }

    @Override public String[] getShimClasses() { return new String[0]; }
}
