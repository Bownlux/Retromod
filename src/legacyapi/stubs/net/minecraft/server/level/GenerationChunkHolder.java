// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.level;

// Abstract on the host; ChunkHolder extends it.
public abstract class GenerationChunkHolder {
    public java.util.concurrent.CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> scheduleChunkGenerationTask(
            net.minecraft.world.level.chunk.status.ChunkStatus status, ChunkMap map) { throw new UnsupportedOperationException(); }
}
