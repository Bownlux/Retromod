package net.minecraft.world.item.alchemy;

public class Potion {
    private final String name;
    public Potion(String name) { this.name = name; }
    @Override public String toString() { return name; }
}
