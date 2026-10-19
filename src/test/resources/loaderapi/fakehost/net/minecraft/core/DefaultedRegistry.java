package net.minecraft.core;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

public class DefaultedRegistry<T> implements Registry<T> {
    private final Map<Identifier, T> values = new HashMap<>();
    public void put(Identifier id, T value) { values.put(id, value); }
    @Override public T getValue(Identifier id) { return values.get(id); }
}
