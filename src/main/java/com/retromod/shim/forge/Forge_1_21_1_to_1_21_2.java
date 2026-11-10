/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Forge 1.21.1 mods for the vanilla 1.21.2 changes. */
public class Forge_1_21_1_to_1_21_2 implements VersionShim {
    @Override public String getShimName() { return "Forge 1.21.1 to 1.21.2"; }
    @Override public String getSourceVersion() { return "1.21.1"; }
    @Override public String getTargetVersion() { return "1.21.2"; }
    @Override public String getModLoaderType() { return "forge"; }
    @Override public void registerRedirects(RetromodTransformer transformer) {
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.2");
        // Vanilla item bases removed in 1.21.2, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21.2");
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRegistryValueGetterRename(
                transformer, "net/minecraft/resources/ResourceLocation");
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerSoundEventLocation(
                transformer, "net/minecraft/resources/ResourceLocation");
        // registryOrThrow became lookupOrThrow and getHolderOrThrow became getOrThrow.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRegistryLookupRenames(transformer);
        // Same vanilla 1.21.2 changes as the NeoForge shim; Forge mods use the same Mojang names.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerMojangItemInteractionResultBridge(transformer);
        // The holder's factories and getters, and new InteractionResultHolder(result, object).
        com.retromod.shim.common.LegacyInteractionResultHolderBridge.register(transformer);
        com.retromod.shim.common.LegacyBlockApiSynthetic.register(transformer);
        com.retromod.shim.common.LegacyBlockMethodBridge.register(transformer);
        // The same release replaced Properties.lootFrom with a loot table override.
        LegacyLootFromBridge.register(transformer);
        com.retromod.shim.common.LegacyChestBlockBridge.register(transformer);
        // Villager.WANTED_ITEMS became the villager_picks_up tag, which a 1.20.5 to 1.21.1 mod still names.
        com.retromod.shim.common.LegacyAnimalFoodBridge.register(transformer);
        com.retromod.shim.common.LegacyFoodConsumableBridge.register(transformer);
        // 1.21.2 turned the eat and drink sounds into holders. A 1.21.1 mod starts after the
        // 1.20 to 1.21 shim that also registers this bridge, and it reads the host, so it gates itself.
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
        // A 1.20.1 mod's serializers read JSON, and its recipes and lookups use containers and recipe ids.
        com.retromod.shim.common.LegacyRecipeApiBridge.register(transformer);
        // Explosion became an interface; an old subclass extends a server explosion instead.
        com.retromod.shim.common.LegacyExplosionBaseBridge.register(transformer);
        // Later versions hid Level.isClientSide and Level.random behind their getters.
        com.retromod.shim.common.LegacyLevelFieldBridge.register(transformer);
        // GuiGraphics lost setColor and bufferSource, and its blits gained a render type argument.
        com.retromod.shim.common.LegacyGuiGraphicsBridge.register1212(transformer);
        // EntityType.Builder.build(String) became build(ResourceKey).
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerOfficialEntityTypeBuildBridge(transformer);
        // Util's executor getters return a TracingExecutor that wraps the old ExecutorService.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerUtilExecutorUnwrapOnHost(transformer);
        // InteractionResult.shouldSwing() is gone; a client-side Success now carries the swing.
        com.retromod.shim.common.LegacyClientInteractionSynthetic.registerSwingDecision(transformer);
        // Item.getDescription() became getName(), as on NeoForge. Exact owner, so on 26.x the
        // inherited LegacyItemNameBridge rule stays the only one for a subclass call.
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "getDescription", "()Lnet/minecraft/network/chat/Component;",
            "net/minecraft/world/item/Item", "getName", "()Lnet/minecraft/network/chat/Component;");
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
