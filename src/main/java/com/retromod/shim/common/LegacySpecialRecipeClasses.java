/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;

import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/** Points special recipe classes that newer hosts replaced at the recipe that does the same job, for mods that test recipes by class. */
public final class LegacySpecialRecipeClasses {

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    static final String NO_RECIPE = "com/retromod/generated/LegacyRemovedRecipe";

    /** Old class to the recipe that took over its job. */
    static final Map<String, String> REPLACEMENTS = Map.of(
            "TippedArrowRecipe", "ImbueRecipe",
            "ArmorDyeRecipe", "DyeRecipe",
            "MapCloningRecipe", "TransmuteRecipe",
            "ShulkerBoxColoring", "TransmuteRecipe");

    private LegacySpecialRecipeClasses() {}

    /** Redirects each removed class whose replacement the host has; a class with none becomes one no recipe is. */
    public static void register(RetromodTransformer t) {
        REPLACEMENTS.forEach((old, replacement) -> {
            if (!ClassResourceInspector.exists(CRAFTING + old) && ClassResourceInspector.exists(CRAFTING + replacement)) {
                t.registerClassRedirect(CRAFTING + old, CRAFTING + replacement);
            }
        });
        // Suspicious stew is crafted by a plain recipe now, so no recipe is the old class.
        String stew = CRAFTING + "SuspiciousStewRecipe";
        if (!ClassResourceInspector.exists(stew) && ClassResourceInspector.exists(CRAFTING + "ImbueRecipe")) {
            t.registerSyntheticClass(NO_RECIPE, emptyClass());
            t.registerClassRedirect(stew, NO_RECIPE);
        }
    }

    static byte[] emptyClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, NO_RECIPE, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
