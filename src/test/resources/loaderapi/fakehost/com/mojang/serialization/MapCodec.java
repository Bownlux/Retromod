package com.mojang.serialization;

public abstract class MapCodec<A> {
    public static <A> MapCodec<A> unit(A value) {
        return new MapCodec<A>() { };
    }
}
