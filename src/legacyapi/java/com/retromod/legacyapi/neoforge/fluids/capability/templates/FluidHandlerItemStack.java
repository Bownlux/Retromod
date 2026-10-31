/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids.capability.templates;

import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandlerItem;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

/** A fluid container item that keeps its fluid in a component and fills and drains in any amount. */
public class FluidHandlerItemStack implements IFluidHandlerItem {

    protected final Supplier<DataComponentType<SimpleFluidContent>> componentType;
    protected ItemStack container;
    protected int capacity;

    public FluidHandlerItemStack(Supplier<DataComponentType<SimpleFluidContent>> componentType, ItemStack container, int capacity) {
        this.componentType = componentType;
        this.container = container;
        this.capacity = capacity;
    }

    @Override
    public ItemStack getContainer() {
        return container;
    }

    public FluidStack getFluid() {
        return container.getOrDefault(componentType.get(), SimpleFluidContent.EMPTY).copy();
    }

    protected void setFluid(FluidStack fluid) {
        container.set(componentType.get(), SimpleFluidContent.copyOf(fluid));
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
        return capacity;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return true;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        // Only a single item holds fluid; a stack of them would share one component.
        if (container.getCount() != 1 || resource.isEmpty() || !canFillFluidType(resource)) return 0;
        FluidStack contained = getFluid();
        if (!contained.isEmpty() && !FluidStack.isSameFluidSameComponents(contained, resource)) return 0;
        int filled = Math.min(capacity - contained.getAmount(), resource.getAmount());
        if (filled <= 0) return 0;
        if (action.execute()) setFluid(resource.copyWithAmount(contained.getAmount() + filled));
        return filled;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (container.getCount() != 1 || resource.isEmpty() || !FluidStack.isSameFluidSameComponents(resource, getFluid())) {
            return FluidStack.EMPTY;
        }
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (container.getCount() != 1 || maxDrain <= 0) return FluidStack.EMPTY;
        FluidStack contained = getFluid();
        if (contained.isEmpty() || !canDrainFluidType(contained)) return FluidStack.EMPTY;
        int drained = Math.min(contained.getAmount(), maxDrain);
        FluidStack result = contained.copyWithAmount(drained);
        if (action.execute()) {
            if (drained == contained.getAmount()) setContainerToEmpty();
            else setFluid(contained.copyWithAmount(contained.getAmount() - drained));
        }
        return result;
    }

    public boolean canFillFluidType(FluidStack fluid) {
        return true;
    }

    public boolean canDrainFluidType(FluidStack fluid) {
        return true;
    }

    /** Called when the last of the fluid is drained. */
    protected void setContainerToEmpty() {
        container.remove(componentType.get());
    }

    /** A container that is used up when it empties. */
    public static class Consumable extends FluidHandlerItemStack {
        public Consumable(Supplier<DataComponentType<SimpleFluidContent>> componentType, ItemStack container, int capacity) {
            super(componentType, container, capacity);
        }

        @Override
        protected void setContainerToEmpty() {
            super.setContainerToEmpty();
            container.shrink(1);
        }
    }

    /** A container that turns into another item when it empties. */
    public static class SwapEmpty extends FluidHandlerItemStack {
        protected final ItemStack emptyContainer;

        public SwapEmpty(Supplier<DataComponentType<SimpleFluidContent>> componentType, ItemStack container, ItemStack emptyContainer, int capacity) {
            super(componentType, container, capacity);
            this.emptyContainer = emptyContainer;
        }

        @Override
        protected void setContainerToEmpty() {
            super.setContainerToEmpty();
            container = emptyContainer;
        }
    }
}
