// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

// A mod-bus event on the host.
public class RegisterItemModelsEvent extends net.neoforged.bus.api.Event {
    public void register(Identifier id, MapCodec<? extends ItemModel.Unbaked> codec) { throw new UnsupportedOperationException(); }
}
