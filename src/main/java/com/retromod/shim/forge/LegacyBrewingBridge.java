/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyBrewingStore;
import com.retromod.shim.forge.embedded.LegacyDataPackSource;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;

import static org.objectweb.asm.Opcodes.*;

/**
 * Brewing recipes that Forge and NeoForge mods register in code, on NeoForge 26.3 and newer.
 *
 * <p>26.3 made brewing recipe files and NeoForge removed {@code IBrewingRecipe} and
 * {@code BrewingRecipeRegistry}, so a mod whose recipe class implemented the interface failed to
 * load, which took the whole mod with it. The interface comes back as a generated one with the
 * old methods, and {@code addRecipe} records the recipe; once the server has started the recorded
 * recipes are run over every potion and item and written as brewing recipe files.
 */
public final class LegacyBrewingBridge {

    static final String RECIPE = "com/retromod/generated/LegacyBrewingRecipe";
    static final String STORE = "com/retromod/shim/forge/embedded/LegacyBrewingStore";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String OBJECT = "Ljava/lang/Object;";

    private LegacyBrewingBridge() {}

    static boolean supportsHost(String host) {
        return RetromodVersion.compareMcVersions(host, "26.3") >= 0 && McReflect.isNeoForge()
                && !ClassResourceInspector.exists("net/neoforged/neoforge/common/brewing/IBrewingRecipe");
    }

    public static void register(RetromodTransformer transformer) {
        if (!supportsHost(RetromodVersion.TARGET_MC_VERSION)) return;
        registerRedirects(transformer);
    }

    static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, STORE, LegacyBrewingStore.class);
        SyntheticEmbedder.registerClassResource(transformer, "com/retromod/shim/forge/embedded/LegacyDataPackSource",
                LegacyDataPackSource.class);
        transformer.registerSyntheticClass(RECIPE, generateRecipe());
        for (String loader : new String[] {"net/minecraftforge/common/brewing/", "net/neoforged/neoforge/common/brewing/"}) {
            transformer.registerClassRedirect(loader + "IBrewingRecipe", RECIPE);
            String registry = loader + "BrewingRecipeRegistry";
            transformer.registerMethodRedirect(registry, "addRecipe", "(L" + RECIPE + ";)Z",
                    STORE, "addRecipe", "(" + OBJECT + ")Z");
            transformer.registerMethodRedirect(registry, "addRecipe",
                    "(Lnet/minecraft/world/item/crafting/Ingredient;Lnet/minecraft/world/item/crafting/Ingredient;" + STACK + ")Z",
                    STORE, "addRecipe", "(" + OBJECT + OBJECT + OBJECT + ")Z");
        }
    }

    /** {@code IBrewingRecipe}: is this the input, is this the reagent, and what comes out. */
    static byte[] generateRecipe() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, RECIPE, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "isInput", "(" + STACK + ")Z", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "isIngredient", "(" + STACK + ")Z", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getOutput", "(" + STACK + STACK + ")" + STACK, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
