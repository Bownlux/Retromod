// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.RecordEvent;

public interface RegisterColorHandlersEvent {
    record Block(BlockColors getBlockColors) implements RecordEvent, RegisterColorHandlersEvent {
        public static final EventBus<Block> BUS = null;

        public void register(List<BlockTintSource> sources, net.minecraft.world.level.block.Block... blocks) {
            throw new UnsupportedOperationException();
        }
    }
}
