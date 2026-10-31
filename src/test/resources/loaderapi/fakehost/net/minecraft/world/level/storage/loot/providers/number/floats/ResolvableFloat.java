package net.minecraft.world.level.storage.loot.providers.number.floats;

import net.minecraft.resources.ResourceKey;

public interface ResolvableFloat {
    static ResolvableFloat fromKey(ResourceKey<?> key) {
        return new ResolvableFloat() {
            @Override public String toString() { return "reference " + key; }
        };
    }
}
