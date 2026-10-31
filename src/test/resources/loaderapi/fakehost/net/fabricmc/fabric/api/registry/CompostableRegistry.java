package net.fabricmc.fabric.api.registry;

import net.minecraft.world.level.ItemLike;

// The 26.1 and 26.2 name of the old CompostingChanceRegistry, which the host still serves there.
public interface CompostableRegistry {
    CompostableRegistry INSTANCE = (item, value) -> { };
    void add(ItemLike item, Object value);
}
