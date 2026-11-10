/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

/** Stands in for Forge's registry keys that the ingredient registration reads. */
public final class ForgeRegistries {
    private ForgeRegistries() {}

    public static final class Keys {
        public static final ResourceKey INGREDIENT_SERIALIZERS = new ResourceKey("forge:ingredient_serializer");
        public static final ResourceKey RECIPE_SERIALIZERS = new ResourceKey("minecraft:recipe_serializer");

        private Keys() {}
    }
}
