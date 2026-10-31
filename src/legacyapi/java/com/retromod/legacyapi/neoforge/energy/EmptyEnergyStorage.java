/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.energy;

/** A storage that holds and moves no energy. */
public class EmptyEnergyStorage implements IEnergyStorage {

    public static final EmptyEnergyStorage INSTANCE = new EmptyEnergyStorage();

    protected EmptyEnergyStorage() {}

    @Override public int receiveEnergy(int toReceive, boolean simulate) { return 0; }
    @Override public int extractEnergy(int toExtract, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return 0; }
    @Override public int getMaxEnergyStored() { return 0; }
    @Override public boolean canExtract() { return false; }
    @Override public boolean canReceive() { return false; }
}
