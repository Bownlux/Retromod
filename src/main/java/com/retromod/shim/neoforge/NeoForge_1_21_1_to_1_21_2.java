/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates NeoForge 1.21.1 mods for the vanilla 1.21.2 renames and merged classes. */
public class NeoForge_1_21_1_to_1_21_2 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.1 to 1.21.2"; }
    @Override public String getSourceVersion() { return "1.21.1"; }
    @Override public String getTargetVersion() { return "1.21.2"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.2");
        // Vanilla item bases removed in 1.21.2, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21.2");
        // NeoForge's own classes that moved package after 21.1, on hosts that moved them.
        NeoForgeMovedClasses.register(transformer);
        NeoForgeMovedMembers.register(transformer);
        LegacyLootModifierBridge.register(transformer);
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRegistryValueGetterRename(
                transformer, "net/minecraft/resources/ResourceLocation");
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerSoundEventLocation(
                transformer, "net/minecraft/resources/ResourceLocation");
        // PathType's constants keep their names until 26.1, so their renames live in
        // Common_1_21_11_to_26_1_ClassMoves.

        transformer.registerClassRedirect(
            "net/minecraft/client/renderer/ShaderInstance",
            "net/minecraft/client/renderer/CompiledShaderProgram"
        );

        // InteractionResultHolder and ItemInteractionResult folded into InteractionResult
        transformer.registerClassRedirect(
            "net/minecraft/world/InteractionResultHolder",
            "net/minecraft/world/InteractionResult"
        );
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerMojangItemInteractionResultBridge(transformer);
        // The holder's factories and getters, and new InteractionResultHolder(result, object).
        com.retromod.shim.common.LegacyInteractionResultHolderBridge.register(transformer);

        // updateShape/getOcclusionShape signatures and the BlockItem description id changed.
        com.retromod.shim.common.LegacyBlockApiSynthetic.register(transformer);
        com.retromod.shim.common.LegacyBlockMethodBridge.register(transformer);
        // The same release replaced Properties.lootFrom with a loot table override.
        com.retromod.shim.forge.LegacyLootFromBridge.register(transformer);
        com.retromod.shim.common.LegacyChestBlockBridge.register(transformer);
        // Villager.WANTED_ITEMS became the villager_picks_up tag, which a 1.20.5 to 1.21.1 mod still names.
        com.retromod.shim.common.LegacyAnimalFoodBridge.register(transformer);
        com.retromod.shim.common.LegacyFoodConsumableBridge.register(transformer);
        // 1.21.2 turned the eat and drink sounds into holders. A 1.21.1 mod never passes the
        // 1.20.5 shims that register this bridge, and it reads the host, so it gates itself.
        com.retromod.shim.common.LegacyHolderConstantBridge.register(transformer);
        com.retromod.shim.common.LegacyItemConstructorBridge.register(transformer);
        // 1.21.2 renamed the boat layer factory and packs map colors as ARGB.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerBoatLayerRenameOnHost(transformer);
        com.retromod.shim.common.LegacyMapColorBridge.register(transformer);
        // Entity and recipe members that 1.21.2 removed or made stricter.
        com.retromod.shim.common.Legacy1212MemberBridge.register(transformer);
        // The build height getters became inclusive under new names; the old ones answer one higher.
        com.retromod.shim.common.LegacyBuildHeightBridge.register(transformer);
        // Reload listeners lost their two profilers; old listeners get the host's reload.
        com.retromod.shim.common.LegacyReloadListenerBridge.register(transformer);
        // 1.21.2 made armor materials plain records, with no registry to put them in.
        com.retromod.shim.common.LegacyArmorMaterialRecordBridge.register(transformer);
        // Tool rules take block holder sets instead of a tag or a list of blocks.
        com.retromod.shim.common.LegacyToolRuleBridge.register(transformer);
        // Recipes are listed through the recipe map and describe themselves through displays.
        com.retromod.shim.common.LegacyRecipeIntrospectionBridge.register(transformer);
        com.retromod.shim.common.LegacySpecialRecipeClasses.register(transformer);
        // Explosion became an interface; an old subclass extends a server explosion instead.
        com.retromod.shim.common.LegacyExplosionBaseBridge.register(transformer);
        com.retromod.shim.common.LegacyExplosionBaseBridge.registerNeoForge(transformer);
        // Later versions hid Level.isClientSide and Level.random behind their getters.
        com.retromod.shim.common.LegacyLevelFieldBridge.register(transformer);
        // GuiGraphics lost setColor and bufferSource, and its blits gained a render type argument.
        com.retromod.shim.common.LegacyGuiGraphicsBridge.register1212(transformer);
        // NeoForge answers an item's crafting remainder from an item instance.
        LegacyCraftingRemainderBridge.register(transformer);
        // EntityType.Builder.build(String) became build(ResourceKey).
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerOfficialEntityTypeBuildBridge(transformer);
        // Util's executor getters return a TracingExecutor that wraps the old ExecutorService.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerUtilExecutorUnwrapOnHost(transformer);
        // InteractionResult.shouldSwing() is gone; a client-side Success now carries the swing.
        com.retromod.shim.common.LegacyClientInteractionSynthetic.registerSwingDecision(transformer);

        // registryOrThrow became lookupOrThrow and getHolderOrThrow became getOrThrow.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRegistryLookupRenames(transformer);

        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "getDescription",
            "()Lnet/minecraft/network/chat/Component;",
            "net/minecraft/world/item/Item", "getName",
            "()Lnet/minecraft/network/chat/Component;"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "getCraftingRemainingItem",
            "()Lnet/minecraft/world/item/Item;",
            "net/minecraft/world/item/Item", "getCraftingRemainder",
            "()Lnet/minecraft/world/item/Item;"
        );

        // GENERIC_ prefix dropped from Attributes in 1.21.2
        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_MAX_HEALTH",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "MAX_HEALTH",
            "Lnet/minecraft/core/Holder;"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_MOVEMENT_SPEED",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "MOVEMENT_SPEED",
            "Lnet/minecraft/core/Holder;"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_ATTACK_DAMAGE",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "ATTACK_DAMAGE",
            "Lnet/minecraft/core/Holder;"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_ARMOR",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "ARMOR",
            "Lnet/minecraft/core/Holder;"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_KNOCKBACK_RESISTANCE",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "KNOCKBACK_RESISTANCE",
            "Lnet/minecraft/core/Holder;"
        );
    }

    @Override public String[] getShimClasses() { return new String[0]; }
}
