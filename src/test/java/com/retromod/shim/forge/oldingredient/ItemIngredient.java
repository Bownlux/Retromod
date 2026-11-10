/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.oldingredient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.retromod.shim.forge.ingredienthost.FriendlyByteBuf;
import com.retromod.shim.forge.ingredienthost.Identifier;
import com.retromod.shim.forge.ingredienthost.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** An old mod's custom ingredient, read from its item field, as resourcefullib and Create write one. */
public class ItemIngredient extends OldAbstractIngredient {
    public static final Serializer FIRST = new Serializer();
    public static final Serializer SECOND = new Serializer();
    public static final Serializer LATE = new Serializer();

    public final String item;
    private final Serializer serializer;

    public ItemIngredient(String item, Serializer serializer) {
        super(Stream.of(new OldValue[0]));
        this.item = item;
        this.serializer = serializer;
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public OldIngredientSerializer<? extends Ingredient> getSerializer() {
        return serializer;
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", String.valueOf(OldCraftingHelper.getID(serializer)));
        json.addProperty("item", item);
        return json;
    }

    /** Registers the first two serializers, as a mod does while it constructs. */
    public static void registerAll() {
        OldCraftingHelper.register(new Identifier("oldmod:first"), FIRST);
        OldCraftingHelper.register(new Identifier("oldmod:second"), SECOND);
    }

    /** Registers one more after Forge registered ingredient serializers, as a setup task would. */
    public static void registerLate() {
        OldCraftingHelper.register(new Identifier("oldmod:late"), LATE);
    }

    public static Identifier idOf(Serializer serializer) {
        return OldCraftingHelper.getID(serializer);
    }

    public static final class Serializer implements OldIngredientSerializer<ItemIngredient> {
        /** The ingredients the old network write was handed. */
        public final List<ItemIngredient> written = new ArrayList<>();

        @Override
        public ItemIngredient parse(FriendlyByteBuf buffer) {
            return new ItemIngredient("from the network", this);
        }

        @Override
        public ItemIngredient parse(JsonObject json) {
            return new ItemIngredient(json.get("item").getAsString(), this);
        }

        @Override
        public void write(FriendlyByteBuf buffer, ItemIngredient ingredient) {
            written.add(ingredient);
        }
    }
}
