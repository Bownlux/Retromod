package net.minecraft.core;

import net.minecraft.resources.Identifier;

public interface Registry<T> {
    T getValue(Identifier id);

    T getValue(net.minecraft.resources.ResourceKey<T> key);

    Identifier getKey(T value);

    java.util.Set<Identifier> keySet();

    default Holder<T> wrapAsHolder(T value) { return Holder.direct(value); }
}
