/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/** Keeps Patchouli's Ingredient-returning smithing accessor calls working in Mojang names. */
public final class PatchouliSmithingAccessorBridge {

    private static final String ACCESSOR = "vazkii/patchouli/mixin/AccessorSmithingTransformRecipe";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";

    private PatchouliSmithingAccessorBridge() {}

    /** Sends the old calls to the Optional accessors the Mixin repair declares, then unwraps them. */
    public static void register(RetromodTransformer transformer) {
        // 1.21.2 made the template and addition optional. The recipes Patchouli pages show carry
        // both, as every smithing transform recipe did when Patchouli was built.
        for (String method : new String[]{"getTemplate", "getAddition"}) {
            transformer.registerReturnUnwrapRedirect(
                    ACCESSOR, method, "()" + INGREDIENT,
                    "retromod$" + method + "Optional", "()Ljava/util/Optional;",
                    "java/util/Optional", "orElseThrow", "()Ljava/lang/Object;", false);
        }
    }
}
