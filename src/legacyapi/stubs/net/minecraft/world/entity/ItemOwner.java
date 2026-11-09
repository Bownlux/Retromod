// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity;

public interface ItemOwner {
    default LivingEntity asLivingEntity() { throw new UnsupportedOperationException(); }
}
