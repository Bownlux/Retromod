package net.fabricmc.fabric.api.registry;

// The 26.x shape: the entry's getters say ignite and burn odds, as vanilla's fire tables do.
public interface FlammableBlockRegistry {
    final class Entry {
        private final int igniteOdds;
        private final int burnOdds;
        public Entry(int igniteOdds, int burnOdds) { this.igniteOdds = igniteOdds; this.burnOdds = burnOdds; }
        public int getIgniteOdds() { return igniteOdds; }
        public int getBurnOdds() { return burnOdds; }
    }
}
