package net.minecraft.world.item.component;

import net.minecraft.resources.ResourceKey;

public final class Compostable {
    private final ResourceKey<?> layers;
    public Compostable(ResourceKey<?> layers) { this.layers = layers; }
    @Override public String toString() { return "compostable " + layers; }
}
