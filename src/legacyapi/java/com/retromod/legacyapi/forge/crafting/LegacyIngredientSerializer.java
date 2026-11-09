/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.crafting;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.ingredients.IIngredientSerializer;

/** Forge 1.20.1's ingredient serializer, which read JSON and a byte buffer, on top of Forge 26.2's codec one. */
public interface LegacyIngredientSerializer<T extends Ingredient> extends IIngredientSerializer<T> {

    /** The old network read an old mod implements. */
    T parse(FriendlyByteBuf buffer);

    /** The old JSON read an old mod implements; it gets the whole ingredient object, its type included. */
    T parse(JsonObject json);

    /** The old network write an old mod implements. */
    void write(FriendlyByteBuf buffer, T ingredient);

    // Forge reads an ingredient's data through this codec, which hands the old JSON read its object.
    @Override
    default MapCodec<T> codec() {
        return new LegacyIngredientCodec<>(this);
    }

    /** Forge 26.2's network read, through the old one. */
    default T read(RegistryFriendlyByteBuf buffer) {
        return parse((FriendlyByteBuf) buffer);
    }

    /** Forge 26.2's network write, through the old one. */
    default void write(RegistryFriendlyByteBuf buffer, T ingredient) {
        write((FriendlyByteBuf) buffer, ingredient);
    }

    /** Forge 26.3's network codec, through the old read and write. */
    default StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return StreamCodec.of((buffer, ingredient) -> write((FriendlyByteBuf) buffer, ingredient),
                buffer -> parse((FriendlyByteBuf) buffer));
    }
}
