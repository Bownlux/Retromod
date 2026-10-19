package net.fabricmc.fabric.api.client.rendering.v1;

import net.fabricmc.fabric.api.event.Event;

import java.util.List;

/** Compile-only copy of the Fabric API 0.128.2 (1.21.5) signature. */
public final class GatherDebugTextEvents {
    public static final Event<Left> LEFT = null;
    public static final Event<Right> RIGHT = null;

    private GatherDebugTextEvents() {}

    @FunctionalInterface
    public interface Left {
        void onGatherLeftDebugText(List<String> lines);
    }

    @FunctionalInterface
    public interface Right {
        void onGatherRightDebugText(List<String> lines);
    }
}
