package net.fabricmc.fabric.api.client.rendering.v1;

import net.minecraft.class_2960;
import net.minecraft.class_9080;

/** Compile-only copy of the Fabric API 0.119.4 (1.21.4) signature. */
public interface IdentifiedLayer extends class_9080.class_9081 {
    class_2960 MISC_OVERLAYS = null;
    class_2960 CROSSHAIR = null;
    class_2960 HOTBAR_AND_BARS = null;
    class_2960 EXPERIENCE_LEVEL = null;
    class_2960 STATUS_EFFECTS = null;
    class_2960 BOSS_BAR = null;
    class_2960 SLEEP = null;
    class_2960 DEMO_TIMER = null;
    class_2960 DEBUG = null;
    class_2960 SCOREBOARD = null;
    class_2960 OVERLAY_MESSAGE = null;
    class_2960 TITLE_AND_SUBTITLE = null;
    class_2960 CHAT = null;
    class_2960 PLAYER_LIST = null;
    class_2960 SUBTITLES = null;

    class_2960 id();

    static IdentifiedLayer of(class_2960 id, class_9080.class_9081 layer) {
        throw new AssertionError("compile-only");
    }
}
