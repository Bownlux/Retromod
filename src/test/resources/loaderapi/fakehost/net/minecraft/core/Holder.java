package net.minecraft.core;

public interface Holder<T> {
    T value();

    static <T> Holder<T> direct(T value) {
        return () -> value;
    }
}
