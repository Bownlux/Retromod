// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui.components.debug;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public interface DebugScreenEntry {
    void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk);
}
