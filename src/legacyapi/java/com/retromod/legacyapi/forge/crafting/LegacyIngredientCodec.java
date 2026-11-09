/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.crafting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.world.item.crafting.Ingredient;

/** The codec Forge reads an old serializer's ingredients with: the old JSON read and the old toJson. */
final class LegacyIngredientCodec<T extends Ingredient> extends MapCodec<T> {

    private static final String TYPE = "type";

    private final LegacyIngredientSerializer<T> serializer;

    LegacyIngredientCodec(LegacyIngredientSerializer<T> serializer) {
        this.serializer = serializer;
    }

    @Override
    public <O> Stream<O> keys(DynamicOps<O> ops) {
        return Stream.empty();
    }

    @Override
    public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
        // Forge's dispatch hands over the whole object, type included, as Forge 1.20.1 passed it to the old read.
        JsonElement json = ops.convertTo(JsonOps.INSTANCE, ops.createMap(input.entries()));
        if (!(json instanceof JsonObject object)) {
            return DataResult.error(() -> "The old ingredient serializer " + name() + " reads a JSON object, not "
                    + json);
        }
        try {
            T ingredient = serializer.parse(object);
            if (ingredient == null) {
                return DataResult.error(() -> "The old ingredient serializer " + name() + " read no ingredient");
            }
            return DataResult.success(ingredient);
        } catch (RuntimeException | LinkageError failure) {
            // A read that throws, also on an API this host removed, becomes a read error rather than an exception.
            LegacyLog.warnOnce("ingredient read " + name(), "Retromod returned a read error for the old ingredient"
                    + " serializer " + name() + ": its JSON read threw " + failure);
            return DataResult.error(() -> "The old ingredient serializer " + name() + " threw " + failure);
        }
    }

    @Override
    public <O> RecordBuilder<O> encode(T ingredient, DynamicOps<O> ops, RecordBuilder<O> prefix) {
        JsonObject json;
        try {
            json = oldJson(ingredient);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return prefix.withErrorsFrom(DataResult.error(() -> "The old toJson of " + ingredient.getClass().getName()
                    + " threw " + failure));
        }
        if (json == null) {
            return prefix.withErrorsFrom(DataResult.error(() -> ingredient.getClass().getName()
                    + " has no old toJson that writes an object"));
        }
        // Forge's dispatch writes the type key itself.
        for (Map.Entry<String, JsonElement> field : json.entrySet()) {
            if (field.getKey().equals(TYPE)) continue;
            prefix.add(field.getKey(), JsonOps.INSTANCE.convertTo(ops, field.getValue()));
        }
        return prefix;
    }

    // Host ingredients have no toJson, so it is looked up on the mod's own class, which may not be public.
    private static JsonObject oldJson(Object ingredient) throws ReflectiveOperationException {
        Method toJson = LegacyPublicMethods.method(ingredient.getClass(), "toJson");
        if (!JsonElement.class.isAssignableFrom(toJson.getReturnType())) return null;
        toJson.setAccessible(true);
        return toJson.invoke(ingredient) instanceof JsonObject object ? object : null;
    }

    private String name() {
        return serializer.getClass().getName();
    }

    @Override
    public String toString() {
        return "LegacyIngredientCodec[" + name() + "]";
    }
}
