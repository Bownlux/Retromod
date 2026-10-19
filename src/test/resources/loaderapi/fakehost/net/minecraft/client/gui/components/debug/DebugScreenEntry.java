package net.minecraft.client.gui.components.debug;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public interface DebugScreenEntry {
    void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk);

    default boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    default DebugEntryCategory category() {
        return DebugEntryCategory.SCREEN_TEXT;
    }
}
