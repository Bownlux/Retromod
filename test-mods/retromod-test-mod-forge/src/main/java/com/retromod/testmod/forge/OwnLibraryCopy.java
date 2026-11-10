/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import appeng.tile.AEBaseTileEntity;

/** Calls the class this mod ships under a library name Retromod moves, kept out of the mod class like the others. */
final class OwnLibraryCopy {

    private OwnLibraryCopy() {}

    /** The mod's own copy answers under its own name, as GeckoLib 4's classes must inside GeckoLib 4. */
    static boolean staysTheModsOwn() {
        return "retromod-test-mod-forge".equals(AEBaseTileEntity.origin())
                && AEBaseTileEntity.class.getName().equals("appeng.tile.AEBaseTileEntity");
    }
}
