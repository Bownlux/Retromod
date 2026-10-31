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
        // Vanilla item bases removed in 1.21.2, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21.2");
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRegistryValueGetterRename(
                transformer, "net/minecraft/resources/ResourceLocation");
        // Same vanilla 1.21.2 changes as the NeoForge shim; Forge mods use the same Mojang names.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerMojangItemInteractionResultBridge(transformer);
        com.retromod.shim.common.LegacyBlockApiSynthetic.register(transformer);
        com.retromod.shim.common.LegacyBlockMethodBridge.register(transformer);
        com.retromod.shim.common.LegacyChestBlockBridge.register(transformer);
        com.retromod.shim.common.LegacyFoodConsumableBridge.register(transformer);
        com.retromod.shim.common.LegacyItemConstructorBridge.register(transformer);
        // Entity and recipe members that 1.21.2 removed or made stricter.
        com.retromod.shim.common.Legacy1212MemberBridge.register(transformer);
        // 1.21.2 made armor materials plain records, with no registry to put them in.
        com.retromod.shim.common.LegacyArmorMaterialRecordBridge.register(transformer);
        // Tool rules take block holder sets instead of a tag or a list of blocks.
        com.retromod.shim.common.LegacyToolRuleBridge.register(transformer);
        // Recipes are listed through the recipe map and describe themselves through displays.
        com.retromod.shim.common.LegacyRecipeIntrospectionBridge.register(transformer);
        com.retromod.shim.common.LegacySpecialRecipeClasses.register(transformer);
        // Explosion became an interface; an old subclass extends a server explosion instead.
        com.retromod.shim.common.LegacyExplosionBaseBridge.register(transformer);
        // Later versions hid Level.isClientSide and Level.random behind their getters.
        com.retromod.shim.common.LegacyLevelFieldBridge.register(transformer);
        // EntityType.Builder.build(String) became build(ResourceKey).
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerOfficialEntityTypeBuildBridge(transformer);
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
