/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** AE2 storage/grid/cell/crafting API renames between AE2 12.x and 15.x. */
public class Ae2ApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Applied Energistics 2 API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "12.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "15.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "common";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "appeng/api/AEApi",
            "appeng/api/AEApi"
        );

        // stacks replaced by the AEKey hierarchy
        transformer.registerClassRedirect(
            "appeng/api/storage/data/IAEStack",
            "appeng/api/stacks/AEKey"
        );

        transformer.registerClassRedirect(
            "appeng/api/storage/data/IAEItemStack",
            "appeng/api/stacks/AEItemKey"
        );

        transformer.registerClassRedirect(
            "appeng/api/storage/data/IAEFluidStack",
            "appeng/api/stacks/AEFluidKey"
        );

        transformer.registerClassRedirect(
            "appeng/api/storage/IStorageChannel",
            "appeng/api/stacks/AEKeyType"
        );

        transformer.registerClassRedirect(
            "appeng/api/storage/channels/IItemStorageChannel",
            "appeng/api/stacks/AEKeyTypes"
        );

        transformer.registerClassRedirect(
            "appeng/api/storage/channels/IFluidStorageChannel",
            "appeng/api/stacks/AEKeyTypes"
        );

        // cells moved under storage/cells/
        transformer.registerClassRedirect(
            "appeng/api/storage/ICellInventory",
            "appeng/api/storage/cells/ICellHandler"
        );

        transformer.registerClassRedirect(
            "appeng/api/storage/ICellHandler",
            "appeng/api/storage/cells/ICellHandler"
        );

        transformer.registerClassRedirect(
            "appeng/api/storage/ICellWorkbenchItem",
            "appeng/api/storage/cells/ICellWorkbenchItem"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/IGrid",
            "appeng/api/networking/IGrid"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/IGridNode",
            "appeng/api/networking/IGridNode"
        );

        // grid caches became grid services
        transformer.registerClassRedirect(
            "appeng/api/networking/IGridCache",
            "appeng/api/networking/IGridService"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/security/ISecurityGrid",
            "appeng/api/networking/security/ISecurityService"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/storage/IStorageGrid",
            "appeng/api/networking/storage/IStorageService"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/crafting/ICraftingGrid",
            "appeng/api/networking/crafting/ICraftingService"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/energy/IEnergyGrid",
            "appeng/api/networking/energy/IEnergyService"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/crafting/ICraftingPatternDetails",
            "appeng/api/crafting/IPatternDetails"
        );

        transformer.registerClassRedirect(
            "appeng/api/networking/crafting/ICraftingJob",
            "appeng/api/networking/crafting/ICraftingPlan"
        );

        transformer.registerClassRedirect(
            "appeng/api/parts/IPart",
            "appeng/api/parts/IPart"
        );

        transformer.registerClassRedirect(
            "appeng/api/parts/IPartHost",
            "appeng/api/parts/IPartHost"
        );

        transformer.registerClassRedirect(
            "appeng/api/parts/IPartItem",
            "appeng/api/parts/IPartItem"
        );

        // tile entity -> block entity
        transformer.registerClassRedirect(
            "appeng/tile/AEBaseTileEntity",
            "appeng/blockentity/AEBaseBlockEntity"
        );

        transformer.registerClassRedirect(
            "appeng/tile/networking/CableBusTileEntity",
            "appeng/blockentity/networking/CableBusBlockEntity"
        );

        transformer.registerClassRedirect(
            "appeng/api/util/AEColor",
            "appeng/api/util/AEColor"
        );

        transformer.registerClassRedirect(
            "appeng/api/util/AECableType",
            "appeng/api/util/AECableType"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.common.embedded.Ae2Shim"
        };
    }
}
