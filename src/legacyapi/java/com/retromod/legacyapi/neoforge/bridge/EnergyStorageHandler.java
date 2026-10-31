/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import com.retromod.legacyapi.neoforge.energy.IEnergyStorage;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** An old energy storage as a current energy handler. Transfers apply at once and an aborted transaction reverses them. */
public final class EnergyStorageHandler extends SnapshotJournal<Integer> implements EnergyHandler {

    private final IEnergyStorage storage;
    // Positive for energy received, negative for energy extracted, newest last.
    private final List<Integer> changes = new ArrayList<>();

    public EnergyStorageHandler(IEnergyStorage storage) {
        this.storage = storage;
    }

    public IEnergyStorage storage() {
        return storage;
    }

    @Override
    public long getAmountAsLong() {
        return storage.getEnergyStored();
    }

    @Override
    public long getCapacityAsLong() {
        return storage.getMaxEnergyStored();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        if (amount <= 0 || !storage.canReceive()) return 0;
        updateSnapshots(transaction);
        int received = storage.receiveEnergy(amount, false);
        if (received > 0) changes.add(received);
        return received;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        if (amount <= 0 || !storage.canExtract()) return 0;
        updateSnapshots(transaction);
        int extracted = storage.extractEnergy(amount, false);
        if (extracted > 0) changes.add(-extracted);
        return extracted;
    }

    @Override
    protected Integer createSnapshot() {
        return changes.size();
    }

    @Override
    protected void revertToSnapshot(Integer mark) {
        for (int i = changes.size() - 1; i >= mark; i--) {
            int change = changes.remove(i);
            if (change > 0) storage.extractEnergy(change, false);
            else storage.receiveEnergy(-change, false);
        }
    }

    @Override
    protected void onRootCommit(Integer originalState) {
        changes.clear();
    }
}
