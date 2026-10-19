package net.minecraft.core;

import net.minecraft.resources.Identifier;

public interface Registry<T> {
    T getValue(Identifier id);
}
