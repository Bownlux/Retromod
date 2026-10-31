package net.minecraft.world.item.component;

import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

public final class CookingFuel {
    private final ResolvableInt burnTime;
    private final ResolvableFloat speedMultiplier;
    public CookingFuel(ResolvableInt burnTime, ResolvableFloat speedMultiplier) {
        this.burnTime = burnTime;
        this.speedMultiplier = speedMultiplier;
    }
    @Override public String toString() { return "fuel " + burnTime + " at " + speedMultiplier; }
}
