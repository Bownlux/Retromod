// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.MutableEvent;
import net.minecraftforge.eventbus.api.event.RecordEvent;

public interface ModelEvent {
    record ModifyBakingResult(ModelBakery getModelBakery, ModelBakery.BakingResult getResults) implements RecordEvent, ModelEvent {
        public static final EventBus<ModifyBakingResult> BUS = null;
    }

    record BakingCompleted(ModelManager getModelManager, ModelBakery getModelBakery) implements RecordEvent, ModelEvent {
        public static final EventBus<BakingCompleted> BUS = null;
    }

    final class RegisterGeometryLoaders extends MutableEvent implements ModelEvent {
        public static final EventBus<RegisterGeometryLoaders> BUS = null;
        public RegisterGeometryLoaders(java.util.Map<net.minecraft.resources.Identifier, net.minecraftforge.client.model.geometry.IGeometryLoader> loaders) {}
        public void register(net.minecraft.resources.Identifier name, net.minecraftforge.client.model.geometry.IGeometryLoader loader) { throw new UnsupportedOperationException(); }
    }
}
