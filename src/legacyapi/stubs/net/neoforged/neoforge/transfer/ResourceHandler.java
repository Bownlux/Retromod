// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer;

import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public interface ResourceHandler<T extends Resource> {
    int size();
    T getResource(int index);
    long getAmountAsLong(int index);
    default int getAmountAsInt(int index) { throw new UnsupportedOperationException(); }
    long getCapacityAsLong(int index, T resource);
    default int getCapacityAsInt(int index, T resource) { throw new UnsupportedOperationException(); }
    boolean isValid(int index, T resource);
    int insert(int index, T resource, int amount, TransactionContext transaction);
    default int insert(T resource, int amount, TransactionContext transaction) { throw new UnsupportedOperationException(); }
    int extract(int index, T resource, int amount, TransactionContext transaction);
    default int extract(T resource, int amount, TransactionContext transaction) { throw new UnsupportedOperationException(); }
}
