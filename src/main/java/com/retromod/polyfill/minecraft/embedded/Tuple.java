/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Polyfill for net.minecraft.util.Tuple, removed in MC 26.2.
 * A trivial mutable pair: (A, B) with get/set on each side. Mods that built
 * their own Tuples and passed them around internally keep working; nothing in
 * 26.2 MC takes a Tuple (the class is gone), so there's no real-vs-polyfill
 * boundary to cross.
 *
 * Mods referencing net/minecraft/util/Tuple (or intermediary class_3545) are
 * redirected here by Minecraft26_2RemovedPolyfill, gated to 26.2+ hosts (Tuple
 * still exists on 26.1 and earlier, so the redirect must not fire there).
 */
package com.retromod.polyfill.minecraft.embedded;

/**
 * Drop-in replacement for the removed {@code net.minecraft.util.Tuple}.
 *
 * @param <A> first element type
 * @param <B> second element type
 */
public class Tuple<A, B> {

    private A a;
    private B b;

    public Tuple(A a, B b) {
        this.a = a;
        this.b = b;
    }

    public A getA() {
        return this.a;
    }

    public void setA(A a) {
        this.a = a;
    }

    public B getB() {
        return this.b;
    }

    public void setB(B b) {
        this.b = b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tuple)) return false;
        Tuple<?, ?> other = (Tuple<?, ?>) o;
        return java.util.Objects.equals(a, other.a) && java.util.Objects.equals(b, other.b);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(a, b);
    }

    @Override
    public String toString() {
        return "Tuple{a=" + a + ", b=" + b + "}";
    }
}
