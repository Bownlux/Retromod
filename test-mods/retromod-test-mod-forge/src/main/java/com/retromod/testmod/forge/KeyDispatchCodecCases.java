/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;

import java.util.function.BiConsumer;

/** The KeyDispatchDataCodec calls TerraBlender, Lithostitched and Tectonic make while they register worldgen types. */
final class KeyDispatchCodecCases {

    private record Size(int value) {}

    private static final Codec<Size> SIZE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("size").forGetter(Size::value)).apply(instance, Size::new));

    private KeyDispatchCodecCases() {}

    /** of(Codec) and the record constructor build, and codec() reads the data the record codec reads. */
    static boolean oldFactoriesParse() {
        KeyDispatchDataCodec<Size> made = KeyDispatchDataCodec.of(SIZE);
        KeyDispatchDataCodec<Size> constructed = new KeyDispatchDataCodec<>(SIZE);
        JsonObject json = new JsonObject();
        json.add("size", new JsonPrimitive(3));
        return parsesThree(made.codec(), json) && parsesThree(constructed.codec(), json);
    }

    /** codec() handed to a registration is the value the dispatch registry holds, so the registry finds it by identity. */
    static boolean registrationGetsTheDispatchCodec() {
        KeyDispatchDataCodec<Size> made = KeyDispatchDataCodec.of(SIZE);
        Object[] registered = new Object[1];
        BiConsumer<String, Object> registry = (id, value) -> registered[0] = value;
        registry.accept("retromod_test_mod_forge:size", made.codec());
        // Up to 1.20.4 codec() still returns the record's codec. From 1.20.5 the registry holds
        // the map codec inside it, and codec() anywhere else builds a new Codec around that.
        Object expected = made.codec() == SIZE ? SIZE : ((MapCodec.MapCodecCodec<Size>) SIZE).codec();
        return registered[0] == expected;
    }

    /** of(Codec.unit(x)) reads data with no fields, as the 1.20.1 dispatch read Lithostitched's markers. */
    static boolean unitCodecParsesWithoutAValueKey() {
        KeyDispatchDataCodec<Size> marker = KeyDispatchDataCodec.of(Codec.unit(new Size(7)));
        return marker.codec().parse(JsonOps.INSTANCE, new JsonObject()).result()
                .map(size -> size.value() == 7).orElse(false);
    }

    private static boolean parsesThree(Codec<Size> codec, JsonObject json) {
        return codec.parse(JsonOps.INSTANCE, json).result().map(size -> size.value() == 3).orElse(false);
    }
}
