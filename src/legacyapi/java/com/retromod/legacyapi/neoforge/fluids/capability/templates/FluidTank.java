/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids.capability.templates;

import com.retromod.legacyapi.neoforge.fluids.IFluidTank;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;
import net.neoforged.neoforge.fluids.FluidStack;

/** One tank of a fixed capacity, the fluid store most old machines keep. */
public class FluidTank implements IFluidHandler, IFluidTank, ValueIOSerializable {

    protected Predicate<FluidStack> validator;
    protected FluidStack fluid = FluidStack.EMPTY;
    protected int capacity;

    public FluidTank(int capacity) {
        this(capacity, stack -> true);
    }

    public FluidTank(int capacity, Predicate<FluidStack> validator) {
        this.capacity = capacity;
        this.validator = validator;
    }

    public FluidTank setCapacity(int capacity) {
        this.capacity = capacity;
        return this;
    }

    public FluidTank setValidator(Predicate<FluidStack> validator) {
        if (validator != null) this.validator = validator;
        return this;
    }

    @Override
    public boolean isFluidValid(FluidStack stack) {
        return validator.test(stack);
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public FluidStack getFluid() {
        return fluid;
    }

    @Override
    public int getFluidAmount() {
        return fluid.getAmount();
    }

    @Override
    public void serialize(ValueOutput output) {
        if (!fluid.isEmpty()) output.store("Fluid", FluidStack.CODEC, fluid);
    }

    @Override
    public void deserialize(ValueInput input) {
        fluid = input.read("Fluid", FluidStack.CODEC).orElse(FluidStack.EMPTY);
    }

    /** NeoForge 1.21's NBT form, under the same {@code Fluid} key. */
    public FluidTank readFromNBT(HolderLookup.Provider provider, CompoundTag tag) {
        RegistryOps<Tag> ops = provider.createSerializationContext(NbtOps.INSTANCE);
        fluid = tag.getCompound("Fluid")
                .flatMap(saved -> FluidStack.CODEC.parse(ops, (Tag) saved).result())
                .orElse(FluidStack.EMPTY);
        return this;
    }

    public CompoundTag writeToNBT(HolderLookup.Provider provider, CompoundTag tag) {
        if (!fluid.isEmpty()) {
            tag.put("Fluid", FluidStack.CODEC.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), fluid).getOrThrow());
        }
        return tag;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return getFluid();
    }

    @Override
    public int getTankCapacity(int tank) {
        return getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return isFluidValid(stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !isFluidValid(resource)) return 0;
        if (!fluid.isEmpty() && !FluidStack.isSameFluidSameComponents(fluid, resource)) return 0;
        int filled = Math.min(capacity - fluid.getAmount(), resource.getAmount());
        if (filled <= 0 || action.simulate()) return Math.max(filled, 0);
        if (fluid.isEmpty()) fluid = resource.copyWithAmount(filled);
        else fluid.grow(filled);
        onContentsChanged();
        return filled;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !FluidStack.isSameFluidSameComponents(resource, fluid)) return FluidStack.EMPTY;
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        int drained = Math.min(maxDrain, fluid.getAmount());
        if (drained <= 0) return FluidStack.EMPTY;
        FluidStack result = fluid.copyWithAmount(drained);
        if (action.execute()) {
            fluid.shrink(drained);
            if (fluid.getAmount() <= 0) fluid = FluidStack.EMPTY;
            onContentsChanged();
        }
        return result;
    }

    protected void onContentsChanged() {}

    public void setFluid(FluidStack stack) {
        this.fluid = stack;
    }

    public boolean isEmpty() {
        return fluid.isEmpty();
    }

    public int getSpace() {
        return Math.max(0, capacity - fluid.getAmount());
    }
}
