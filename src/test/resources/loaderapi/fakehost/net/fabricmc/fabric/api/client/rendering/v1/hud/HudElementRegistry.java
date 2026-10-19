package net.fabricmc.fabric.api.client.rendering.v1.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The host HUD element registry as a flat ordered list, starting with the vanilla elements. */
public interface HudElementRegistry {
    Map<Identifier, HudElement> ELEMENTS = new LinkedHashMap<>();
    List<String> LOG = new ArrayList<>();

    static void addFirst(Identifier id, HudElement element) {
        vanilla();
        insert(0, id, element);
        LOG.add("first " + id);
    }

    static void addLast(Identifier id, HudElement element) {
        vanilla();
        insert(ELEMENTS.size(), id, element);
        LOG.add("last " + id);
    }

    static void attachElementBefore(Identifier beforeThis, Identifier id, HudElement element) {
        insert(indexOf(beforeThis), id, element);
        LOG.add("before " + beforeThis + " " + id);
    }

    static void attachElementAfter(Identifier afterThis, Identifier id, HudElement element) {
        insert(indexOf(afterThis) + 1, id, element);
        LOG.add("after " + afterThis + " " + id);
    }

    static void removeElement(Identifier id) {
        indexOf(id);
        ELEMENTS.remove(id);
        LOG.add("remove " + id);
    }

    static void replaceElement(Identifier id, Function<HudElement, HudElement> replacer) {
        indexOf(id);
        ELEMENTS.put(id, replacer.apply(ELEMENTS.get(id)));
        LOG.add("replace " + id);
    }

    /** Draws every element in order, as one HUD frame. */
    static void renderForTesting(GuiGraphicsExtractor graphics, DeltaTracker tracker) {
        for (HudElement element : new ArrayList<>(ELEMENTS.values())) element.extractRenderState(graphics, tracker);
    }

    /** The element ids in drawing order. */
    static List<String> orderForTesting() {
        List<String> order = new ArrayList<>();
        ELEMENTS.keySet().forEach(id -> order.add(id.toString()));
        return order;
    }

    // Like Fabric API, it refuses an anchor it does not know.
    private static int indexOf(Identifier id) {
        vanilla();
        int index = new ArrayList<>(ELEMENTS.keySet()).indexOf(id);
        if (index < 0) throw new IllegalArgumentException("Unknown HUD element " + id);
        return index;
    }

    private static void insert(int index, Identifier id, HudElement element) {
        vanilla();
        List<Map.Entry<Identifier, HudElement>> entries = new ArrayList<>(ELEMENTS.entrySet());
        entries.add(index, Map.entry(id, element));
        ELEMENTS.clear();
        entries.forEach(entry -> ELEMENTS.put(entry.getKey(), entry.getValue()));
    }

    /** Fills the vanilla elements once, each drawing its own name into the test log. */
    private static void vanilla() {
        if (!ELEMENTS.isEmpty()) return;
        for (Field field : VanillaHudElements.class.getFields()) {
            try {
                Identifier id = (Identifier) field.get(null);
                ELEMENTS.put(id, (graphics, tracker) -> fixture.Recorder.note("vanilla " + id));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
