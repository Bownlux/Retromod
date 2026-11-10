// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.attribute;

public interface EnvironmentAttributeReader {
    <Value> Value getDimensionValue(EnvironmentAttribute<Value> attribute);
}
