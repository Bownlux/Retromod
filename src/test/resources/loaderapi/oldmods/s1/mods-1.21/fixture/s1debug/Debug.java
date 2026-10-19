package fixture.s1debug;

import fixture.Recorder;
import net.fabricmc.fabric.api.client.rendering.v1.GatherDebugTextEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.class_2960;

/** A 1.21.8 mod with debug text and HUD elements placed next to ids 26.x removed or renamed. */
public final class Debug {
    private Debug() {}

    public static void register(class_2960 beforeDebug, class_2960 afterEffects, class_2960 afterChat) {
        GatherDebugTextEvents.LEFT.register(lines -> lines.add("old left"));
        GatherDebugTextEvents.RIGHT.register(lines -> lines.add("old right"));
        HudElementRegistry.attachElementBefore(VanillaHudElements.DEBUG, beforeDebug, (graphics, tracker) -> Recorder.note("before debug"));
        HudElementRegistry.attachElementAfter(VanillaHudElements.STATUS_EFFECTS, afterEffects, (graphics, tracker) -> Recorder.note("after effects"));
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, afterChat, (graphics, tracker) -> Recorder.note("after chat"));
        HudElementRegistry.removeElement(VanillaHudElements.DEBUG);
    }
}
