// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

public final class Capabilities {
    public static final class Item {
        public static final BlockCapability<ResourceHandler<ItemResource>, Direction> BLOCK = null;
        public static final EntityCapability<ResourceHandler<ItemResource>, Void> ENTITY = null;
        public static final EntityCapability<ResourceHandler<ItemResource>, Direction> ENTITY_AUTOMATION = null;
        public static final ItemCapability<ResourceHandler<ItemResource>, ItemAccess> ITEM = null;
    }
    public static final class Fluid {
        public static final BlockCapability<ResourceHandler<FluidResource>, Direction> BLOCK = null;
        public static final EntityCapability<ResourceHandler<FluidResource>, Direction> ENTITY = null;
        public static final ItemCapability<ResourceHandler<FluidResource>, ItemAccess> ITEM = null;
    }
    public static final class Energy {
        public static final BlockCapability<EnergyHandler, Direction> BLOCK = null;
        public static final EntityCapability<EnergyHandler, Direction> ENTITY = null;
        public static final ItemCapability<EnergyHandler, ItemAccess> ITEM = null;
    }
}
