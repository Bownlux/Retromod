/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** A worldgen type 26.3 removed, rebuilt as the map codec 26.3 registers in its place; it reads and writes with the mod's codec. */
public abstract class LegacyTypeCodec<T> extends MapCodec<T> {

    private final Supplier<?> source;
    private volatile MapCodec<Object> delegate;

    // Each removed type is its own subclass, so a check against one type never matches another's codec.
    protected LegacyTypeCodec(Supplier<?> source) {
        this.source = source;
    }

    /** {@code type.codec()} on an old type: the type is its own codec now. */
    public static MapCodec<?> codecOf(Object type) {
        return (MapCodec<?>) type;
    }

    /** A codec, or before 1.20.5 a plain codec of a map, as a map codec. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static MapCodec<Object> asMapCodec(Object codec) {
        if (codec instanceof MapCodec<?> map) return (MapCodec) map;
        if (codec instanceof MapCodec.MapCodecCodec<?> record) return (MapCodec) record.codec();
        if (codec instanceof Codec<?> plain) return (MapCodec) MapCodec.assumeMapUnsafe(plain);
        throw new IllegalStateException("A worldgen type gave " + codec + " where a codec belongs, "
                + "so what it describes cannot be read or written.");
    }

    // The mod's codec field may be set after its type was made, so it is read on first use.
    private MapCodec<Object> delegate() {
        MapCodec<Object> codec = delegate;
        if (codec == null) {
            codec = asMapCodec(source.get());
            delegate = codec;
        }
        return codec;
    }

    @Override
    public <O> Stream<O> keys(DynamicOps<O> ops) {
        return delegate().keys(ops);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
        return (DataResult) delegate().decode(ops, input);
    }

    @Override
    public <O> RecordBuilder<O> encode(T input, DynamicOps<O> ops, RecordBuilder<O> prefix) {
        return delegate().encode(input, ops, prefix);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + source + "]";
    }
}
