/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.forge.client.event.RegisterGuiOverlaysEvent;
import com.retromod.legacyapi.forge.client.event.RenderGuiEvent;
import com.retromod.legacyapi.forge.client.event.RenderGuiOverlayEvent;
import com.retromod.legacyapi.forge.client.gui.overlay.IGuiOverlay;
import com.retromod.legacyapi.forge.client.gui.overlay.VanillaGuiOverlay;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeLayer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** This mod's copy of the old HUD overlay API as the HUD coordinator reaches it, through slots every copy shares. */
public final class LegacyOverlayHub {

    // Each translated jar carries its own relocated classes, so the slots hold only JDK and host types.
    /** The slots' layout; a copy from a Retromod build with another layout keeps slots of its own. */
    public static final int PROTOCOL = 1;
    /** {@code Map<String, Function<Object[], Object>>}: each mod's endpoint by mod id, listed while the mod constructs. */
    public static final String ENDPOINTS = "forge.hud.endpoints";
    /** {@code List<Object[]>}: every overlay in the old drawing order, each {@link #ID}, {@link #DRAWER}, {@link #OWNER}. */
    public static final String ORDER = "forge.hud.order";
    /** {@code int[]}: the left and right heights every mod's ForgeGui reads before and writes back after it runs. */
    public static final String HEIGHTS = "forge.hud.heights";
    /** {@code Map<Identifier, ForgeLayer>}: the host layers standing for each old vanilla id, filled by the coordinator. */
    public static final String VANILLA_LAYERS = "forge.hud.vanilla";
    /** {@code AtomicBoolean}: set by the first copy on a client, which alone loads and runs the HUD coordinator. */
    public static final String COORDINATOR = "forge.hud.coordinator";

    /** An order entry's Identifier. */
    public static final int ID = 0;
    /** An order entry's ForgeLayer, which draws a mod's overlay with no events around it; null for a vanilla id. */
    public static final int DRAWER = 1;
    /** An order entry's owner: the namespace that registered it, or "minecraft". */
    public static final int OWNER = 2;

    /** The Forge 26.2 and 26.3 layers each old vanilla overlay stands for, by old path, in draw order; none for the debug text and graph. */
    public static final Map<String, List<String>> HOST_LAYERS = Map.ofEntries(
            Map.entry("vignette", List.of("camera_overlay")),
            Map.entry("spyglass", List.of("camera_overlay")),
            Map.entry("helmet", List.of("camera_overlay")),
            Map.entry("frostbite", List.of("camera_overlay")),
            Map.entry("portal", List.of("camera_overlay")),
            Map.entry("hotbar", List.of("spectator_hotbar", "item_hotbar")),
            Map.entry("crosshair", List.of("crosshair")),
            Map.entry("boss_event_progress", List.of("boss_overlay")),
            Map.entry("player_health", List.of("health_bar")),
            Map.entry("armor_level", List.of("health_bar")),
            Map.entry("food_level", List.of("health_bar")),
            Map.entry("air_level", List.of("health_bar")),
            Map.entry("mount_health", List.of("vehicle_health")),
            Map.entry("jump_bar", List.of("background", "experience_level", "contextual_info")),
            Map.entry("experience_bar", List.of("background", "experience_level", "contextual_info")),
            Map.entry("item_name", List.of("selected_item_name", "spectator_action")),
            Map.entry("sleep_fade", List.of("sleep_overlay")),
            Map.entry("potion_icons", List.of("potion_effects")),
            Map.entry("debug_text", List.of()),
            Map.entry("fps_graph", List.of()),
            Map.entry("record_overlay", List.of("hotbar_message")),
            Map.entry("title_text", List.of("title")),
            Map.entry("subtitles", List.of("subtitle")),
            Map.entry("scoreboard", List.of("scoreboard")),
            Map.entry("chat_panel", List.of("chat_overlay")),
            Map.entry("player_list", List.of("tab_list")));

    /** The stack each of those layers sits in; a second subtitle layer in the root stack draws only for a hidden HUD. */
    public static final Map<String, String> HOST_STACKS = Map.ofEntries(
            Map.entry("camera_overlay", "pre_sleep_phase"), Map.entry("crosshair", "pre_sleep_phase"),
            Map.entry("spectator_hotbar", "hotbar"), Map.entry("item_hotbar", "hotbar"), Map.entry("health_bar", "hotbar"),
            Map.entry("vehicle_health", "hotbar"), Map.entry("background", "hotbar"), Map.entry("experience_level", "hotbar"),
            Map.entry("contextual_info", "hotbar"), Map.entry("selected_item_name", "hotbar"), Map.entry("spectator_action", "hotbar"),
            Map.entry("potion_effects", "pre_sleep_phase"), Map.entry("boss_overlay", "pre_sleep_phase"),
            Map.entry("sleep_overlay", "vanilla_root"), Map.entry("hotbar_message", "post_sleep_phase"),
            Map.entry("title", "post_sleep_phase"), Map.entry("subtitle", "post_sleep_phase"),
            Map.entry("scoreboard", "post_sleep_phase"), Map.entry("chat_overlay", "post_sleep_phase"),
            Map.entry("tab_list", "post_sleep_phase"));

    /** Those layers in the order Forge 26.2 and 26.3 draw them. */
    public static final List<String> HOST_DRAW_ORDER = List.of("camera_overlay", "crosshair", "spectator_hotbar",
            "item_hotbar", "health_bar", "vehicle_health", "background", "experience_level", "contextual_info",
            "selected_item_name", "spectator_action", "potion_effects", "boss_overlay", "sleep_overlay", "scoreboard",
            "hotbar_message", "title", "chat_overlay", "tab_list", "subtitle");

    private static final Function<Object[], Object> ENDPOINT = LegacyOverlayHub::handle;
    // The overlays this mod's copy registered, by their full id.
    private static final Map<Identifier, IGuiOverlay> OWN = new ConcurrentHashMap<>();
    private static volatile List<Object[]> order;
    private static volatile int[] heights;

    private LegacyOverlayHub() {}

    /** Called first in each old {@code @Mod} constructor of a jar that uses the old overlay API: lists this copy under the mod. */
    public static void attach() {
        String copy = LegacyOverlayHub.class.getName();
        try {
            endpoints().putIfAbsent(ModLoadingContext.get().getContainer().getModId(), ENDPOINT);
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("hud endpoint " + copy, "Retromod did not list the old HUD overlays of " + copy
                    + " for the HUD coordinator: " + failure);
        }
        // The first copy on a client runs the one coordinator; a dedicated server loads none of its client classes,
        // and the other copies never load theirs.
        if (!onClient()) return;
        if (!LegacySharedSlots.get(COORDINATOR, PROTOCOL, AtomicBoolean.class, AtomicBoolean::new).compareAndSet(false, true)) return;
        try {
            LegacyHudCoordinator.start();
        } catch (LinkageError failure) {
            // The mod still constructs; only the old overlays go undrawn.
            LegacyLog.warnOnce("hud coordinator " + copy, "Retromod could not start the HUD coordinator in " + copy
                    + ", so old HUD overlays are not drawn: " + failure);
        }
    }

    private static boolean onClient() {
        try {
            return FMLEnvironment.dist == Dist.CLIENT;
        } catch (LinkageError noLoader) {
            return false;
        }
    }

    /** One coordinator request, name first: register, pre, post, guiPre, guiPost, listens, hostLayers or protocol; a pre answers whether it ends cancelled. */
    static Object handle(Object[] request) {
        return switch ((String) request[0]) {
            case "register" -> RegisterGuiOverlaysEvent.postTo(request[1], (String) request[2]);
            case "pre" -> RenderGuiOverlayEvent.postFor(true, request);
            case "post" -> RenderGuiOverlayEvent.postFor(false, request);
            case "guiPre" -> RenderGuiEvent.postFor(true, request);
            case "guiPost" -> RenderGuiEvent.postFor(false, request);
            case "listens" -> RenderGuiOverlayEvent.hasListeners() || RenderGuiEvent.hasListeners();
            case "hostLayers" -> HOST_LAYERS;
            case "protocol" -> PROTOCOL;
            default -> throw new IllegalArgumentException("Unknown HUD overlay request " + request[0]);
        };
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Function<Object[], Object>> endpoints() {
        return LegacySharedSlots.get(ENDPOINTS, PROTOCOL, Map.class, ConcurrentHashMap::new);
    }

    /** The shared overlay order, which starts with the 26 vanilla ids as Forge 1.20.1's did. */
    @SuppressWarnings("unchecked")
    public static List<Object[]> order() {
        List<Object[]> shared = order;
        if (shared == null) {
            shared = LegacySharedSlots.get(ORDER, PROTOCOL, List.class, LegacyOverlayHub::vanillaOrder);
            order = shared;
        }
        return shared;
    }

    /** The shared {leftHeight, rightHeight}. */
    public static int[] heights() {
        int[] shared = heights;
        if (shared == null) {
            shared = LegacySharedSlots.get(HEIGHTS, PROTOCOL, int[].class, () -> new int[]{39, 39});
            heights = shared;
        }
        return shared;
    }

    @SuppressWarnings("unchecked")
    public static Map<Identifier, ForgeLayer> vanillaLayers() {
        return LegacySharedSlots.get(VANILLA_LAYERS, PROTOCOL, Map.class, ConcurrentHashMap::new);
    }

    private static List<Object[]> vanillaOrder() {
        List<Object[]> seeded = new CopyOnWriteArrayList<>();
        for (VanillaGuiOverlay overlay : VanillaGuiOverlay.values()) seeded.add(new Object[]{overlay.id(), null, "minecraft"});
        return seeded;
    }

    /** Adds an overlay this mod's copy registered; the order entry is the caller's. */
    public static void addOwn(Identifier id, IGuiOverlay overlay) {
        OWN.put(id, overlay);
    }

    /** The overlay this mod's copy registered under {@code id}, or null. */
    public static IGuiOverlay ownOverlay(Identifier id) {
        return OWN.get(id);
    }

    /** Every id in the shared order. */
    public static List<Identifier> orderedIds() {
        List<Identifier> ids = new ArrayList<>();
        for (Object[] entry : order()) ids.add((Identifier) entry[ID]);
        return ids;
    }

    /** Whether {@code id} is a vanilla overlay or one any mod registered. */
    public static boolean isKnown(Identifier id) {
        return indexOf(order(), id) >= 0;
    }

    /** The position of {@code id} in {@code entries}, or -1. */
    public static int indexOf(List<Object[]> entries, Identifier id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i)[ID].equals(id)) return i;
        }
        return -1;
    }

    /** What draws {@code id} for a mod that did not register it: the host layer for a vanilla id, or the owner's drawer. */
    public static ForgeLayer sharedLayer(Identifier id) {
        ForgeLayer vanilla = vanillaLayers().get(id);
        if (vanilla != null) return vanilla;
        List<Object[]> entries = order();
        int index = indexOf(entries, id);
        return index < 0 ? null : (ForgeLayer) entries.get(index)[DRAWER];
    }
}
