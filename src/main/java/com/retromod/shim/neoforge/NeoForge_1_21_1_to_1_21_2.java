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
        // Vanilla item bases removed in 1.21.2, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21.2");
        // NeoForge's own classes that moved package after 21.1, on hosts that moved them.
        NeoForgeMovedClasses.register(transformer);
        NeoForgeMovedMembers.register(transformer);
        LegacyLootModifierBridge.register(transformer);
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRegistryValueGetterRename(
                transformer, "net/minecraft/resources/ResourceLocation");
        // 1.21.2 pathfinding refactor renamed the PathType constants.
        String pathType = "net/minecraft/world/level/pathfinder/PathType";
        transformer.registerFieldRedirect(pathType, "DAMAGE_FIRE", pathType, "FIRE");
        transformer.registerFieldRedirect(pathType, "DANGER_FIRE", pathType, "FIRE_IN_NEIGHBOR");
        transformer.registerFieldRedirect(pathType, "DAMAGE_OTHER", pathType, "DAMAGING");
        transformer.registerFieldRedirect(pathType, "DANGER_OTHER", pathType, "DAMAGING_IN_NEIGHBOR");

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

        // updateShape/getOcclusionShape signatures and the BlockItem description id changed.
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
        com.retromod.shim.common.LegacyExplosionBaseBridge.registerNeoForge(transformer);
        // Later versions hid Level.isClientSide and Level.random behind their getters.
        com.retromod.shim.common.LegacyLevelFieldBridge.register(transformer);
        // NeoForge answers an item's crafting remainder from an item instance.
        LegacyCraftingRemainderBridge.register(transformer);
        // EntityType.Builder.build(String) became build(ResourceKey).
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerOfficialEntityTypeBuildBridge(transformer);

        transformer.registerMethodRedirect(
            "net/minecraft/core/RegistryAccess", "registryOrThrow",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Registry;",
            "net/minecraft/core/RegistryAccess", "lookupOrThrow",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Registry;"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/core/Registry", "getHolderOrThrow",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;",
            "net/minecraft/core/Registry", "getOrThrow",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/core/Registry", "getOptional",
            "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;",
            "net/minecraft/core/Registry", "getOptionalValue",
            "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;"
        );

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
