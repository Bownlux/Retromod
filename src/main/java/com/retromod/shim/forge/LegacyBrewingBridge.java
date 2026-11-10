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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Brings back the brewing recipe classes and {@code addRecipe} on Forge and NeoForge 26.3, which replaced them with recipe files. */
public final class LegacyBrewingBridge {

    static final String RECIPE = "com/retromod/generated/LegacyBrewingRecipe";
    static final String SIMPLE_RECIPE = "com/retromod/generated/LegacySimpleBrewingRecipe";
    static final String STORE = "com/retromod/shim/forge/embedded/LegacyBrewingStore";
    private static final String STACK_TYPE = "net/minecraft/world/item/ItemStack";
    private static final String INGREDIENT_TYPE = "net/minecraft/world/item/crafting/Ingredient";
    private static final String STACK = "L" + STACK_TYPE + ";";
    private static final String INGREDIENT = "L" + INGREDIENT_TYPE + ";";
    private static final String OBJECT = "Ljava/lang/Object;";

    private LegacyBrewingBridge() {}

    /** The name the old recipe interface takes on NeoForge 26.3, for bridges that hand recipes on. */
    public static String recipeInterface() {
        return RECIPE;
    }

    static boolean supportsHost(String host) {
        return RetromodVersion.compareMcVersions(host, "26.3") >= 0 && McReflect.isNeoForge()
                && !ClassResourceInspector.exists("net/neoforged/neoforge/common/brewing/IBrewingRecipe");
    }

    public static void register(RetromodTransformer transformer) {
        if (!supportsHost(RetromodVersion.TARGET_MC_VERSION)) return;
        registerRedirects(transformer);
    }

    /** Forge 66 removed the recipe classes, the registry and its event when 26.3 made brewing recipe files. */
    public static boolean registerForge(RetromodTransformer transformer) {
        return registerForge(transformer, ClassResourceInspector::read);
    }

    static boolean registerForge(RetromodTransformer transformer, Function<String, ClassNode> host) {
        if (!ForgeHostShape.matches(List.of(), List.of(LegacyBrewingRegistryBridge.FORGE_RECIPE),
                LegacyBrewingRegistryBridge.FIRST_WITHOUT_RECIPE, host)) {
            return false;
        }
        registerRedirects(transformer);
        return true;
    }

    static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, STORE, LegacyBrewingStore.class);
        SyntheticEmbedder.registerClassResource(transformer, "com/retromod/shim/forge/embedded/LegacyDataPackSource",
                LegacyDataPackSource.class);
        transformer.registerSyntheticClass(RECIPE, generateRecipe());
        transformer.registerSyntheticClass(SIMPLE_RECIPE, generateSimpleRecipe());
        for (String loader : new String[] {"net/minecraftforge/common/brewing/", "net/neoforged/neoforge/common/brewing/"}) {
            transformer.registerClassRedirect(loader + "IBrewingRecipe", RECIPE);
            // The plain recipe went too; mods build it and extend it, as Alex's Mobs does.
            transformer.registerClassRedirect(loader + "BrewingRecipe", SIMPLE_RECIPE);
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

    /** {@code BrewingRecipe}: an input and an ingredient that brew a copy of one output. */
    static byte[] generateSimpleRecipe() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SIMPLE_RECIPE, null, "java/lang/Object", new String[] {RECIPE});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "input", INGREDIENT, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "ingredient", INGREDIENT, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "output", STACK, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + INGREDIENT + INGREDIENT + STACK + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        String[][] fields = {{"input", INGREDIENT}, {"ingredient", INGREDIENT}, {"output", STACK}};
        for (int i = 0; i < fields.length; i++) {
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, i + 1);
            init.visitFieldInsn(PUTFIELD, SIMPLE_RECIPE, fields[i][0], fields[i][1]);
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        emitTest(cw, "isInput", "input");
        emitTest(cw, "isIngredient", "ingredient");
        emitGetter(cw, "getInput", "input", INGREDIENT);
        emitGetter(cw, "getIngredient", "ingredient", INGREDIENT);
        emitGetter(cw, "getOutput", "output", STACK);

        // getOutput(input, ingredient) asks the overridable checks, then hands out a copy, as Forge's did.
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getOutput", "(" + STACK + STACK + ")" + STACK, null, null);
        mv.visitCode();
        Label none = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, SIMPLE_RECIPE, "isInput", "(" + STACK + ")Z", false);
        mv.visitJumpInsn(IFEQ, none);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, SIMPLE_RECIPE, "isIngredient", "(" + STACK + ")Z", false);
        mv.visitJumpInsn(IFEQ, none);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, SIMPLE_RECIPE, "getOutput", "()" + STACK, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, STACK_TYPE, "copy", "()" + STACK, false);
        mv.visitInsn(ARETURN);
        mv.visitLabel(none);
        mv.visitFieldInsn(GETSTATIC, STACK_TYPE, "EMPTY", STACK);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitTest(ClassWriter cw, String name, String field) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "(" + STACK + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, SIMPLE_RECIPE, field, INGREDIENT);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, INGREDIENT_TYPE, "test", "(" + STACK + ")Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitGetter(ClassWriter cw, String name, String field, String type) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + type, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, SIMPLE_RECIPE, field, type);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
