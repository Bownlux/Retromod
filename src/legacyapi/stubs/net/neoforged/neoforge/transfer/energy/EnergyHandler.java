// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer.energy;

import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public interface EnergyHandler {
    long getAmountAsLong();
    default int getAmountAsInt() { throw new UnsupportedOperationException(); }
    long getCapacityAsLong();
    default int getCapacityAsInt() { throw new UnsupportedOperationException(); }
    int insert(int amount, TransactionContext transaction);
    int extract(int amount, TransactionContext transaction);
}
