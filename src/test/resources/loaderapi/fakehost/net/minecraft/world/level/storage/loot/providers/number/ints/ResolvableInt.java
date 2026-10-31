package net.minecraft.world.level.storage.loot.providers.number.ints;

public interface ResolvableInt {
    record Constant(int value) implements ResolvableInt {
        @Override public String toString() { return "constant " + value; }
    }
}
