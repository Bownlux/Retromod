/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandlerItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/** A current item fluid handler as an old one, its container read back from the access it works through. */
public final class ResourceFluidHandlerItem extends ResourceFluidHandler implements IFluidHandlerItem {

    private final ItemAccess access;

    public ResourceFluidHandlerItem(ResourceHandler<FluidResource> handler, ItemAccess access) {
        super(handler);
        this.access = access;
    }

    /** The item now in the access, which a fill or drain may have swapped, as a bucket swaps. */
    @Override
    public ItemStack getContainer() {
        return access.getResource().toStack(access.getAmount());
    }
}
