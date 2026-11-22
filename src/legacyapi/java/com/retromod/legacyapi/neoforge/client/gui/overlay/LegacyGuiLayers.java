/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.gui.overlay;

import com.mojang.blaze3d.platform.Window;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;

/** Forge 1.20.1's overlay registration and render event members on NeoForge's GUI layers, for one mod. */
public final class LegacyGuiLayers {

    /** Each old vanilla overlay path, in the old drawing order, and the NeoForge layer fields that stand for it, newest first. */
    static final Map<String, List<String>> LAYER_FIELDS = layerFields();
    private static final String VANILLA_LAYERS = "net.neoforged.neoforge.client.gui.VanillaGuiLayers";

    // The overlays this mod's copy registered, by their full id.
    private static final Map<Identifier, IGuiOverlay> OWN = new ConcurrentHashMap<>();
    // Overlays turned off for a missing class or member, which would fail the same way every frame.
    private static final Set<Identifier> DISABLED = ConcurrentHashMap.newKeySet();
    private static volatile String namespace;
    private static volatile Map<Identifier, Identifier> toLayer;
    private static volatile Map<Identifier, Identifier> fromLayer;

    private LegacyGuiLayers() {}

    private static Map<String, List<String>> layerFields() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (String camera : List.of("vignette", "spyglass", "helmet", "frostbite", "portal")) {
            fields.put(camera, List.of("CAMERA_OVERLAYS"));
        }
        fields.put("hotbar", List.of("HOTBAR"));
        fields.put("crosshair", List.of("CROSSHAIR"));
        fields.put("boss_event_progress", List.of("BOSS_OVERLAY"));
        fields.put("player_health", List.of("PLAYER_HEALTH"));
        fields.put("armor_level", List.of("ARMOR_LEVEL"));
        fields.put("food_level", List.of("FOOD_LEVEL"));
        fields.put("air_level", List.of("AIR_LEVEL"));
        fields.put("mount_health", List.of("VEHICLE_HEALTH"));
        // 26.x merged the experience bar and the jump meter into one contextual bar, which reports the experience bar.
        fields.put("experience_bar", List.of("CONTEXTUAL_INFO_BAR", "EXPERIENCE_BAR"));
        fields.put("jump_bar", List.of("CONTEXTUAL_INFO_BAR", "JUMP_METER"));
        fields.put("item_name", List.of("SELECTED_ITEM_NAME"));
        fields.put("sleep_fade", List.of("SLEEP_OVERLAY"));
        fields.put("potion_icons", List.of("EFFECTS"));
        fields.put("debug_text", List.of("DEBUG_OVERLAY"));
        fields.put("fps_graph", List.of());
        fields.put("record_overlay", List.of("OVERLAY_MESSAGE"));
        fields.put("title_text", List.of("TITLE"));
        fields.put("subtitles", List.of("SUBTITLE_OVERLAY"));
        fields.put("scoreboard", List.of("SCOREBOARD_SIDEBAR"));
        fields.put("chat_panel", List.of("CHAT"));
        fields.put("player_list", List.of("TAB_LIST"));
        return fields;
    }

    /** Called first in each old {@code @Mod} constructor of a jar that uses the old overlay API: the mod names its overlays. */
    public static void attach(String modId) {
        if (namespace == null) namespace = modId;
    }

    public static void registerBelowAll(RegisterGuiLayersEvent event, String path, IGuiOverlay overlay) {
        Identifier id = own(path, overlay);
        event.registerBelowAll(id, layer(id, overlay));
    }

    public static void registerAboveAll(RegisterGuiLayersEvent event, String path, IGuiOverlay overlay) {
        Identifier id = own(path, overlay);
        event.registerAboveAll(id, layer(id, overlay));
    }

    public static void registerBelow(RegisterGuiLayersEvent event, Identifier other, String path, IGuiOverlay overlay) {
        Identifier id = own(path, overlay);
        Identifier anchor = anchor(other);
        // An old vanilla overlay with no layer of its own, the debug text or graph, drew near the top.
        if (anchor == null) event.registerAboveAll(id, layer(id, overlay));
        else event.registerBelow(anchor, id, layer(id, overlay));
    }

    public static void registerAbove(RegisterGuiLayersEvent event, Identifier other, String path, IGuiOverlay overlay) {
        Identifier id = own(path, overlay);
        Identifier anchor = anchor(other);
        if (anchor == null) event.registerAboveAll(id, layer(id, overlay));
        else event.registerAbove(anchor, id, layer(id, overlay));
    }

    /** {@code getOverlay()}: this mod's overlay object for the layer, under its old vanilla id where it had one. */
    public static NamedGuiOverlay getOverlay(RenderGuiLayerEvent event) {
        Identifier name = event.getName();
        Identifier old = fromLayer().get(name);
        return NamedGuiOverlay.of(old != null ? old : name);
    }

    /** {@code getPartialTick()}: the frame's partial tick as a float, as Forge 1.20.1 reported it. */
    public static float getPartialTick(RenderGuiLayerEvent event) {
        return event.getPartialTick().getGameTimeDeltaPartialTick(false);
    }

    public static Window getWindow(RenderGuiLayerEvent event) {
        return Minecraft.getInstance().getWindow();
    }

    static IGuiOverlay ownOverlay(Identifier id) {
        return OWN.get(id);
    }

    static boolean isVanilla(Identifier id) {
        return id.getNamespace().equals("minecraft") && LAYER_FIELDS.containsKey(id.getPath());
    }

    // The old id the mod's namespace gives a path, recorded with its overlay.
    private static Identifier own(String path, IGuiOverlay overlay) {
        String owner = namespace != null ? namespace : activeNamespace();
        Identifier id = Identifier.fromNamespaceAndPath(owner, path);
        OWN.putIfAbsent(id, overlay);
        return id;
    }

    // The NeoForge layer an old anchor stands for; another mod's overlay keeps its id, and null means no layer.
    private static Identifier anchor(Identifier other) {
        if (!isVanilla(other)) return other;
        return toLayer().get(other);
    }

    // A jar whose constructor hook did not run asks the loader for the mod in progress, as Forge 1.20.1 did.
    private static String activeNamespace() {
        try {
            String active = ModLoadingContext.get().getActiveNamespace();
            if (active != null) return active;
        } catch (RuntimeException | LinkageError unavailable) {
            // Outside mod loading there is no active mod.
        }
        return "minecraft";
    }

    private static GuiLayer layer(Identifier id, IGuiOverlay overlay) {
        return (graphics, delta) -> {
            if (DISABLED.contains(id)) return;
            ForgeGui gui = ForgeGui.cast(Minecraft.getInstance().gui);
            try {
                overlay.render(gui, graphics, delta.getGameTimeDeltaPartialTick(false), graphics.guiWidth(), graphics.guiHeight());
            } catch (LinkageError missing) {
                // Every frame would fail the same way, so the overlay is left out from now on.
                DISABLED.add(id);
                LegacyLog.warn("Retromod stopped drawing the HUD overlay " + id + ", which uses " + missing
                        + ". The rest of the HUD and the mod keep running. Report this line with the mod and its version.");
            } catch (RuntimeException failure) {
                // Forge 1.20.1 logged a failing overlay and went on with the rest of the HUD.
                LegacyLog.warnOnce("neoforge hud overlay " + id, "The HUD overlay " + id + " threw while drawing: " + failure
                        + ". Retromod went on with the rest of the HUD");
            } finally {
                ForgeGui.pushHeights();
            }
        };
    }

    private static Map<Identifier, Identifier> toLayer() {
        if (toLayer == null) readLayers();
        return toLayer;
    }

    private static Map<Identifier, Identifier> fromLayer() {
        if (fromLayer == null) readLayers();
        return fromLayer;
    }

    // NeoForge's layer ids are read by field name, since the fields changed between hosts.
    private static synchronized void readLayers() {
        if (toLayer != null && fromLayer != null) return;
        Map<Identifier, Identifier> to = new LinkedHashMap<>();
        Map<Identifier, Identifier> from = new LinkedHashMap<>();
        try {
            Class<?> layers = Class.forName(VANILLA_LAYERS, true, LegacyGuiLayers.class.getClassLoader());
            for (Map.Entry<String, List<String>> entry : LAYER_FIELDS.entrySet()) {
                Identifier layer = firstLayer(layers, entry.getValue());
                if (layer == null) continue;
                Identifier old = Identifier.withDefaultNamespace(entry.getKey());
                to.put(old, layer);
                // A layer several old overlays stand for reports the first of them, in the old drawing order.
                from.putIfAbsent(layer, old);
            }
        } catch (ReflectiveOperationException | LinkageError missing) {
            LegacyLog.warnOnce("neoforge vanilla layers", "Retromod could not read NeoForge's vanilla GUI layers, so old overlays"
                    + " ordered against vanilla ones draw above the HUD: " + missing);
        }
        fromLayer = from;
        toLayer = to;
    }

    private static Identifier firstLayer(Class<?> layers, List<String> names) {
        for (String name : names) {
            try {
                Field field = layers.getField(name);
                if (field.get(null) instanceof Identifier id) return id;
            } catch (ReflectiveOperationException absent) {
                // Not on this host; the next name may be.
            }
        }
        return null;
    }
}
