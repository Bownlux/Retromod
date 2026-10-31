// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

public final class EntityCapability<T, C> extends BaseCapability<T, C> {
    private EntityCapability() { super(null, null, null); }
    public static <T> EntityCapability<T, Direction> createSided(Identifier name, Class<T> type) { throw new UnsupportedOperationException(); }
    public static <T> EntityCapability<T, Void> createVoid(Identifier name, Class<T> type) { throw new UnsupportedOperationException(); }
    public T getCapability(Entity entity, C context) { throw new UnsupportedOperationException(); }
}
