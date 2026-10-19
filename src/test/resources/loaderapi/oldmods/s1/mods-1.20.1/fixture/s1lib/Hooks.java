package fixture.s1lib;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import fixture.Recorder;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.class_437;

/** A 1.20.1 library that takes the old callback types in its public methods. */
public final class Hooks {
    private Hooks() {}

    public static void onHud(HudRenderCallback callback) {
        HudRenderCallback.EVENT.register(callback);
    }

    public static void onAfterRender(class_437 screen, ScreenEvents.AfterRender callback) {
        ScreenEvents.afterRender(screen).register(callback);
    }

    public static void onTooltip(ItemTooltipCallback callback) {
        ItemTooltipCallback.EVENT.register(callback);
    }

    /** A library interface that extends the old HUD callback, which a dependent implements and calls. */
    public interface LibHud extends HudRenderCallback {
    }

    /** A library-made callback that a dependent calls through the library's interface. */
    public static LibHud libHud() {
        return (drawContext, tickDelta) -> Recorder.note("lib sub hud " + tickDelta);
    }

    /** A base class a dependent extends, which implements the old tooltip callback itself. */
    public abstract static class BaseTooltip implements ItemTooltipCallback {
    }
}
