// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.fluids;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.MutableDataComponentHolder;

public final class FluidStack implements MutableDataComponentHolder {
    public static final FluidStack EMPTY = null;
    public static final MapCodec<FluidStack> MAP_CODEC = null;
    public static final Codec<FluidStack> CODEC = null;
    public static final Codec<FluidStack> OPTIONAL_CODEC = null;
    public FluidStack(Fluid fluid, int amount) { throw new UnsupportedOperationException(); }
    public boolean isEmpty() { throw new UnsupportedOperationException(); }
    public int getAmount() { throw new UnsupportedOperationException(); }
    public void setAmount(int amount) { throw new UnsupportedOperationException(); }
    public void grow(int amount) { throw new UnsupportedOperationException(); }
    public void shrink(int amount) { throw new UnsupportedOperationException(); }
    public FluidStack copy() { throw new UnsupportedOperationException(); }
    public FluidStack copyWithAmount(int amount) { throw new UnsupportedOperationException(); }
    public Fluid getFluid() { throw new UnsupportedOperationException(); }
    public static boolean isSameFluid(FluidStack first, FluidStack second) { throw new UnsupportedOperationException(); }
    public static boolean isSameFluidSameComponents(FluidStack first, FluidStack second) { throw new UnsupportedOperationException(); }
    @Override public <T> T get(DataComponentType<? extends T> type) { throw new UnsupportedOperationException(); }
    @Override public <T> T set(DataComponentType<T> type, T value) { throw new UnsupportedOperationException(); }
    @Override public <T> T remove(DataComponentType<? extends T> type) { throw new UnsupportedOperationException(); }
}
