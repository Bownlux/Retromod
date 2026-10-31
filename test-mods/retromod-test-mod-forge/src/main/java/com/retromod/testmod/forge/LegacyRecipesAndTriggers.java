/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;
import java.util.Optional;

/**
 * #372: Fossils and Archaeology 9.3.4.0 reads its machine recipes with the 1.20.1 serializer API,
 * looks them up with containers, and reads its advancement triggers' item and entity predicates
 * from JSON. Recipes and advancements load with the server's data, so the results are in the log
 * rather than the summary.
 */
final class LegacyRecipesAndTriggers {

    private static final String MOD_ID = "retromod_test_mod_forge";
    static final ResourceLocation EGG = new ResourceLocation(MOD_ID, "legacy_egg");

    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD_ID);
    private static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, MOD_ID);
    static final RegistryObject<EggSerializer> SERIALIZER = SERIALIZERS.register("legacy_egg", EggSerializer::new);
    static final RegistryObject<RecipeType<EggRecipe>> TYPE = TYPES.register("legacy_egg", () -> RecipeType.simple(EGG));

    // How many advancement criteria the trigger read, each with its item predicate.
    static int triggersRead;

    private LegacyRecipesAndTriggers() {}

    static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
        TYPES.register(modBus);
        CriteriaTriggers.register(new EggTrigger());
    }

    /** The data file's recipe: found by type and by container, with its id, result, and network form. */
    static String recipesLoad(MinecraftServer server) {
        RecipeManager recipes = server.getRecipeManager();
        List<EggRecipe> all = recipes.getAllRecipesFor(TYPE.get());
        if (all.size() != 1) return "fail: " + all.size() + " recipes of the type";
        SimpleContainer eggs = new SimpleContainer(new ItemStack(Items.EGG));
        Optional<EggRecipe> found = recipes.getRecipeFor(TYPE.get(), eggs, server.overworld());
        if (found.isEmpty()) return "fail: an egg matched no recipe";
        Recipe<?> recipe = found.get();
        if (!EGG.equals(recipe.getId())) return "fail: the recipe's id is " + recipe.getId();
        ItemStack result = found.get().assemble(eggs, server.registryAccess());
        if (!result.is(Items.SLIME_BALL) || result.getCount() != 2) return "fail: it made " + result;
        // The form the server sends players, read back.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SERIALIZER.get().toNetwork(buffer, found.get());
        EggRecipe copy = SERIALIZER.get().fromNetwork(EGG, buffer);
        return copy.result.is(Items.SLIME_BALL) && copy.input.test(new ItemStack(Items.EGG))
                ? "success" : "fail: the network copy differs";
    }

    static String triggerRead() {
        return triggersRead > 0 ? "success" : "fail: the advancement's criterion was not read";
    }

    static final class EggRecipe implements Recipe<Container> {
        private final ResourceLocation id;
        final Ingredient input;
        final ItemStack result;

        EggRecipe(ResourceLocation id, Ingredient input, ItemStack result) {
            this.id = id;
            this.input = input;
            this.result = result;
        }

        @Override public boolean matches(Container container, Level level) { return input.test(container.getItem(0)); }
        @Override public ItemStack assemble(Container container, RegistryAccess access) { return result.copy(); }
        @Override public boolean canCraftInDimensions(int width, int height) { return true; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return result; }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }
        @Override public RecipeType<?> getType() { return TYPE.get(); }
    }

    static final class EggSerializer implements RecipeSerializer<EggRecipe> {
        @Override
        public EggRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new EggRecipe(id, Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "input")),
                    ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result")));
        }

        @Override
        public EggRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new EggRecipe(id, Ingredient.fromNetwork(buffer), buffer.readItem());
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, EggRecipe recipe) {
            recipe.input.toNetwork(buffer);
            buffer.writeItem(recipe.result);
        }
    }

    /** A trigger whose criteria name a player by loot conditions and an item by id, as Fossils' do. */
    static final class EggTrigger extends SimpleCriterionTrigger<EggTrigger.Instance> {
        @Override
        public ResourceLocation getId() {
            return EGG;
        }

        @Override
        protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
            ItemPredicate item = ItemPredicate.fromJson(json.get("item"));
            triggersRead++;
            return new Instance(player, item);
        }

        static final class Instance extends AbstractCriterionTriggerInstance {
            final ItemPredicate item;

            Instance(ContextAwarePredicate player, ItemPredicate item) {
                super(EGG, player);
                this.item = item;
            }
        }
    }
}
