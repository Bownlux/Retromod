package fixture.s1old;

import fixture.Recorder;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.class_1799;
import net.minecraft.class_1836;
import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_437;

import java.util.List;

/** A 1.20.1 mod's HUD callbacks, screen render events and tooltips. */
public final class OldScreens {
    private OldScreens() {}

    public static void registerHud() {
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> Recorder.note("old hud lambda " + graphics + " " + tickDelta));
        HudRenderCallback.EVENT.register(new OwnHud());
    }

    /** Fires the old event itself, as a mod that draws its HUD into its own screen did. */
    public static void fireHud(class_332 graphics, float tickDelta) {
        HudRenderCallback.EVENT.invoker().onHudRender(graphics, tickDelta);
    }

    public static void registerScreen(class_437 screen) {
        ScreenEvents.beforeRender(screen).register((current, graphics, mouseX, mouseY, tickDelta) ->
                Recorder.note("old before " + current + " " + graphics + " " + mouseX + " " + mouseY + " " + tickDelta));
        ScreenEvents.afterRender(screen).register(new OwnAfterRender());
    }

    public static void registerTooltips() {
        ItemTooltipCallback.EVENT.register((stack, flag, lines) -> Recorder.note("old tooltip lambda " + flag + " " + lines.size()));
        ItemTooltipCallback.EVENT.register(new OwnTooltip());
        net.fabricmc.fabric.api.event.client.ItemTooltipCallback.EVENT.register((stack, flag, lines) ->
                Recorder.note("v0 tooltip " + flag));
    }

    /** Fires the old tooltip event itself, as a mod with its own tooltip screen did. */
    public static void fireTooltip(class_1799 stack, class_1836 flag, List<class_2561> lines) {
        ItemTooltipCallback.EVENT.invoker().getTooltip(stack, flag, lines);
    }

    static final class OwnHud implements HudRenderCallback {
        @Override
        public void onHudRender(class_332 graphics, float tickDelta) {
            Recorder.note("old hud class " + tickDelta);
        }
    }

    static final class OwnAfterRender implements ScreenEvents.AfterRender {
        @Override
        public void afterRender(class_437 screen, class_332 graphics, int mouseX, int mouseY, float tickDelta) {
            Recorder.note("old after class " + screen + " " + tickDelta);
        }
    }

    static final class OwnTooltip implements ItemTooltipCallback {
        @Override
        public void getTooltip(class_1799 stack, class_1836 flag, List<class_2561> lines) {
            Recorder.note("old tooltip class " + flag);
        }
    }
}
