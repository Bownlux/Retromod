// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer.transaction;

public abstract class SnapshotJournal<T> {
    public SnapshotJournal() {}
    protected abstract T createSnapshot();
    protected abstract void revertToSnapshot(T snapshot);
    protected void onRootCommit(T originalState) { throw new UnsupportedOperationException(); }
    public void updateSnapshots(TransactionContext transaction) { throw new UnsupportedOperationException(); }
}
