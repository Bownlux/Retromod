package fixture.s1layers;

import fixture.Recorder;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.class_2960;
import net.minecraft.class_332;
import net.minecraft.class_9779;

/** A 1.21.4 mod that adds, moves, removes and wraps HUD layers. */
public final class Layers {
    private Layers() {}

    public static void register(class_2960 own, class_2960 beforeChat, class_2960 afterBars) {
        HudLayerRegistrationCallback.EVENT.register(drawer -> {
            drawer.addLayer(IdentifiedLayer.of(own, (graphics, tracker) -> Recorder.note("layer own " + graphics)));
            drawer.attachLayerBefore(IdentifiedLayer.CHAT, beforeChat, (graphics, tracker) -> Recorder.note("layer before chat"));
            drawer.attachLayerAfter(IdentifiedLayer.HOTBAR_AND_BARS, new OwnLayer(afterBars));
            drawer.removeLayer(IdentifiedLayer.DEBUG);
            drawer.replaceLayer(IdentifiedLayer.SUBTITLES, layer -> IdentifiedLayer.of(layer.id(), (graphics, tracker) -> {
                Recorder.note("wrapped " + layer.id());
                layer.render(graphics, tracker);
            }));
        });
    }

    static final class OwnLayer implements IdentifiedLayer {
        private final class_2960 id;

        OwnLayer(class_2960 id) {
            this.id = id;
        }

        @Override
        public class_2960 id() {
            return id;
        }

        @Override
        public void render(class_332 graphics, class_9779 tracker) {
            Recorder.note("layer class " + tracker);
        }
    }
}
