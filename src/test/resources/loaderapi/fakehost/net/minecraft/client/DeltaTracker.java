package net.minecraft.client;

public interface DeltaTracker {
    DeltaTracker ZERO = of(0f);

    float getGameTimeDeltaTicks();

    float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame);

    float getRealtimeDeltaTicks();

    /** A tracker whose partial tick is {@code partial}, for tests. */
    static DeltaTracker of(float partial) {
        return new DeltaTracker() {
            @Override public float getGameTimeDeltaTicks() { return 1f; }
            @Override public float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame) { return partial; }
            @Override public float getRealtimeDeltaTicks() { return 1f; }
            @Override public String toString() { return "tracker " + partial; }
        };
    }
}
