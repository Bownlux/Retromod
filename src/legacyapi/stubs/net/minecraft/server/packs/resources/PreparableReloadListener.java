// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.resources;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface PreparableReloadListener {
    CompletableFuture<Void> reload(SharedState state, Executor prepareExecutor, PreparationBarrier barrier, Executor applyExecutor);

    final class SharedState {
        private SharedState() {}
        public ResourceManager resourceManager() { throw new UnsupportedOperationException(); }
    }

    interface PreparationBarrier {
        <T> CompletableFuture<T> wait(T value);
    }
}
