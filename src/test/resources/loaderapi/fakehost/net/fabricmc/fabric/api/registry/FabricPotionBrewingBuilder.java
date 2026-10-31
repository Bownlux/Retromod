package net.fabricmc.fabric.api.registry;

import fixture.Recorder;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.Ingredient;

/** The 26.1 and 26.2 brewing builder; its methods record what they were given. */
public interface FabricPotionBrewingBuilder {
    Event<BuildCallback> BUILD = EventFactory.createArrayBacked(BuildCallback.class,
            listeners -> builder -> { for (BuildCallback listener : listeners) listener.build(builder); });

    default void registerItemRecipe(Item input, Ingredient ingredient, Item output) {
        Recorder.EVENTS.add("item recipe " + input + " -> " + output);
    }

    default void registerPotionRecipe(Holder<Potion> input, Ingredient ingredient, Holder<Potion> output) {
        Recorder.EVENTS.add("potion recipe " + input.value() + " -> " + output.value());
    }

    default void registerRecipes(Ingredient ingredient, Holder<Potion> potion) {
        Recorder.EVENTS.add("recipes -> " + potion.value());
    }

    default net.minecraft.world.flag.FeatureFlagSet getEnabledFeatures() {
        return new net.minecraft.world.flag.FeatureFlagSet();
    }

    interface BuildCallback {
        void build(PotionBrewing.Builder builder);
    }
}
