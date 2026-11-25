// Compile-only stub of a NeoForge 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import net.minecraft.client.resources.model.ModelBakery;
import net.neoforged.bus.api.Event;

public abstract class ModelEvent extends Event {
    private ModelEvent() {}

    public static class ModifyBakingResult extends ModelEvent {
        private ModifyBakingResult() {}
        public ModelBakery.BakingResult getBakingResult() { throw new UnsupportedOperationException(); }
    }
}
