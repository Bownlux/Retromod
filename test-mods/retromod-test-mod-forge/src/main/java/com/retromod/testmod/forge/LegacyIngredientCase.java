/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.IIngredientSerializer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Forge 1.20.1's custom ingredient serializers, which Forge 26.2 replaced with codecs from its own registry. One
 * registers while the mod constructs and one while recipe serializers register, and a data recipe uses the first.
 * Recipes load with the server's data, so the results are in the log rather than the summary.
 */
final class LegacyIngredientCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String MOD_ID = "retromod_test_mod_forge";
    static final ResourceLocation ID = new ResourceLocation(MOD_ID, "legacy_ingredient");
    static final ResourceLocation EVENT_ID = new ResourceLocation(MOD_ID, "legacy_ingredient_in_event");
    static final Serializer SERIALIZER = new Serializer();
    static final Serializer EVENT_SERIALIZER = new Serializer();
    private static volatile String constructed = "fail: the mod did not construct";
    private static volatile String inEvent = "fail: recipe serializers were not registered";

    private LegacyIngredientCase() {}

    static void register(IEventBus modBus) {
        // KubeJS, Moonlight and resourcefullib register while their mod constructs. A failure is this case's
        // result, so the other cases still run.
        try {
            CraftingHelper.register(ID, SERIALIZER);
            constructed = "success";
        } catch (Throwable t) {
            constructed = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        modBus.addListener(LegacyIngredientCase::registerInEvent);
        MinecraftForge.EVENT_BUS.addListener(LegacyIngredientCase::check);
    }

    // Create and Farmer's Delight register while recipe serializers register.
    private static void registerInEvent(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.RECIPE_SERIALIZERS, helper -> {
            try {
                CraftingHelper.register(EVENT_ID, EVENT_SERIALIZER);
                inEvent = "success";
            } catch (Throwable t) {
                inEvent = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        });
    }

    private static void check(ServerStartedEvent event) {
        LOG.info("[Retromod-Test-Forge] CraftingHelper.register while the mod constructs (server started): {}",
                constructed);
        LOG.info("[Retromod-Test-Forge] CraftingHelper.register while recipe serializers register (server started): {}",
                inEvent);
        log("CraftingHelper.getID names both old ingredient serializers (server started)",
                () -> ID.equals(CraftingHelper.getID(SERIALIZER))
                        && EVENT_ID.equals(CraftingHelper.getID(EVENT_SERIALIZER)));
        log("An old ingredient serializer reads its JSON through the host and writes it back (server started)",
                LegacyIngredientCase::readsAndWrites);
        String recipe;
        try {
            recipe = recipeResult(event.getServer());
        } catch (Throwable t) {
            recipe = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] A data recipe with an old serializer's ingredient loads (server started): {}",
                recipe);
    }

    private static boolean readsAndWrites() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("type", ID.toString());
        json.addProperty("item", "minecraft:stick");
        Ingredient read = read(json);
        if (!(read instanceof LegacyIngredient legacy) || !"minecraft:stick".equals(legacy.item)) return false;
        if (!read.test(new ItemStack(Items.STICK)) || read.test(new ItemStack(Items.DIAMOND))) return false;
        // The type comes back from the host's registry, the rest from the old toJson.
        JsonElement written = write(read);
        return written instanceof JsonObject object && ID.toString().equals(object.get("type").getAsString())
                && "minecraft:stick".equals(object.get("item").getAsString());
    }

    // Forge 26.2 reads ingredients through Ingredient.CODEC, which Forge 1.20.1 did not have. DataResult became an
    // interface after 1.20.1, so its result is read by reflection.
    private static Ingredient read(JsonObject json) throws Exception {
        Codec<Ingredient> codec = hostCodec();
        if (codec == null) return Ingredient.fromJson(json);
        DataResult<Ingredient> result = codec.parse(JsonOps.INSTANCE, json);
        return (Ingredient) resultOf(result).orElse(null);
    }

    private static JsonElement write(Ingredient ingredient) throws Exception {
        Codec<Ingredient> codec = hostCodec();
        if (codec == null) return ingredient.toJson();
        return (JsonElement) resultOf(codec.encodeStart(JsonOps.INSTANCE, ingredient)).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Codec<Ingredient> hostCodec() {
        try {
            return (Codec<Ingredient>) Ingredient.class.getField("CODEC").get(null);
        } catch (ReflectiveOperationException olderHost) {
            return null;
        }
    }

    private static Optional<?> resultOf(DataResult<?> result) throws Exception {
        return (Optional<?>) DataResult.class.getMethod("result").invoke(result);
    }

    /** Whether the data recipe loaded: a 1.20.1 list holds recipes, a 26.x list holds holders named by their key. */
    private static String recipeResult(MinecraftServer server) {
        ResourceLocation recipe = new ResourceLocation(MOD_ID, "legacy_ingredient");
        for (Object entry : server.getRecipeManager().getRecipes()) {
            boolean named = entry instanceof Recipe<?> old ? recipe.equals(old.getId())
                    : String.valueOf(entry).contains(recipe.toString());
            if (named) return "success";
        }
        // From 26.3 a recipe that fails to read stops the world, so Retromod keeps a copied custom-ingredient
        // recipe out.
        ClassLoader loader = LegacyIngredientCase.class.getClassLoader();
        String data = "data/" + MOD_ID + "/";
        if (loader.getResource(data + "recipe/legacy_ingredient.json") == null
                && loader.getResource(data + "recipes/legacy_ingredient.json") != null) {
            return "skipped: this host loads recipes with the registries, so the copied recipe stays out";
        }
        return "fail: the recipe did not load";
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

    /** A custom ingredient that matches one item by id, read from its item field. */
    static final class LegacyIngredient extends AbstractIngredient {
        final String item;

        LegacyIngredient(String item) {
            // resourcefullib hands its base an empty array of the old values.
            super(Stream.of(new Ingredient.Value[0]));
            this.item = item;
        }

        @Override
        public boolean test(ItemStack stack) {
            return stack != null && item.equals(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        }

        @Override
        public boolean isSimple() {
            return false;
        }

        @Override
        public IIngredientSerializer<? extends Ingredient> getSerializer() {
            return SERIALIZER;
        }

        @Override
        public JsonElement toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("type", String.valueOf(CraftingHelper.getID(SERIALIZER)));
            json.addProperty("item", item);
            return json;
        }
    }

    static final class Serializer implements IIngredientSerializer<LegacyIngredient> {
        @Override
        public LegacyIngredient parse(FriendlyByteBuf buffer) {
            return new LegacyIngredient(buffer.readUtf());
        }

        @Override
        public LegacyIngredient parse(JsonObject json) {
            return new LegacyIngredient(json.get("item").getAsString());
        }

        @Override
        public void write(FriendlyByteBuf buffer, LegacyIngredient ingredient) {
            buffer.writeUtf(ingredient.item);
        }
    }
}
