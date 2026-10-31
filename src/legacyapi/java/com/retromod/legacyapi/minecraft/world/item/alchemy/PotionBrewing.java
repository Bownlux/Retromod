/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item.alchemy;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.predicates.PotionsPredicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.BrewingInput;
import net.minecraft.world.item.crafting.BrewingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PotionIngredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Minecraft's brewing table from before 26.3, read back from the brewing recipes that replaced it. */
public class PotionBrewing {

    public static final int BREWING_TIME_SECONDS = 20;
    public static final PotionBrewing EMPTY = new PotionBrewing(List.of());

    private static volatile Built cache;

    // Public, as NeoForge's access transformer left them, because old mods read them directly.
    public final List<Ingredient> containers = new ArrayList<>();
    public final List<Mix<Potion>> potionMixes = new ArrayList<>();
    public final List<Mix<Item>> containerMixes = new ArrayList<>();
    private final List<BrewingRecipe> recipes;

    private record Built(RecipeMap recipes, PotionBrewing brewing) {}

    /** One old mix: a potion turned into another, or a bottle into another bottle. */
    public record Mix<T>(Holder<T> from, Ingredient ingredient, Holder<T> to) {}

    private PotionBrewing(List<BrewingRecipe> recipes) {
        this.recipes = recipes;
        for (BrewingRecipe recipe : recipes) sort(recipe);
    }

    /** The server's brewing, rebuilt only when a reload replaced its recipes. */
    public static PotionBrewing of(MinecraftServer server) {
        if (server == null) return EMPTY;
        RecipeMap map = server.getRecipeManager().recipeMap();
        Built built = cache;
        if (built != null && built.recipes() == map) return built.brewing();
        List<BrewingRecipe> brewing = new ArrayList<>();
        for (RecipeHolder<BrewingRecipe> holder : map.byType(RecipeType.BREWING)) brewing.add(holder.value());
        PotionBrewing result = new PotionBrewing(List.copyOf(brewing));
        cache = new Built(map, result);
        return result;
    }

    /** A level's brewing; a client level without a local server sees none, since 26.3 keeps recipes on the server. */
    public static PotionBrewing of(Level level) {
        return of(level == null ? null : level.getServer());
    }

    // A recipe that keeps its bottle is a potion mix, one that keeps its potion is a container mix.
    // 26.3 writes each once per bottle or potion, so a mix the lists already hold is skipped.
    private void sort(BrewingRecipe recipe) {
        Ingredient bottle = recipe.getInput().ingredient();
        if (containers.stream().noneMatch(known -> sameItems(known, bottle))) containers.add(bottle);
        Holder<Item> inputItem = onlyItem(bottle);
        Holder<Item> outputItem = recipe.getOutput().typeHolder();
        Holder<Potion> outputPotion = potionOf(recipe.getOutput().create());
        List<Holder<Potion>> inputPotions = potionsOf(recipe.getInput());
        Ingredient reagent = recipe.getReagent().ingredient();
        if (inputItem == null || outputPotion == null || inputPotions.isEmpty()) return;

        if (inputItem.value() == outputItem.value()) {
            for (Holder<Potion> from : inputPotions) addOnce(potionMixes, new Mix<>(from, reagent, outputPotion));
        } else if (inputPotions.size() == 1 && inputPotions.get(0).value() == outputPotion.value()) {
            addOnce(containerMixes, new Mix<>(inputItem, reagent, outputItem));
        }
    }

    private static <T> void addOnce(List<Mix<T>> mixes, Mix<T> mix) {
        for (Mix<T> known : mixes) {
            if (known.from().value() == mix.from().value() && known.to().value() == mix.to().value()
                    && sameItems(known.ingredient(), mix.ingredient())) return;
        }
        mixes.add(mix);
    }

    private static boolean sameItems(Ingredient first, Ingredient second) {
        return itemsOf(first).equals(itemsOf(second));
    }

    private static List<Object> itemsOf(Ingredient ingredient) {
        return ingredient.items().map(Holder::value).collect(Collectors.toList());
    }

    private static Holder<Item> onlyItem(Ingredient ingredient) {
        List<Holder<Item>> items = ingredient.items().limit(2).collect(Collectors.toList());
        return items.size() == 1 ? items.get(0) : null;
    }

    private static List<Holder<Potion>> potionsOf(PotionIngredient input) {
        Optional<HolderSet<Potion>> potions = input.potions().flatMap(PotionsPredicate::potions);
        return potions.map(set -> set.stream().collect(Collectors.toList())).orElse(List.of());
    }

    private static Holder<Potion> potionOf(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents == null ? null : contents.potion().orElse(null);
    }

    public boolean isIngredient(ItemStack stack) {
        for (BrewingRecipe recipe : recipes) {
            if (recipe.getReagent().test(stack)) return true;
        }
        return false;
    }

    /** NeoForge's check for a stack that can sit in a bottle slot. */
    public boolean isInput(ItemStack stack) {
        for (Ingredient container : containers) {
            if (container.test(stack)) return true;
        }
        return false;
    }

    /** NeoForge's own brewing recipes; 26.3 keeps every recipe as data, so the mixes above already list them. */
    public List<Object> getRecipes() {
        return List.of();
    }

    public boolean isContainerIngredient(ItemStack stack) {
        for (Mix<Item> mix : containerMixes) {
            if (mix.ingredient().test(stack)) return true;
        }
        return false;
    }

    public boolean isPotionIngredient(ItemStack stack) {
        for (Mix<Potion> mix : potionMixes) {
            if (mix.ingredient().test(stack)) return true;
        }
        return false;
    }

    public boolean isBrewablePotion(Holder<Potion> potion) {
        for (Mix<Potion> mix : potionMixes) {
            if (mix.to().value() == potion.value()) return true;
        }
        return false;
    }

    public boolean hasMix(ItemStack input, ItemStack reagent) {
        return recipeFor(input, reagent) != null;
    }

    public boolean hasContainerMix(ItemStack input, ItemStack reagent) {
        for (Mix<Item> mix : containerMixes) {
            if (mix.from().value() == input.getItem() && mix.ingredient().test(reagent)) return true;
        }
        return false;
    }

    public boolean hasPotionMix(ItemStack input, ItemStack reagent) {
        Holder<Potion> potion = potionOf(input);
        if (potion == null) return false;
        for (Mix<Potion> mix : potionMixes) {
            if (mix.from().value() == potion.value() && mix.ingredient().test(reagent)) return true;
        }
        return false;
    }

    /** The brewed bottle, or the bottle unchanged when no recipe takes this reagent. */
    public ItemStack mix(ItemStack reagent, ItemStack potion) {
        if (potion.isEmpty()) return potion;
        BrewingRecipe recipe = recipeFor(potion, reagent);
        return recipe == null ? potion : recipe.assemble(new BrewingInput(potion, reagent));
    }

    private BrewingRecipe recipeFor(ItemStack input, ItemStack reagent) {
        BrewingInput brewing = new BrewingInput(input, reagent);
        for (BrewingRecipe recipe : recipes) {
            if (recipe.matches(brewing)) return recipe;
        }
        return null;
    }
}
