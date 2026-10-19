package fixture.s1new;

import fixture.Recorder;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/** A 1.21.1 mod's HUD callback and tooltip, whose shapes 26.x still serves. */
public final class NewScreens {
    private NewScreens() {}

    public static void register() {
        HudRenderCallback.EVENT.register((graphics, tracker) -> Recorder.note("new hud lambda " + graphics + " " + tracker));
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> Recorder.note("new tooltip " + context + " " + type));
    }
}
