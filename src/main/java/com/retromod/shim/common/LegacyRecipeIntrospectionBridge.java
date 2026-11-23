/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyRecipeIntrospection;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps 1.21.1 recipe lookups and recipe questions working on 1.21.2 and newer, which rebuilt them. */
public final class LegacyRecipeIntrospectionBridge {

    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyRecipeIntrospection";
    static final String CALLS = "com/retromod/generated/LegacyRecipeQueries";

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    static final String MANAGER = CRAFTING + "RecipeManager";
    private static final String RECIPE = CRAFTING + "Recipe";
    private static final String HOLDER = CRAFTING + "RecipeHolder";
    private static final String INGREDIENT = CRAFTING + "Ingredient";
    private static final String TYPE = "L" + CRAFTING + "RecipeType;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
    private static final String OBJECT = "Ljava/lang/Object;";

    /** Every recipe class a mod asks for its ingredients or result by name, beside the interface. */
    private static final List<String> RECIPE_OWNERS = List.of(RECIPE, CRAFTING + "CraftingRecipe", CRAFTING + "ShapedRecipe",
            CRAFTING + "ShapelessRecipe", CRAFTING + "SingleItemRecipe", CRAFTING + "AbstractCookingRecipe",
            CRAFTING + "SmeltingRecipe", CRAFTING + "BlastingRecipe", CRAFTING + "SmokingRecipe",
            CRAFTING + "CampfireCookingRecipe", CRAFTING + "StonecutterRecipe", CRAFTING + "SmithingRecipe",
            CRAFTING + "SmithingTransformRecipe", CRAFTING + "SmithingTrimRecipe", CRAFTING + "CustomRecipe");
    private static final List<String> COOKING_OWNERS = List.of(CRAFTING + "AbstractCookingRecipe", CRAFTING + "SmeltingRecipe",
            CRAFTING + "BlastingRecipe", CRAFTING + "SmokingRecipe", CRAFTING + "CampfireCookingRecipe");

    private LegacyRecipeIntrospectionBridge() {}

    /** Registers on a host whose recipe manager no longer lists recipes by type. */
    public static void register(RetromodTransformer t) {
        ClassNode manager = ClassResourceInspector.read(MANAGER);
        boolean rebuilt = manager == null
                ? RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)
                : manager.methods.stream().noneMatch(m -> m.name.equals("getAllRecipesFor"));
        if (!rebuilt) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyRecipeIntrospection.class);
        // From 26.1 a recipe's result item may have no bound components while a mod reads it, as Moonlight's generated pack does.
        boolean earlyStacks = com.retromod.mixin.MixinLazyStaticInitializer.hostBindsComponentsLate(
                RetromodVersion.TARGET_MC_VERSION, t.getFuzzyResolver());
        if (earlyStacks) RebuiltApiClasses.registerTree(t, LegacyEarlyItemStackBridge.HELPER);
        t.registerSyntheticClass(CALLS, generate(earlyStacks));

        String receiver = "L" + MANAGER + ";";
        t.registerMethodRedirect(MANAGER, "getAllRecipesFor", "(" + TYPE + ")Ljava/util/List;",
                CALLS, "allRecipesFor", "(" + receiver + TYPE + ")Ljava/util/List;", true);
        String lookup = "(" + TYPE + "L" + CRAFTING + "RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;";
        t.registerMethodRedirect(MANAGER, "getRecipesFor", lookup, CALLS, "recipesFor", "(" + receiver + lookup.substring(1), true);
        t.registerMethodRedirect(MANAGER, "byKey", "(" + ID + ")Ljava/util/Optional;",
                CALLS, "byKey", "(" + receiver + ID + ")Ljava/util/Optional;", true);
        t.registerMethodRedirect(HOLDER, "id", "()" + ID, CALLS, "holderId", "(L" + HOLDER + ";)" + ID, true);
        for (String owner : RECIPE_OWNERS) {
            t.registerMethodRedirect(owner, "getIngredients", "()Lnet/minecraft/core/NonNullList;",
                    CALLS, "ingredients", "(" + OBJECT + ")Lnet/minecraft/core/NonNullList;", true);
            t.registerMethodRedirect(owner, "getResultItem", "(" + PROVIDER + ")" + STACK,
                    CALLS, "resultItem", "(" + OBJECT + PROVIDER + ")" + STACK, true);
            // Before 1.20.5 the result took the registry access, which is a provider too.
            t.registerMethodRedirect(owner, "getResultItem", "(Lnet/minecraft/core/RegistryAccess;)" + STACK,
                    CALLS, "resultItem", "(" + OBJECT + PROVIDER + ")" + STACK, true);
        }
        for (String owner : COOKING_OWNERS) {
            t.registerMethodRedirect(owner, "getExperience", "()F", owner, "experience", "()F");
            t.registerMethodRedirect(owner, "getCookingTime", "()I", owner, "cookingTime", "()I");
        }
        t.registerMethodRedirect(INGREDIENT, "getItems", "()[" + STACK, CALLS, "items", "(L" + INGREDIENT + ";)[" + STACK, true);
    }

    /** Exact-typed forwarders, so each call site gets back the type it expects. */
    static byte[] generate() {
        return generate(false);
    }

    /** As above; with {@code earlyStacks} a result item whose components are not bound yet is built to wait for them. */
    static byte[] generate(boolean earlyStacks) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        String manager = "L" + MANAGER + ";";
        forward(cw, "allRecipesFor", "(" + manager + TYPE + ")Ljava/util/List;");
        forward(cw, "recipesFor", "(" + manager + TYPE + "L" + CRAFTING + "RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;");
        forward(cw, "byKey", "(" + manager + ID + ")Ljava/util/Optional;");
        forward(cw, "holderId", "(L" + HOLDER + ";)" + ID);
        forward(cw, "ingredients", "(" + OBJECT + ")Lnet/minecraft/core/NonNullList;");
        if (earlyStacks) {
            earlyResultItem(cw);
        } else {
            forward(cw, "resultItem", "(" + OBJECT + PROVIDER + ")" + STACK);
        }
        forward(cw, "items", "(L" + INGREDIENT + ";)[" + STACK);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code resultItem(recipe, registries)}: an early stack for an unbound result item, else the resolved display. */
    private static void earlyResultItem(ClassWriter cw) {
        String holder = "net/minecraft/core/Holder";
        String patch = "net/minecraft/core/component/DataComponentPatch";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "resultItem", "(" + OBJECT + PROVIDER + ")" + STACK, null, null);
        mv.visitCode();
        org.objectweb.asm.Label resolve = new org.objectweb.asm.Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "unboundResult", "(" + OBJECT + ")[" + OBJECT, false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNULL, resolve);
        // {holder, count, patch} from the template, built into a stack that reads the components once they bind.
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, holder);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ICONST_2);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, patch);
        mv.visitMethodInsn(INVOKESTATIC, LegacyEarlyItemStackBridge.HELPER, "of",
                "(L" + holder + ";IL" + patch + ";)" + STACK, false);
        mv.visitInsn(ARETURN);
        mv.visitLabel(resolve);
        mv.visitFrame(F_APPEND, 1, new Object[] {"[Ljava/lang/Object;"}, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "resultItem", "(" + OBJECT + OBJECT + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, STACK.substring(1, STACK.length() - 1));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static R name(args)}: the same-named helper on objects, its result cast to R. */
    private static void forward(ClassWriter cw, String name, String desc) {
        Type[] arguments = Type.getArgumentTypes(desc);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        for (int i = 0; i < arguments.length; i++) mv.visitVarInsn(ALOAD, i);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, name, "(" + OBJECT.repeat(arguments.length) + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, Type.getReturnType(desc).getInternalName());
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
