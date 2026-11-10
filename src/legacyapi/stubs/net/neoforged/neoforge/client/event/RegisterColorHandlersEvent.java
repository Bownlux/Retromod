// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public abstract class RegisterColorHandlersEvent extends Event implements IModBusEvent {
    protected RegisterColorHandlersEvent() {}

    public static class BlockTintSources extends RegisterColorHandlersEvent {
        public BlockTintSources(BlockColors colors) { throw new UnsupportedOperationException(); }
        public BlockColors getBlockColors() { throw new UnsupportedOperationException(); }
        public void register(List<BlockTintSource> sources, Block... blocks) { throw new UnsupportedOperationException(); }
    }

    public static class ItemTintSources extends RegisterColorHandlersEvent {
        public void register(Identifier id, MapCodec<? extends ItemTintSource> codec) { throw new UnsupportedOperationException(); }
    }
}
