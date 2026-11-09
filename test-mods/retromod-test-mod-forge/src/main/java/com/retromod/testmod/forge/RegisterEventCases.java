/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.retromod.testlib.forge.RetromodTestLibForge;
import java.util.List;
import java.util.UUID;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.RecipeHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls the known-good Forge 1.20.1 set makes while its registries fill, each of which stopped
 * Farmer's Delight, Lootr or Sophisticated Backpacks in RegisterEvent on Forge 26.2. Blocks cannot
 * be built outside registration on newer hosts, so those cases only prove the constructor links.
 */
final class RegisterEventCases {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");

    private RegisterEventCases() {}

    /** Farmer's Delight's rope fence gate passes its own open and close sounds. */
    static final class RopeGate extends FenceGateBlock {
        RopeGate(BlockBehaviour.Properties properties) {
            super(properties, SoundEvents.FENCE_GATE_OPEN, SoundEvents.FENCE_GATE_CLOSE);
        }
    }

    static void buildSoundGate() {
        new RopeGate(BlockBehaviour.Properties.of());
    }

    /** Farmer's Delight's wild crops extend the flower with vanilla's effect constructor. */
    static final class WildCrop extends FlowerBlock {
        WildCrop(BlockBehaviour.Properties properties) {
            super(MobEffects.HUNGER, 6, properties);
        }
    }

    static void buildWildCrop() {
        new WildCrop(BlockBehaviour.Properties.of());
    }

    /** Whether the wild crop gets past reading its effect: anything Retromod's flower helper threw fails it. */
    static boolean wildCropReadsItsEffect() {
        try {
            buildWildCrop();
            return true;
        } catch (Throwable thrown) {
            for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
                StackTraceElement[] trace = cause.getStackTrace();
                if (trace.length > 0 && trace[0].getClassName().endsWith(".LegacyFlowerEffects")) {
                    LOG.warn("[Retromod-Test-Forge] the flower's effect was not read: {}", cause.toString());
                    return false;
                }
            }
            return true;
        }
    }

    /** The cooking pot's item color, packed before its item registers. */
    static boolean packedColor() {
        return Mth.color(1.0F, 0.5F, 0.0F) == 0xFF7F00;
    }

    /**
     * A patch feature's configuration, read through its codec and built by hand. 26.3 folded every
     * configuration into its feature, so there the old configuration has nothing to implement.
     */
    static boolean randomPatchConfiguration() {
        if (!hostHasConfiguredFeatures()) {
            LOG.info("[Retromod-Test-Forge] RandomPatchConfiguration: skipped: this host has no FeatureConfiguration,"
                    + " so old patch features cannot load here");
            return true;
        }
        RandomPatchConfiguration config = new RandomPatchConfiguration(32, 2, 3, null);
        return RandomPatchConfiguration.CODEC != null && config.tries() == 32 && config.xzSpread() == 2
                && config.ySpread() == 3;
    }

    private static boolean hostHasConfiguredFeatures() {
        try {
            Class.forName("net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration", false,
                    RegisterEventCases.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** The cooking pot implements the 1.20.1 last-recipe interface; its defaults still run. */
    static final class Pot implements RecipeHolder {
        private Recipe<?> used;

        @Override
        public void setRecipeUsed(Recipe<?> recipe) {
            used = recipe;
        }

        @Override
        public Recipe<?> getRecipeUsed() {
            return used;
        }
    }

    static boolean recipeHolder() {
        RecipeHolder pot = new Pot();
        pot.setRecipeUsed(null);
        pot.awardUsedRecipes(null, List.of());
        return pot.getRecipeUsed() == null;
    }

    /** Farmer's Delight's foods name their effects through a supplier. */
    static boolean suppliedFoodEffect() {
        FoodProperties food = new FoodProperties.Builder().nutrition(2)
                .effect(() -> new MobEffectInstance(MobEffects.REGENERATION, 20, 0), 1.0F).build();
        return food != null;
    }

    /** Lootr's minecart sets a client factory whose lambda names Forge's spawn packet. */
    static void clientFactory() {
        EntityType.Builder.of((type, level) -> null, MobCategory.MISC)
                .setCustomClientFactory((spawn, level) -> null);
    }

    /** Farmer's Delight's skillet reads Item's base attack UUID through itself. */
    static final class Skillet extends Item {
        Skillet(Item.Properties properties) {
            super(properties);
        }

        static UUID damageUuid() {
            return BASE_ATTACK_DAMAGE_UUID;
        }
    }

    static boolean baseAttackUuid() {
        return UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF").equals(Skillet.damageUuid());
    }

    /**
     * Picks between two of the library's result kinds, as Sophisticated Backpacks does with
     * Sophisticated Core's. The color call makes Retromod rewrite this class, so its frames are
     * computed again and the join must come from the library jar.
     */
    static RetromodTestLibForge.LibResult pick(boolean success) {
        RetromodTestLibForge.LibResult result = success
                ? new RetromodTestLibForge.LibSuccess() : new RetromodTestLibForge.LibFailure();
        if (Mth.color(0.0F, 0.0F, 0.0F) != 0) return null;
        return result;
    }

    static boolean libraryJoin() {
        return pick(true) instanceof RetromodTestLibForge.LibSuccess
                && pick(false) instanceof RetromodTestLibForge.LibFailure;
    }
}
