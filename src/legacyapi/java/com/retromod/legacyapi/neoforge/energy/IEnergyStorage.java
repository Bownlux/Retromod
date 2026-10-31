/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.energy;

import com.retromod.legacyapi.neoforge.bridge.EnergyHandlerStorage;
import com.retromod.legacyapi.neoforge.bridge.EnergyStorageHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;

/** NeoForge's energy storage, which 26.3 removed, as old mods call and implement it. */
public interface IEnergyStorage {

    /** The old storage for a current energy handler; one that wraps an old storage gives that one back. */
    static IEnergyStorage of(EnergyHandler handler) {
        if (handler instanceof EnergyStorageHandler adapter) return adapter.storage();
        return new EnergyHandlerStorage(handler);
    }

    int receiveEnergy(int toReceive, boolean simulate);

    int extractEnergy(int toExtract, boolean simulate);

    int getEnergyStored();

    int getMaxEnergyStored();

    boolean canExtract();

    boolean canReceive();
}
