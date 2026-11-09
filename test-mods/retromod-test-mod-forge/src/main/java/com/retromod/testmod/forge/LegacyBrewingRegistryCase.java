/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.brewing.BrewingRecipe;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.20.1's static BrewingRecipeRegistry, which Forge 26.2 replaced with a brewing event. A
 * server builds its brewing as it starts, so the results are in the log rather than the summary.
 */
final class LegacyBrewingRegistryCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static volatile String added = "fail: common setup did not run";

    private LegacyBrewingRegistryCase() {}

    static void register(IEventBus modBus) {
        modBus.addListener(LegacyBrewingRegistryCase::addRecipes);
        MinecraftForge.EVENT_BUS.addListener(LegacyBrewingRegistryCase::check);
    }

    // Alex's Mobs and Cobblemon add their recipes from a deferred common setup task.
    private static void addRecipes(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                boolean first = BrewingRecipeRegistry.addRecipe(Ingredient.of(Items.APPLE), Ingredient.of(Items.DIAMOND),
                        new ItemStack(Items.GOLDEN_APPLE));
                // A later recipe for the same pair, which must lose to the first.
                boolean second = BrewingRecipeRegistry.addRecipe(new BrewingRecipe(Ingredient.of(Items.APPLE),
                        Ingredient.of(Items.DIAMOND), new ItemStack(Items.COAL)));
                added = first && second ? "success" : "fail: addRecipe returned false";
            } catch (Throwable t) {
                added = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        });
    }

    private static void check(ServerStartedEvent event) {
        LOG.info("[Retromod-Test-Forge] BrewingRecipeRegistry.addRecipe at common setup (server started): {}", added);
        log("BrewingRecipeRegistry.getOutput takes the bottle first and the first recipe added wins (server started)", () -> {
            ItemStack apple = new ItemStack(Items.APPLE);
            ItemStack diamond = new ItemStack(Items.DIAMOND);
            return BrewingRecipeRegistry.getOutput(apple, diamond).is(Items.GOLDEN_APPLE)
                    && BrewingRecipeRegistry.getOutput(diamond, apple).isEmpty()
                    && BrewingRecipeRegistry.hasOutput(apple, diamond);
        });
        log("BrewingRecipeRegistry.isValidInput, isValidIngredient and getRecipes see the recipe (server started)",
                () -> BrewingRecipeRegistry.isValidInput(new ItemStack(Items.APPLE))
                        && BrewingRecipeRegistry.isValidIngredient(new ItemStack(Items.DIAMOND))
                        && BrewingRecipeRegistry.getRecipes().stream()
                                .anyMatch(recipe -> recipe instanceof BrewingRecipe brewing
                                        && brewing.getOutput().is(Items.GOLDEN_APPLE)));
    }

    private static void log(String description, Check check) {
        String result;
        try {
            result = check.run() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] {}: {}", description, result);
    }

    @FunctionalInterface
    private interface Check {
        boolean run() throws Throwable;
    }
}
