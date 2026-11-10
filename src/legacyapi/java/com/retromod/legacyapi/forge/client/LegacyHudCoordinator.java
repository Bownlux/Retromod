/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.mojang.blaze3d.platform.Window;
import com.retromod.legacyapi.forge.client.gui.overlay.VanillaGuiOverlay;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayer;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;

/** Draws every mod's old HUD overlays and posts the old HUD events inside Forge 26.2's layers, as Forge 1.20.1's ForgeGui did. */
public final class LegacyHudCoordinator {

    // The host stacks Retromod adds its three slots to, by ForgeLayeredDraw's ids.
    private static final String ROOT_STACK = "vanilla_root";
    private static final String PRE_SLEEP = "pre_sleep_phase";
    private static final String POST_SLEEP = "post_sleep_phase";

    private static final Identifier JUMP_BAR = VanillaGuiOverlay.JUMP_BAR.id();
    private static final Identifier EXPERIENCE_BAR = VanillaGuiOverlay.EXPERIENCE_BAR.id();

    // Overlays drawn before the first vanilla one, after the debug text, and above all.
    private static final Station BOTTOM = new Station(List.of());
    private static final Station TOP = new Station(List.of());
    private static final Map<Identifier, Station> BY_ID = new HashMap<>();
    private static final List<Station> STATIONS = stations();
    private static final List<Station> DRAW_ORDER = drawOrder();

    private static final Set<String> NOTED = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> BROKEN = ConcurrentHashMap.newKeySet();

    private static volatile ForgeLayeredDraw built;
    private static volatile List<Copy> copies = List.of();
    private static volatile int planned = -1;
    private static volatile boolean modOverlays;

    // The frame being drawn, touched only on the render thread.
    private static int frame;
    private static boolean active;
    private static boolean hudCancelled;
    private static Window window;
    private static Float partialTick;
    private static List<Copy> listening = List.of();
    private static LegacyHudHeights.Status status;

    private LegacyHudCoordinator() {}

    /** One mod's copy of the rebuilt API, reached through its endpoint. */
    private record Copy(String modId, Function<Object[], Object> endpoint) {}

    /** Old vanilla overlays a run of neighboring host layers draws, and the mod overlays Forge 1.20.1 drew after them. */
    private static final class Station {
        final List<String> layers;
        final List<Identifier> vanilla = new ArrayList<>();
        final ForgeLayer[] originals;
        final ForgeLayer[] wrappers;
        volatile List<Object[]> sequence = List.of();
        int frame = -1;
        int postsFrame = -1;
        final Set<Identifier> cancelled = new HashSet<>();
        final Map<Identifier, int[]> after = new HashMap<>();
        int[] start = {39, 39};
        boolean hidden;

        Station(List<String> layers) {
            this.layers = layers;
            this.originals = new ForgeLayer[layers.size()];
            this.wrappers = new ForgeLayer[layers.size()];
        }
    }

    // Old overlays standing for the same host layers share a station, in the old drawing order.
    private static List<Station> stations() {
        Map<List<String>, Station> byLayers = new LinkedHashMap<>();
        for (VanillaGuiOverlay overlay : VanillaGuiOverlay.values()) {
            List<String> layers = LegacyOverlayHub.HOST_LAYERS.get(overlay.id().getPath());
            Station station = byLayers.computeIfAbsent(layers, Station::new);
            station.vanilla.add(overlay.id());
            BY_ID.put(overlay.id(), station);
        }
        return List.copyOf(byLayers.values());
    }

    // The stations as Forge draws their layers, with the debug text's after the sleep fade, where its slot is.
    private static List<Station> drawOrder() {
        List<Station> ordered = new ArrayList<>();
        for (String layer : LegacyOverlayHub.HOST_DRAW_ORDER) {
            for (Station station : STATIONS) {
                if (station.layers.contains(layer) && !ordered.contains(station)) ordered.add(station);
            }
            if (layer.equals("sleep_overlay")) ordered.add(BY_ID.get(VanillaGuiOverlay.DEBUG_TEXT.id()));
        }
        return List.copyOf(ordered);
    }

    /** Starts the coordinator for every mod's copy; the hub calls it from the one copy that claimed it, on a client. */
    public static void start() {
        try {
            // The Hud is built after every mod constructs, and the key mappings come once Minecraft.gui exists.
            AddGuiOverlayLayersEvent.BUS.addListener(Priority.LOW, LegacyHudCoordinator::buildLayers);
            RegisterKeyMappingsEvent.BUS.addListener(Priority.LOWEST, event -> registerOverlays());
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("hud coordinator", "Retromod could not reach Forge's HUD layer events, so old HUD overlays "
                    + "are not drawn: " + failure);
        }
    }

    /** While the Hud is built: wraps the host layers old overlays stand for and adds Retromod's three slots. */
    private static void buildLayers(AddGuiOverlayLayersEvent event) {
        // A failure here must never reach the Hud's constructor.
        try {
            ForgeLayeredDraw root = event.getLayeredDraw();
            if (root == null || root == built) return;
            built = root;
            Map<Identifier, ForgeLayer> vanillaLayers = LegacyOverlayHub.vanillaLayers();
            for (Station station : STATIONS) {
                for (int i = 0; i < station.layers.size(); i++) wrap(root, station, i);
                // Another mod's NamedGuiOverlay.overlay() for one of these ids draws the host layers as they were.
                ForgeLayer plain = (graphics, delta) -> {
                    for (ForgeLayer original : station.originals) {
                        if (original != null) original.extract(graphics, delta);
                    }
                };
                for (Identifier id : station.vanilla) vanillaLayers.put(id, plain);
            }
            // Forge's order is the stack, the new layer, then the layer it goes next to.
            root.addBelow(host(PRE_SLEEP), own("legacy/bottom"), host("camera_overlay"), LegacyHudCoordinator::startFrame);
            root.addAbove(host(ROOT_STACK), own("legacy/debug"), host("sleep_overlay"), LegacyHudCoordinator::drawDebug);
            root.add(host(POST_SLEEP), own("legacy/top"), LegacyHudCoordinator::endFrame);
            plan();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("hud build", "Retromod could not place the old HUD overlays among Forge's HUD layers, so "
                    + "they are not drawn: " + failure);
        }
    }

    // The host layer stays in its place and its own condition; the wrapper adds the old events around it.
    private static void wrap(ForgeLayeredDraw root, Station station, int index) {
        String path = station.layers.get(index);
        Identifier stack = host(LegacyOverlayHub.HOST_STACKS.get(path));
        ForgeLayeredDraw holder = root.getChild(stack);
        ForgeLayer original = holder == null ? null : holder.getLayer(host(path));
        if (original == null) {
            LegacyLog.warnOnce("hud layer " + path, "Retromod found no HUD layer minecraft:" + path
                    + ", so the old overlay events around it are not posted.");
            return;
        }
        station.originals[index] = original;
        ForgeLayer wrapper = (graphics, delta) -> drawLayer(station, index, original, graphics, delta);
        station.wrappers[index] = wrapper;
        root.replace(stack, host(path), wrapper);
    }

    /** Where Forge 1.20.1 called GuiOverlayManager.init: posts each mod's RegisterGuiOverlaysEvent in mod order. */
    private static void registerOverlays() {
        // A failure here must never reach Forge's client setup.
        try {
            register();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("hud register", "Retromod could not post the old RegisterGuiOverlaysEvent, so old HUD "
                    + "overlays are not drawn: " + failure);
        }
    }

    private static void register() {
        // Forge 1.20.1 posted nothing to mods once loading had failed.
        if (!ModLoader.isLoadingStateValid()) return;
        Map<String, Function<Object[], Object>> endpoints = LegacyOverlayHub.endpoints();
        List<Copy> found = new ArrayList<>();
        Set<Function<Object[], Object>> seen = new HashSet<>();
        ModList.forEachModInOrder(container -> {
            Function<Object[], Object> endpoint = endpoints.get(container.getModId());
            if (endpoint == null) return;
            // A jar with two mods has one copy, which every frame reaches once.
            if (seen.add(endpoint)) found.add(new Copy(container.getModId(), endpoint));
            try {
                endpoint.apply(new Object[]{"register", container.getModBusGroup(), container.getNamespace()});
            } catch (RuntimeException | LinkageError failure) {
                LegacyLog.warn("The RegisterGuiOverlaysEvent listener of " + container.getModId() + " threw: " + failure
                        + ". Retromod kept the overlays it registered before that and went on with the other mods.");
            }
        });
        copies = List.copyOf(found);
        if (built == null) {
            LegacyLog.warnOnce("hud unbuilt", "Retromod found no Forge HUD layers to draw the old HUD overlays into.");
        } else {
            noteReplacedWrappers(built);
        }
        plan();
    }

    // A native mod's later replace or condition on a wrapped layer can keep the wrapper from running; the next
    // station then runs that station's events and overlays.
    private static void noteReplacedWrappers(ForgeLayeredDraw root) {
        for (Station station : STATIONS) {
            for (int i = 0; i < station.layers.size(); i++) {
                if (station.wrappers[i] == null) continue;
                String path = station.layers.get(i);
                ForgeLayeredDraw holder = root.getChild(host(LegacyOverlayHub.HOST_STACKS.get(path)));
                if (holder != null && holder.getLayer(host(path)) == station.wrappers[i]) continue;
                LegacyLog.debug("Another mod replaced the HUD layer minecraft:" + path + " or put it behind a condition after "
                        + "Retromod wrapped it, so the old overlay events for it run with the next layer when it does not draw.");
            }
        }
    }

    // Each mod overlay goes after the nearest vanilla overlay before it in the shared order, where Forge 1.20.1 drew it.
    private static void plan() {
        List<Object[]> order = List.copyOf(LegacyOverlayHub.order());
        int lastVanilla = -1;
        for (int i = 0; i < order.size(); i++) {
            if (isVanilla(order.get(i))) lastVanilla = i;
        }
        Map<Station, List<Object[]>> sequences = new IdentityHashMap<>();
        Station current = BOTTOM;
        for (int i = 0; i < order.size(); i++) {
            Object[] entry = order.get(i);
            if (isVanilla(entry)) current = BY_ID.get((Identifier) entry[LegacyOverlayHub.ID]);
            // Above every vanilla overlay means above all, after the subtitles and the player list.
            Station into = i > lastVanilla ? TOP : current;
            sequences.computeIfAbsent(into, any -> new ArrayList<>()).add(entry);
        }
        for (Station station : allStations()) station.sequence = List.copyOf(sequences.getOrDefault(station, List.of()));
        modOverlays = order.stream().anyMatch(entry -> !isVanilla(entry));
        planned = order.size();
    }

    private static List<Station> allStations() {
        List<Station> all = new ArrayList<>(STATIONS);
        all.add(BOTTOM);
        all.add(TOP);
        return all;
    }

    private static boolean isVanilla(Object[] entry) {
        return entry[LegacyOverlayHub.DRAWER] == null && BY_ID.containsKey(entry[LegacyOverlayHub.ID]);
    }

    // ---- one frame ----

    // The first layer: Forge 1.20.1 reset both heights to 39 and posted RenderGuiEvent.Pre before any overlay.
    private static void startFrame(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (planned != LegacyOverlayHub.order().size()) plan();
        listening = listeningCopies();
        // With no old listener and no old overlay, the host layers draw as they are and nothing else runs.
        active = !listening.isEmpty() || modOverlays;
        if (!active) return;
        frame++;
        status = null;
        window = Minecraft.getInstance().getWindow();
        partialTick = delta.getGameTimeDeltaPartialTick(false);
        setHeights(new int[]{39, 39});
        hudCancelled = post("guiPre", null, graphics);
        if (!hudCancelled) runStation(BOTTOM, graphics, delta);
    }

    // The debug text and frame graph have no layer, so their events and the overlays after them go after the sleep fade.
    private static void drawDebug(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (!active || hudCancelled) return;
        Station debug = BY_ID.get(VanillaGuiOverlay.DEBUG_TEXT.id());
        catchUp(debug, graphics, delta);
        runStation(debug, graphics, delta);
    }

    // The last layer: the overlays above all, then RenderGuiEvent.Post, which a cancelled Pre skipped.
    private static void endFrame(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (!active) return;
        active = false;
        if (hudCancelled) return;
        catchUp(TOP, graphics, delta);
        runStation(TOP, graphics, delta);
        post("guiPost", null, graphics);
    }

    // A wrapped host layer. Outside a HUD frame, as with the HUD hidden, it only draws; the first layer of its
    // station posts the station's Pres, and the last posts the Posts and draws the mod overlays after them.
    private static void drawLayer(Station station, int index, ForgeLayer original, GuiGraphicsExtractor graphics,
            DeltaTracker delta) {
        if (!active) {
            original.extract(graphics, delta);
            return;
        }
        if (hudCancelled) return;
        if (station.frame != frame) {
            catchUp(station, graphics, delta);
            runPres(station, graphics);
        }
        if (!station.hidden) original.extract(graphics, delta);
        if (index == station.layers.size() - 1) runPosts(station, graphics, delta);
    }

    // Stations drawn before next whose wrapper did not run this frame, because another mod replaced their layer or
    // put it behind a condition, run here, so their events and the old overlays after them still come.
    private static void catchUp(Station next, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        for (Station station : DRAW_ORDER) {
            if (station == next) return;
            if (station.frame != frame) runPres(station, graphics);
            if (station.postsFrame != frame) runPosts(station, graphics, delta);
        }
    }

    private static void runStation(Station station, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        runPres(station, graphics);
        runPosts(station, graphics, delta);
    }

    // Every Pre before the host layers draw, each with the heights its old bar saw, so a cancel can still hide them.
    private static void runPres(Station station, GuiGraphicsExtractor graphics) {
        station.frame = frame;
        station.cancelled.clear();
        station.after.clear();
        int[] running = heights().clone();
        station.start = running.clone();
        for (Identifier id : station.vanilla) {
            setHeights(running);
            boolean cancelled = post("pre", id, graphics);
            running = heights().clone();
            if (cancelled) {
                station.cancelled.add(id);
            } else {
                int[] rise = LegacyHudHeights.rise(id.getPath(), status());
                running[0] += rise[0];
                running[1] += rise[1];
            }
            station.after.put(id, running.clone());
        }
        setHeights(running);
        List<Identifier> shown = shown(station);
        station.hidden = !shown.isEmpty() && station.cancelled.containsAll(shown);
        boolean partly = shown.stream().anyMatch(station.cancelled::contains);
        if (partly && !station.hidden && !station.layers.isEmpty()) notePartialCancel(station, shown);
    }

    // Then each Post with the heights after its bar, and each mod overlay in its old place, carrying what they change.
    private static void runPosts(Station station, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        station.postsFrame = frame;
        int[] base = station.start;
        int[] offset = {0, 0};
        for (Object[] entry : station.sequence) {
            boolean vanilla = isVanilla(entry);
            Identifier id = (Identifier) entry[LegacyOverlayHub.ID];
            if (vanilla) {
                int[] after = station.after.get(id);
                if (after == null) continue;
                base = after;
                if (station.cancelled.contains(id)) continue;
            }
            setHeights(new int[]{base[0] + offset[0], base[1] + offset[1]});
            if (vanilla) {
                post("post", id, graphics);
            } else {
                drawEntry(entry, graphics, delta);
            }
            int[] now = heights();
            offset = new int[]{now[0] - base[0], now[1] - base[1]};
        }
        setHeights(new int[]{base[0] + offset[0], base[1] + offset[1]});
    }

    // The ids whose drawing the station's layers stand for this frame. Forge 1.20.1 posted both bar events every
    // frame but drew only the jump bar while riding a jumping mount and the experience bar otherwise, so only the
    // shown bar's cancel can hide the layers.
    private static List<Identifier> shown(Station station) {
        if (!station.vanilla.contains(JUMP_BAR)) return station.vanilla;
        Minecraft minecraft = Minecraft.getInstance();
        boolean jumping = minecraft.player != null && minecraft.player.jumpableVehicle() != null;
        return List.of(jumping ? JUMP_BAR : EXPERIENCE_BAR);
    }

    // A mod's overlay: its Pre in every mod, then its drawing unless cancelled, then its Post.
    private static void drawEntry(Object[] entry, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Identifier id = (Identifier) entry[LegacyOverlayHub.ID];
        if (post("pre", id, graphics)) return;
        if (entry[LegacyOverlayHub.DRAWER] instanceof ForgeLayer drawer && !BROKEN.contains(id)) {
            try {
                drawer.extract(graphics, delta);
            } catch (LinkageError missing) {
                // Every frame would fail the same way.
                BROKEN.add(id);
                LegacyLog.warn("Retromod stopped drawing the HUD overlay " + id + " of " + entry[LegacyOverlayHub.OWNER]
                        + ", which uses " + missing + ". The rest of the HUD keeps drawing. Report this line with the mod and its version.");
            } catch (RuntimeException failure) {
                LegacyLog.warnOnce("hud overlay " + id, "The HUD overlay " + id + " threw while drawing: " + failure
                        + ". Retromod went on with the rest of the HUD.");
            }
        }
        post("post", id, graphics);
    }

    /**
     * One request to every listening mod's copy, in mod order. A Pre carries the cancel along, so a later mod's
     * receiveCanceled listener sees it and can undo it, as on Forge 1.20.1's single bus; the answer is the final state.
     */
    private static boolean post(String name, Identifier id, GuiGraphicsExtractor graphics) {
        boolean pre = name.equals("pre") || name.equals("guiPre");
        boolean cancelled = false;
        for (Copy copy : listening) {
            Object[] request = request(name, id, graphics, pre, cancelled);
            try {
                Object answer = copy.endpoint().apply(request);
                if (pre) cancelled = Boolean.TRUE.equals(answer);
            } catch (RuntimeException | LinkageError failure) {
                // A listener that throws neither cancels nor uncancels, and the HUD goes on.
                LegacyLog.warnOnce("hud listener " + copy.modId() + " " + name, "A " + eventName(name) + " listener of "
                        + copy.modId() + " threw: " + failure + ". Retromod treated that post as not cancelled and went on "
                        + "drawing the HUD. Report this line with the mod and its version.");
            }
        }
        return cancelled;
    }

    private static Object[] request(String name, Identifier id, GuiGraphicsExtractor graphics, boolean pre, boolean cancelled) {
        if (id == null) {
            return pre ? new Object[]{name, window, graphics, partialTick, cancelled} : new Object[]{name, window, graphics, partialTick};
        }
        return pre ? new Object[]{name, id, window, graphics, partialTick, cancelled} : new Object[]{name, id, window, graphics, partialTick};
    }

    private static String eventName(String name) {
        return switch (name) {
            case "pre" -> "RenderGuiOverlayEvent.Pre";
            case "post" -> "RenderGuiOverlayEvent.Post";
            case "guiPre" -> "RenderGuiEvent.Pre";
            default -> "RenderGuiEvent.Post";
        };
    }

    // Copies with no HUD listener are passed by for the whole frame.
    private static List<Copy> listeningCopies() {
        List<Copy> found = new ArrayList<>();
        for (Copy copy : copies) {
            try {
                if (Boolean.TRUE.equals(copy.endpoint().apply(new Object[]{"listens"}))) found.add(copy);
            } catch (RuntimeException | LinkageError failure) {
                LegacyLog.warnOnce("hud listens " + copy.modId(), "Retromod could not ask " + copy.modId()
                        + " for its HUD listeners, so they are not called: " + failure);
            }
        }
        return found;
    }

    // Forge draws some old overlays in one layer, so a cancel of part of them cannot hide only that part.
    private static void notePartialCancel(Station station, List<Identifier> shown) {
        String key = station.layers + " " + station.cancelled;
        if (!NOTED.add(key)) return;
        LegacyLog.debug("Retromod still drew the HUD layers " + String.join(", ", station.layers) + " although a mod cancelled "
                + station.cancelled + ", because Forge draws " + shown + " in them together.");
    }

    // What the old bars depended on, read from the camera player as Forge 1.20.1's ForgeGui read it, once a frame.
    private static LegacyHudHeights.Status status() {
        if (status == null) status = readStatus();
        return status;
    }

    private static LegacyHudHeights.Status readStatus() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || !minecraft.gameMode.canHurtPlayer()
                || !(minecraft.getCameraEntity() instanceof Player player)) {
            return LegacyHudHeights.Status.NONE;
        }
        float health = (float) Math.ceil(player.getHealth());
        Entity vehicle = player.getVehicle();
        float mountMaxHealth = vehicle instanceof LivingEntity living ? living.getMaxHealth() : 0;
        return new LegacyHudHeights.Status(true, Math.max(player.getMaxHealth(), health), player.getAbsorptionAmount(),
                vehicle != null && vehicle.showVehicleHealth(), mountMaxHealth, player.isUnderWater(),
                player.getAirSupply(), player.getMaxAirSupply());
    }

    private static int[] heights() {
        return LegacyOverlayHub.heights();
    }

    private static void setHeights(int[] values) {
        int[] shared = LegacyOverlayHub.heights();
        shared[0] = values[0];
        shared[1] = values[1];
    }

    private static Identifier host(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private static Identifier own(String path) {
        return Identifier.fromNamespaceAndPath("retromod", path);
    }
}
