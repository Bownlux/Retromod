/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Fabric API changes: https://fabricmc.net/2024/10/14/1212.html
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Fabric 1.21.1 mods for 1.21.2. */
public class Fabric_1_21_1_to_1_21_2 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "Fabric 1.21.1 to 1.21.2";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21.1";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.2";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    /** Two 1.21.2 member changes, in the intermediary names a Fabric host before 26.1 runs. */
    private static void registerIntermediaryMemberChanges(RetromodTransformer transformer) {
        // Property.getPossibleValues() now returns a List, which is still the Collection the old
        // call returned.
        String property = "net/minecraft/class_2769";
        transformer.registerInheritedMethodRedirect(property, "method_11898", "()Ljava/util/Collection;",
                property, "method_11898", "()Ljava/util/List;");
        // Entity.hurt(source, amount) split into server and client halves, and hurtOrSimulate is
        // the old call; the old intermediary id now names the one-argument client half.
        // A helper rather than a rename: a mob's own override of the old method must still run,
        // and that override's super call, which names its direct superclass, must still link.
        com.retromod.shim.common.Legacy1212MemberBridge.registerIntermediaryHurt(transformer);
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {

        // Vanilla attributes became Holder<Attribute>. Distributed Fabric mods use these
        // intermediary names, so retype both the constants and AttributeSupplier.Builder.add.
        String attributes = "net/minecraft/class_5134";
        String attribute = "Lnet/minecraft/class_1320;";
        String holder = "Lnet/minecraft/class_6880;";
        for (String field : new String[]{
                "field_23716", "field_23717", "field_23718", "field_23719", "field_23720",
                "field_23721", "field_23722", "field_23723", "field_23724", "field_23725",
                "field_23726", "field_23727", "field_23728"}) {
            transformer.registerFieldRedirect(
                    attributes, field, attribute, attributes, field, holder);
        }
        String builder = "net/minecraft/class_5132$class_5133";
        String returnsBuilder = "L" + builder + ";";
        transformer.registerMethodRedirect(
                builder, "method_26867", "(" + attribute + ")" + returnsBuilder,
                builder, "method_26867", "(" + holder + ")" + returnsBuilder);
        transformer.registerMethodRedirect(
                builder, "method_26868", "(" + attribute + "D)" + returnsBuilder,
                builder, "method_26868", "(" + holder + "D)" + returnsBuilder);

        // Smithing transform recipes made template and addition Optional. Patchouli exposes the
        // old fields through an accessor mixin. Its accessor methods are retyped by the mixin
        // repair, while these call-site adapters preserve the Ingredient-returning API it uses.
        String patchouliSmithing =
                "vazkii/patchouli/mixin/AccessorSmithingTransformRecipe";
        String ingredient = "Lnet/minecraft/class_1856;";
        for (String method : new String[]{"getTemplate", "getAddition"}) {
            transformer.registerReturnUnwrapRedirect(
                    patchouliSmithing, method, "()" + ingredient,
                    "retromod$" + method + "Optional", "()Ljava/util/Optional;",
                    "java/util/Optional", "orElseThrow", "()Ljava/lang/Object;", false);
        }

        // SoundEvent became a record. The removed getId intermediary name has no mapping entry,
        // so old Fabric mods otherwise keep calling a method that no longer exists.
        transformer.registerMethodRedirect(
                "net/minecraft/class_3414", "method_14833",
                "()Lnet/minecraft/class_2960;",
                "net/minecraft/class_3414", "comp_3319",
                "()Lnet/minecraft/class_2960;");

        // PathType refactor: DAMAGE_X/DANGER_X -> X/X_IN_NEIGHBOR, OTHER -> DAMAGING.
        String pathType = "net/minecraft/world/level/pathfinder/PathType";
        transformer.registerFieldRedirect(pathType, "DAMAGE_FIRE", pathType, "FIRE");
        transformer.registerFieldRedirect(pathType, "DANGER_FIRE", pathType, "FIRE_IN_NEIGHBOR");
        transformer.registerFieldRedirect(pathType, "DAMAGE_OTHER", pathType, "DAMAGING");
        transformer.registerFieldRedirect(pathType, "DANGER_OTHER", pathType, "DAMAGING_IN_NEIGHBOR");

        // WorldVersion became a record: getters -> record accessors.
        transformer.registerMethodRedirect(
            "net/minecraft/WorldVersion", "getName", "()Ljava/lang/String;",
            "net/minecraft/WorldVersion", "name", "()Ljava/lang/String;"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/WorldVersion", "getId", "()Ljava/lang/String;",
            "net/minecraft/WorldVersion", "id", "()Ljava/lang/String;"
        );

        // getReleaseTarget has no record accessor; name() is the closest.
        transformer.registerMethodRedirect(
            "net/minecraft/WorldVersion", "getReleaseTarget", "()Ljava/lang/String;",
            "net/minecraft/WorldVersion", "name", "()Ljava/lang/String;"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/WorldVersion", "getBuildTime", "()Ljava/util/Date;",
            "net/minecraft/WorldVersion", "buildTime", "()Ljava/util/Date;"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/WorldVersion", "isStable", "()Z",
            "net/minecraft/WorldVersion", "stable", "()Z"
        );

        // getWorldVersion() -> dataVersion() changes return type (int -> DataVersion),
        // so it can't be a plain redirect; callers need a polyfill.

        // new Identifier(...) -> Identifier.of(...)

        transformer.registerConstructorRedirect(
            "net/minecraft/util/Identifier", "(Ljava/lang/String;Ljava/lang/String;)V",
            "com/retromod/shim/fabric/embedded/IdentifierShim", "of", 
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;"
        );
        
        transformer.registerConstructorRedirect(
            "net/minecraft/util/Identifier", "(Ljava/lang/String;)V",
            "com/retromod/shim/fabric/embedded/IdentifierShim", "of",
            "(Ljava/lang/String;)Ljava/lang/Object;"
        );
        
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/item/v1/FabricItem", "getAttributeModifiers",
            "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/EquipmentSlot;)Lcom/google/common/collect/Multimap;",
            "com/retromod/shim/fabric/embedded/FabricItemShim", "getAttributeModifiers",
            "(Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/EquipmentSlot;)Lcom/google/common/collect/Multimap;"
        );
        
        // FabricDimensions removed: teleport -> Entity.teleportTo
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/dimension/v1/FabricDimensions", "teleport",
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/server/world/ServerWorld;Lnet/fabricmc/fabric/api/dimension/v1/TeleportTarget;)Lnet/minecraft/entity/Entity;",
            "com/retromod/shim/fabric/embedded/FabricDimensionsShim", "teleport",
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/server/world/ServerWorld;Ljava/lang/Object;)Lnet/minecraft/entity/Entity;"
        );
        
        // FabricBlockEntityType.Builder removed
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/object/builder/v1/block/entity/FabricBlockEntityType$Builder",
            "com/retromod/shim/fabric/embedded/FabricBlockEntityTypeBuilderShim"
        );

        // fabric-renderer-registries-v1 removed
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendereregistry/v1/BlockEntityRendererRegistry",
            "com/retromod/shim/fabric/embedded/BlockEntityRendererRegistryShim"
        );

        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendereregistry/v1/EntityRendererRegistry",
            "com/retromod/shim/fabric/embedded/EntityRendererRegistryShim"
        );

        // ResourceCondition.test gained a registries argument
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/resource/conditions/v1/ResourceCondition", "test",
            "(Lnet/minecraft/registry/DynamicRegistryManager;)Z",
            "com/retromod/shim/fabric/embedded/ResourceConditionShim", "test",
            "(Ljava/lang/Object;Lnet/minecraft/registry/DynamicRegistryManager;)Z"
        );

        // InteractionResultHolder<T> and ItemInteractionResult folded into InteractionResult.
        transformer.registerClassRedirect(
            "net/minecraft/world/InteractionResultHolder",
            "net/minecraft/world/InteractionResult"
        );

        transformer.registerClassRedirect(
            "net/minecraft/world/ItemInteractionResult",
            "net/minecraft/world/InteractionResult"
        );

        // The block adapter matches Mojang names, which a Fabric mod only has after the 26.x remap.
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.LegacyBlockApiSynthetic.register(transformer);
            com.retromod.shim.common.LegacyBlockMethodBridge.register(transformer);
            com.retromod.shim.common.LegacyChestBlockBridge.register(transformer);
            com.retromod.shim.common.LegacyFoodConsumableBridge.register(transformer);
            // 1.21.2 turned the eat and drink sounds into holders. NeoForge and Forge register
            // this bridge from their 1.20.5 shims; Fabric has no earlier Mojang-named shim for it.
            com.retromod.shim.common.LegacyHolderConstantBridge.register(transformer);
            com.retromod.shim.common.LegacyItemConstructorBridge.register(transformer);
            // Entity, goal and level members 1.20.5 and 1.21.2 changed. NeoForge and Forge
            // register the 1.20.5 bridge from their 1.20.5 shims, which Fabric lacks.
            com.retromod.shim.common.Legacy1205MemberBridge.register(transformer);
            com.retromod.shim.common.Legacy1212MemberBridge.register(transformer);
            // 1.20.5 made the Attributes constants holders, which NeoForge and Forge also bridge
            // from their 1.20.5 shims. Without it a mob's createAttributes() failed with
            // NoSuchFieldError: Attributes.MAX_HEALTH in the mod's entry point.
            com.retromod.shim.common.LegacyAttributeApiBridge.register(transformer);
            // 1.20.5 turned the SpawnPlacements.Type enum into SpawnPlacementTypes, bridged the same
            // way on NeoForge and Forge. The entry point otherwise stopped at a biome spawn rule.
            com.retromod.shim.common.LegacySpawnPlacementTypeBridge.register(transformer);
            // 1.20.2 split Advancement into its data and an AdvancementHolder.
            com.retromod.shim.common.LegacyAdvancementBridge.register(transformer);
            // Block constructors 1.20.3 reshaped. Pre-26.1 Fabric has the intermediary
            // Pre1_20_3BlockConstructorBridge instead.
            com.retromod.shim.common.LegacyBlockConstructorBridge.register(transformer);
            com.retromod.shim.common.LegacyProtectedBlockCallBridge.register(transformer);
        } else {
            // Before 26.x these two calls keep intermediary names, so they are bridged here. On 26.x
            // the Mojang-named bridges above own them.
            registerIntermediaryMemberChanges(transformer);
        }

        // Registry getter renames
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

        // 1.21.2 dropped the GENERIC_ prefix on the Attributes constants.
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
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_ATTACK_SPEED",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "ATTACK_SPEED",
            "Lnet/minecraft/core/Holder;"
        );

        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_ARMOR",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "ARMOR",
            "Lnet/minecraft/core/Holder;"
        );

        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_ARMOR_TOUGHNESS",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "ARMOR_TOUGHNESS",
            "Lnet/minecraft/core/Holder;"
        );

        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_KNOCKBACK_RESISTANCE",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "KNOCKBACK_RESISTANCE",
            "Lnet/minecraft/core/Holder;"
        );

        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_ATTACK_KNOCKBACK",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "ATTACK_KNOCKBACK",
            "Lnet/minecraft/core/Holder;"
        );

        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_LUCK",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "LUCK",
            "Lnet/minecraft/core/Holder;"
        );

        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/ai/attributes/Attributes", "GENERIC_MAX_ABSORPTION",
            "Lnet/minecraft/core/Holder;",
            "net/minecraft/world/entity/ai/attributes/Attributes", "MAX_ABSORPTION",
            "Lnet/minecraft/core/Holder;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.fabric.embedded.IdentifierShim",
            "com.retromod.shim.fabric.embedded.FabricItemShim",
            "com.retromod.shim.fabric.embedded.FabricDimensionsShim",
            "com.retromod.shim.fabric.embedded.FabricBlockEntityTypeBuilderShim",
            "com.retromod.shim.fabric.embedded.BlockEntityRendererRegistryShim",
            "com.retromod.shim.fabric.embedded.EntityRendererRegistryShim",
            "com.retromod.shim.fabric.embedded.ResourceConditionShim"
        };
    }
}
