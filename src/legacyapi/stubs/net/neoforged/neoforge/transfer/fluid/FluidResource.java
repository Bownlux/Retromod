// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer.fluid;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.resource.Resource;

public final class FluidResource implements Resource {
    public static final FluidResource EMPTY = null;
    public static FluidResource of(FluidStack stack) { throw new UnsupportedOperationException(); }
    @Override public boolean isEmpty() { throw new UnsupportedOperationException(); }
    public boolean matches(FluidStack stack) { throw new UnsupportedOperationException(); }
    public FluidStack toStack(int amount) { throw new UnsupportedOperationException(); }
}
