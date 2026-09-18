/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** NeoForge 1.21.1 to 1.21.2: shader rename, InteractionResult merge, Registry renames, Attribute GENERIC_ drop. */
public class NeoForge_1_21_1_to_1_21_2 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.1 to 1.21.2"; }
    @Override public String getSourceVersion() { return "1.21.1"; }
    @Override public String getTargetVersion() { return "1.21.2"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
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
