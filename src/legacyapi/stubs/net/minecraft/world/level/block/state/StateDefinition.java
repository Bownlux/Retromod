// Compile-only stub of a 1.21.2 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block.state;

import com.google.common.collect.ImmutableList;
import java.util.Collection;
import net.minecraft.world.level.block.state.properties.Property;

public class StateDefinition<O, S> {
    private StateDefinition() {}
    public ImmutableList<S> getPossibleStates() { throw new UnsupportedOperationException(); }
    public Collection<Property<?>> getProperties() { throw new UnsupportedOperationException(); }

    public static class Builder<O, S> {
        public Builder<O, S> add(Property<?>... properties) { throw new UnsupportedOperationException(); }
    }
}
