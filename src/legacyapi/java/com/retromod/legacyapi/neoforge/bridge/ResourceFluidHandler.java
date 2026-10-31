/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** A current fluid resource handler as an old fluid handler. */
public class ResourceFluidHandler implements IFluidHandler {

    protected final ResourceHandler<FluidResource> handler;

    public ResourceFluidHandler(ResourceHandler<FluidResource> handler) {
        this.handler = handler;
    }

    public ResourceHandler<FluidResource> handler() {
        return handler;
    }

    @Override
    public int getTanks() {
        return handler.size();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        FluidResource resource = handler.getResource(tank);
        return resource.isEmpty() ? FluidStack.EMPTY : resource.toStack(handler.getAmountAsInt(tank));
    }

    @Override
    public int getTankCapacity(int tank) {
        return handler.getCapacityAsInt(tank, handler.getResource(tank));
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return handler.isValid(tank, FluidResource.of(stack));
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return 0;
        try (Transaction transaction = Transactions.open()) {
            int filled = handler.insert(FluidResource.of(resource), resource.getAmount(), transaction);
            if (action.execute()) transaction.commit();
            return filled;
        }
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return FluidStack.EMPTY;
        FluidResource kind = FluidResource.of(resource);
        try (Transaction transaction = Transactions.open()) {
            int drained = handler.extract(kind, resource.getAmount(), transaction);
            if (action.execute()) transaction.commit();
            return drained <= 0 ? FluidStack.EMPTY : kind.toStack(drained);
        }
    }

    /** Drains the first fluid any tank holds. */
    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        for (int tank = 0; tank < handler.size(); tank++) {
            FluidResource kind = handler.getResource(tank);
            if (kind.isEmpty()) continue;
            return drain(kind.toStack(maxDrain), action);
        }
        return FluidStack.EMPTY;
    }
}
