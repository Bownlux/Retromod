// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block.state.properties;

public abstract class Property<T extends Comparable<T>> {
    protected Property() {}
    public String getName() { throw new UnsupportedOperationException(); }
    public abstract String getName(T value);
}
