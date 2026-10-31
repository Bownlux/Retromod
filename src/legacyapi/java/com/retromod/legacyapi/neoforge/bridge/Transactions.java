/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Opens the transaction an old-style call runs a current handler in. */
public final class Transactions {

    private Transactions() {}

    /**
     * A root transaction, or one nested in the transaction already open on this thread. Old calls
     * do not know about transactions; when current code calls them inside one, their change has
     * to belong to it, or the outer transaction could not undo it.
     */
    public static Transaction open() {
        TransactionContext outer = Transaction.getCurrentOpenedTransaction();
        return outer == null ? Transaction.openRoot() : Transaction.open(outer);
    }
}
