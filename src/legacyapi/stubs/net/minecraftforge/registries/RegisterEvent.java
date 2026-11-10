// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.registries;

import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.fml.event.IModBusEvent;

public class RegisterEvent implements IModBusEvent {
    RegisterEvent() {}
    public static EventBus<RegisterEvent> getBus(BusGroup group) { throw new UnsupportedOperationException(); }
    public ResourceKey<? extends Registry<?>> getRegistryKey() { throw new UnsupportedOperationException(); }
    public <T> void register(ResourceKey<? extends Registry<T>> key, Identifier name, Supplier<T> valueSupplier) { throw new UnsupportedOperationException(); }
}
