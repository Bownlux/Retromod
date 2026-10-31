package net.minecraft.core.component;

public final class DataComponentType<T> {
    private final String name;
    public DataComponentType(String name) { this.name = name; }
    @Override public String toString() { return name; }
}
