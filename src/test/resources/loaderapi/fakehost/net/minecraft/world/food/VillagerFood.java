package net.minecraft.world.food;

public record VillagerFood(int nutrition) {
    @Override public String toString() { return "villager food " + nutrition; }
}
