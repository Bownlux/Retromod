/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Farmer's Delight's cooking serializer reads its result with ItemStack.STRICT_CODEC and its container with
 * OPTIONAL_CODEC, and its constructor reads the result's crafting remainder. 26.3 loads recipes before item
 * components are bound, so the data recipe loads only if those reads can wait for them.
 */
final class RecipeStackCodecCase {

    private static final String MOD_ID = "retromod_test_mod_neoforge";

    static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, MOD_ID);
    static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD_ID);
    static final DeferredHolder<RecipeType<?>, RecipeType<StackRecipe>> TYPE =
            TYPES.register("legacy_stack", () -> RecipeType.simple(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, "legacy_stack")));
    static final DeferredHolder<RecipeSerializer<?>, Serializer> SERIALIZER = SERIALIZERS.register("legacy_stack", Serializer::new);

    // The last recipe the serializer built, kept for the server-started half.
    private static volatile StackRecipe loaded;

    private RecipeStackCodecCase() {}

    static void register(IEventBus modBus) {
        TYPES.register(modBus);
        SERIALIZERS.register(modBus);
    }

    /** The data recipe loaded, and its stacks read the items' bound components once the server runs. */
    static String run() {
        StackRecipe recipe = loaded;
        if (recipe == null) return "fail: the recipe did not load";
        if (recipe.result.getItem() != Items.HONEY_BOTTLE || recipe.result.getCount() != 2) {
            return "fail: the result read as " + recipe.result;
        }
        if (recipe.result.getMaxStackSize() != 16) return "fail: the result's max stack size reads " + recipe.result.getMaxStackSize();
        if (recipe.remainder.getItem() != Items.GLASS_BOTTLE) return "fail: the remainder read as " + recipe.remainder;
        if (!recipe.container.isEmpty()) return "fail: the missing container read as " + recipe.container;
        return ItemStack.isSameItemSameComponents(recipe.result, new ItemStack(Items.HONEY_BOTTLE))
                ? "success" : "fail: the result does not stack with a new honey bottle";
    }

    /** A recipe holding stacks its serializer decoded, and the remainder its constructor read from the result. */
    static final class StackRecipe implements Recipe<SingleRecipeInput> {
        final Ingredient input;
        final ItemStack result;
        final ItemStack container;
        final ItemStack remainder;

        StackRecipe(Ingredient input, ItemStack result, ItemStack container) {
            this.input = input;
            this.result = result;
            this.container = container;
            this.remainder = result.getCraftingRemainingItem();
            loaded = this;
        }

        @Override
        public boolean matches(SingleRecipeInput input, Level level) {
            return this.input.test(input.item());
        }

        @Override
        public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
            return result.copy();
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return true;
        }

        @Override
        public ItemStack getResultItem(HolderLookup.Provider registries) {
            return result;
        }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            return NonNullList.of(Ingredient.EMPTY, input);
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return SERIALIZER.get();
        }

        @Override
        public RecipeType<?> getType() {
            return TYPE.get();
        }
    }

    /** The serializer, with the 1.21.1 stack codecs Farmer's Delight uses. */
    static final class Serializer implements RecipeSerializer<StackRecipe> {
        private static final MapCodec<StackRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(recipe -> recipe.input),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe -> recipe.result),
                ItemStack.OPTIONAL_CODEC.optionalFieldOf("container", ItemStack.EMPTY).forGetter(recipe -> recipe.container)
        ).apply(instance, StackRecipe::new));
        private static final StreamCodec<RegistryFriendlyByteBuf, StackRecipe> STREAM_CODEC = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, recipe -> recipe.input,
                ItemStack.STREAM_CODEC, recipe -> recipe.result,
                ItemStack.OPTIONAL_STREAM_CODEC, recipe -> recipe.container,
                StackRecipe::new);

        @Override
        public MapCodec<StackRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, StackRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
