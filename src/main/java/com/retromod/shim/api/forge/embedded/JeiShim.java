package com.retromod.shim.api.forge.embedded;
import com.retromod.shim.api.common.embedded.ApiShimWarnings;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.Collection;
public class JeiShim {
    public static Object getItemType() {
        try { return Class.forName("mezz.jei.api.ingredients.VanillaTypes").getField("ITEM_STACK").get(null);
        } catch (Exception e) {
            try { return Class.forName("mezz.jei.api.constants.VanillaTypes").getField("ITEM_STACK").get(null);
            } catch (Exception e2) {
                warn("getItemType", null, "it returned null", e2);
                return null;
            }
        }
    }
    public static Object getFluidType() {
        try { return Class.forName("mezz.jei.api.ingredients.VanillaTypes").getField("FLUID_STACK").get(null);
        } catch (Exception e) {
            try { return Class.forName("mezz.jei.api.neoforge.NeoForgeTypes").getField("FLUID_STACK").get(null);
            } catch (Exception e2) {
                warn("getFluidType", null, "it returned null", e2);
                return null;
            }
        }
    }
    public static Object getCategoryUid(Object category) {
        try {
            Method m = LegacyPublicMethods.method(category.getClass(), "getRecipeType");
            Object recipeType = m.invoke(category);
            return LegacyPublicMethods.method(recipeType.getClass(), "getUid").invoke(recipeType);
        } catch (Exception e) {
            try { return LegacyPublicMethods.method(category.getClass(), "getUid").invoke(category);
            } catch (Exception e2) {
                warn("getCategoryUid", category, "it returned null", e2);
                return null;
            }
        }
    }
    public static Class<?> getRecipeClass(Object category) {
        try {
            Object recipeType = LegacyPublicMethods.method(category.getClass(), "getRecipeType").invoke(category);
            return (Class<?>) LegacyPublicMethods.method(recipeType.getClass(), "getRecipeClass").invoke(recipeType);
        } catch (Exception e) {
            warn("getRecipeClass", category, "it returned Object", e);
            return Object.class;
        }
    }
    public static void addRecipesLegacy(Object registration, Collection<?> recipes, Object uid) {
        try {
            for (Method m : LegacyPublicMethods.methods(registration.getClass())) {
                if (m.getName().equals("addRecipes") && m.getParameterCount() == 2) {
                    m.invoke(registration, createRecipeType(uid), recipes);
                    return;
                }
            }
            warn("addRecipes", registration, "the recipes were not added", null);
        } catch (Exception e) { warn("addRecipes", registration, "the recipes were not added", e); }
    }
    private static Object createRecipeType(Object uid) {
        try {
            Class<?> recipeType = Class.forName("mezz.jei.api.recipe.RecipeType");
            Method create = recipeType.getMethod("create", Class.forName("net.minecraft.resources.ResourceLocation"), Class.class);
            return create.invoke(null, uid, Object.class);
        } catch (Exception e) {
            warn("createRecipeType", uid, "it returned null", e);
            return null;
        }
    }
    public static void drawLegacy(Object category, Object recipe, Object poseStack, double mouseX, double mouseY) {
        try {
            for (Method m : LegacyPublicMethods.methods(category.getClass())) {
                if (m.getName().equals("draw") && m.getParameterCount() >= 4) {
                    m.invoke(category, recipe, null, poseStack, mouseX, mouseY);
                    return;
                }
            }
            warn("draw", category, "it was not drawn", null);
        } catch (Exception e) { warn("draw", category, "it was not drawn", e); }
    }
    public static Object getItemStacks(Object layout) {
        try { return LegacyPublicMethods.method(layout.getClass(), "getItemStacks").invoke(layout);
        } catch (Exception e) {
            try { return LegacyPublicMethods.method(layout.getClass(), "getRecipeSlotsView").invoke(layout);
            } catch (Exception e2) {
                warn("getItemStacks", layout, "it returned null", e2);
                return null;
            }
        }
    }
    public static Object getFocusValue(Object focus) {
        try {
            Object typed = LegacyPublicMethods.method(focus.getClass(), "getTypedValue").invoke(focus);
            return LegacyPublicMethods.method(typed.getClass(), "getIngredient").invoke(typed);
        } catch (Exception e) {
            try { return LegacyPublicMethods.method(focus.getClass(), "getValue").invoke(focus);
            } catch (Exception e2) {
                warn("getFocusValue", focus, "it returned null", e2);
                return null;
            }
        }
    }
    public static Object getFocusMode(Object focus) {
        try { return LegacyPublicMethods.method(focus.getClass(), "getRole").invoke(focus);
        } catch (Exception e) {
            try { return LegacyPublicMethods.method(focus.getClass(), "getMode").invoke(focus);
            } catch (Exception e2) {
                warn("getFocusMode", focus, "it returned null", e2);
                return null;
            }
        }
    }
    private static void warn(String call, Object receiver, String effect, Throwable failure) {
        ApiShimWarnings.warnOnce("JEI", call, receiver, effect, failure);
    }
}
