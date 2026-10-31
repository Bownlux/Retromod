/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import com.retromod.legacyapi.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** A current energy handler as an old energy storage. */
public final class EnergyHandlerStorage implements IEnergyStorage {

    private final EnergyHandler handler;

    public EnergyHandlerStorage(EnergyHandler handler) {
        this.handler = handler;
    }

    public EnergyHandler handler() {
        return handler;
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        try (Transaction transaction = Transactions.open()) {
            int received = handler.insert(toReceive, transaction);
            if (!simulate) transaction.commit();
            return received;
        }
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (toExtract <= 0) return 0;
        try (Transaction transaction = Transactions.open()) {
            int extracted = handler.extract(toExtract, transaction);
            if (!simulate) transaction.commit();
            return extracted;
        }
    }

    @Override
    public int getEnergyStored() {
        return handler.getAmountAsInt();
    }

    @Override
    public int getMaxEnergyStored() {
        return handler.getCapacityAsInt();
    }

    // A current handler does not say which way it moves energy; asking with a transaction it
    // throws away answers without changing it.
    @Override
    public boolean canExtract() {
        return extractEnergy(1, true) > 0 || handler.getAmountAsLong() == 0;
    }

    @Override
    public boolean canReceive() {
        return receiveEnergy(1, true) > 0 || handler.getAmountAsLong() >= handler.getCapacityAsLong();
    }
}
