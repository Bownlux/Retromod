package fixture.s1dep;

import fixture.Recorder;
import fixture.s1lib.Hooks;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.class_1799;
import net.minecraft.class_1836;
import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_437;

import java.util.List;

/** A 1.20.1 mod that hands its callbacks to the library, translated apart from it. */
public final class Main {
    private Main() {}

    public static void register(class_437 screen) {
        Hooks.onHud((graphics, tickDelta) -> Recorder.note("dep hud " + tickDelta));
        Hooks.onAfterRender(screen, (current, graphics, mouseX, mouseY, tickDelta) -> Recorder.note("dep after " + tickDelta));
        Hooks.onTooltip((stack, flag, lines) -> Recorder.note("dep tooltip lambda"));
        Hooks.onTooltip(new DepTooltip());
    }

    /** Registers HUD callbacks typed as the library's own sub-interface. */
    public static void registerLibHud() {
        Hooks.LibHud own = (graphics, tickDelta) -> Recorder.note("dep sub hud " + tickDelta);
        Hooks.onHud(own);
        Hooks.onHud(new DepLibHud());
    }

    /** Calls callbacks through the library's sub-interface, as a mod that keeps its own list does. */
    public static void fireLibHud(class_332 graphics, float tickDelta) {
        Hooks.LibHud own = (current, delta) -> Recorder.note("dep sub hud " + delta);
        for (Hooks.LibHud hud : List.of(own, new DepLibHud(), Hooks.libHud())) {
            hud.onHudRender(graphics, tickDelta);
        }
    }

    static final class DepLibHud implements Hooks.LibHud {
        @Override
        public void onHudRender(class_332 graphics, float tickDelta) {
            Recorder.note("dep sub hud class " + tickDelta);
        }
    }

    /** Extends the library's base and names the old type again, so it reaches both jars' bridges. */
    static final class DepTooltip extends Hooks.BaseTooltip implements ItemTooltipCallback {
        @Override
        public void getTooltip(class_1799 stack, class_1836 flag, List<class_2561> lines) {
            Recorder.note("dep tooltip class");
        }
    }
}
