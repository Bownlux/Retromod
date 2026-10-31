package net.minecraft.tags;

public final class TagKey<T> {
    private final String id;
    public TagKey(String id) { this.id = id; }
    @Override public String toString() { return "#" + id; }
}
