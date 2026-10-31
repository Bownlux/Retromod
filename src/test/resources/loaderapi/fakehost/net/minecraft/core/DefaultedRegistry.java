package net.minecraft.core;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultedRegistry<T> implements Registry<T> {
    private final Map<Identifier, T> values = new LinkedHashMap<>();
    public void put(Identifier id, T value) { values.put(id, value); }
    @Override public T getValue(Identifier id) { return values.get(id); }
    @Override public T getValue(net.minecraft.resources.ResourceKey<T> key) { return values.get(key.identifier()); }
    @Override public java.util.Set<Identifier> keySet() { return values.keySet(); }
    @Override public Identifier getKey(T value) {
        for (Map.Entry<Identifier, T> entry : values.entrySet()) if (entry.getValue() == value) return entry.getKey();
        return null;
    }
}
