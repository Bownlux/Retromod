// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.crafting;

import java.util.Collection;

public class RecipeMap {
    public <I extends RecipeInput, T extends Recipe<I>> Collection<RecipeHolder<T>> byType(RecipeType<T> type) { throw new UnsupportedOperationException(); }
}
