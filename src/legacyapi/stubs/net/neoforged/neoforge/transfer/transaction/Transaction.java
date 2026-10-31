// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer.transaction;

public final class Transaction implements AutoCloseable, TransactionContext {
    public static Transaction openRoot() { throw new UnsupportedOperationException(); }
    public static Transaction open(TransactionContext parent) { throw new UnsupportedOperationException(); }
    public static TransactionContext getCurrentOpenedTransaction() { throw new UnsupportedOperationException(); }
    public void commit() { throw new UnsupportedOperationException(); }
    @Override public void close() { throw new UnsupportedOperationException(); }
    @Override public int depth() { throw new UnsupportedOperationException(); }
}
