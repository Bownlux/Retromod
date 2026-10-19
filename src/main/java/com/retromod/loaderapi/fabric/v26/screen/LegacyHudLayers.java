/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.loaderapi.fabric.v26.screen.shared.LegacyHudLayer;
import com.retromod.loaderapi.fabric.v26.screen.shared.LegacyHudLayerRegistrationCallback;
import com.retromod.loaderapi.fabric.v26.screen.shared.LegacyIdentifiedLayer;
import com.retromod.loaderapi.fabric.v26.screen.shared.LegacyLayeredDrawerWrapper;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Serves the 1.21.4 and 1.21.5 HUD layer API, and HUD element ids 26.x removed, through the host registry. */
public final class LegacyHudLayers {

    static final String DEBUG = "minecraft:debug";
    static final String HOTBAR_AND_BARS = "minecraft:hotbar_and_bars";
    static final String STATUS_EFFECTS = "minecraft:status_effects";

    /** The 26.x elements that the 1.21.4 hotbar and bars layer drew as one. */
    static final List<String> HOTBAR_GROUP = List.of("SPECTATOR_MENU", "HOTBAR", "ARMOR_BAR",
            "HEALTH_BAR", "FOOD_BAR", "AIR_BAR", "MOUNT_HEALTH", "INFO_BAR", "HELD_ITEM_TOOLTIP",
            "SPECTATOR_TOOLTIP");

    private LegacyHudLayers() {}

    /** Builds the old registration event, which fires once the client has started. */
    public static Event<LegacyHudLayerRegistrationCallback> registrationEvent() {
        Event<LegacyHudLayerRegistrationCallback> event = EventFactory.createArrayBacked(
                LegacyHudLayerRegistrationCallback.class, callbacks -> drawer -> {
                    for (LegacyHudLayerRegistrationCallback callback : callbacks) {
                        callback.registerLegacyHudLayers(drawer);
                    }
                });
        // Fabric API fired it while the client built its HUD, after every mod's client setup.
        boolean installed;
        try {
            installed = HostGui.onClientStarted("legacy HUD layer registration",
                    () -> event.invoker().registerLegacyHudLayers(new Wrapper()));
        } catch (RuntimeException e) {
            HostGui.warnOnce("hud-layers", "Retromod could not listen for the client start ({}). Old HUD "
                    + "layer callbacks are kept but never fire.", e.toString());
            return event;
        }
        if (!installed) {
            HostGui.debugOnce("no-hud-layers", "Old HUD layer callbacks are kept but never fire: "
                    + "this side has no Fabric API client lifecycle events.");
        }
        return event;
    }

    /** {@code IdentifiedLayer.of(id, layer)}. */
    public static LegacyIdentifiedLayer identified(Object id, LegacyHudLayer layer) {
        return new Identified(id, layer);
    }

    /** {@code IdentifiedLayer.HOTBAR_AND_BARS}, an id 26.x split into ten elements. */
    public static Object hotbarAndBarsAnchor() {
        return HostGui.id("minecraft", "hotbar_and_bars");
    }

    /** {@code DEBUG}: the debug overlay, which 26.x draws outside the HUD element tree. */
    public static Object debugAnchor() {
        return HostGui.id("minecraft", "debug");
    }

    // ---- Host registry calls that understand the removed ids ----------------------------

    /** {@code HudElementRegistry.attachElementBefore}, with a removed anchor placed where it stood. */
    public static void attachElementBefore(Object beforeThis, Object id, Object element) {
        HostGui.call(HostLink.require(HostGui.HUD_ATTACH_BEFORE), anchorBefore(beforeThis), id, element);
    }

    /** {@code HudElementRegistry.attachElementAfter}, with a removed anchor placed where it stood. */
    public static void attachElementAfter(Object afterThis, Object id, Object element) {
        HostGui.call(HostLink.require(HostGui.HUD_ATTACH_AFTER), anchorAfter(afterThis), id, element);
    }

    /** {@code HudElementRegistry.removeElement}; a removed id removes the elements that replaced it. */
    public static void removeElement(Object id) {
        for (Object element : removed(id)) {
            HostGui.call(HostLink.require(HostGui.HUD_REMOVE), element);
        }
    }

    /** {@code HudElementRegistry.replaceElement}; a removed id replaces the element that took its place. */
    public static void replaceElement(Object id, Function<Object, Object> replacer) {
        Object target = replaced(id);
        if (target != null) HostGui.call(HostLink.require(HostGui.HUD_REPLACE), target, replacer);
    }

    // ---- Removed ids ----------------------------------------------------------------------

    /** Where a layer attached before {@code anchor} goes on 26.x. */
    static Object anchorBefore(Object anchor) {
        // In 1.21.4 to 1.21.8 the debug overlay sat between the demo timer and the scoreboard.
        return switch (String.valueOf(anchor)) {
            case DEBUG -> vanilla("SCOREBOARD");
            case HOTBAR_AND_BARS -> vanilla(HOTBAR_GROUP.get(0));
            case STATUS_EFFECTS -> vanilla("MOB_EFFECTS");
            default -> anchor;
        };
    }

    /** Where a layer attached after {@code anchor} goes on 26.x. */
    static Object anchorAfter(Object anchor) {
        // The experience level followed the hotbar and bars, so a layer after them comes before it.
        return switch (String.valueOf(anchor)) {
            case DEBUG -> vanilla("DEMO_TIMER");
            case HOTBAR_AND_BARS -> vanilla("INFO_BAR");
            case STATUS_EFFECTS -> vanilla("MOB_EFFECTS");
            default -> anchor;
        };
    }

    /** The 26.x elements that removing {@code id} removes. */
    static List<Object> removed(Object id) {
        List<Object> elements = new ArrayList<>();
        switch (String.valueOf(id)) {
            case DEBUG -> HostGui.infoOnce("hud-remove-debug", "An old mod removed the debug HUD layer. "
                    + "26.x draws the debug overlay outside the HUD elements, so it stays.");
            case HOTBAR_AND_BARS -> HOTBAR_GROUP.forEach(name -> elements.add(vanilla(name)));
            case STATUS_EFFECTS -> elements.add(vanilla("MOB_EFFECTS"));
            default -> elements.add(id);
        }
        return elements;
    }

    /** The 26.x element that replacing {@code id} replaces, or null for none. */
    static Object replaced(Object id) {
        switch (String.valueOf(id)) {
            case DEBUG -> {
                HostGui.infoOnce("hud-replace-debug", "An old mod replaced the debug HUD layer. 26.x "
                        + "draws the debug overlay outside the HUD elements, so it keeps its own.");
                return null;
            }
            case HOTBAR_AND_BARS -> {
                HostGui.infoOnce("hud-replace-hotbar", "An old mod replaced the hotbar and bars HUD "
                        + "layer. 26.x draws them as separate elements; the replacement takes the hotbar's "
                        + "place and the bars stay.");
                return vanilla("HOTBAR");
            }
            case STATUS_EFFECTS -> {
                return vanilla("MOB_EFFECTS");
            }
            default -> {
                return id;
            }
        }
    }

    /** A {@code VanillaHudElements} id by field name. */
    static Object vanilla(String field) {
        Member member = new Member(HostGui.VANILLA_HUD, field, "L" + HostGui.IDENTIFIER + ";", true);
        return HostGui.call(HostLink.require(member));
    }

    // ---- Host elements ----------------------------------------------------------------------

    /** A host {@code HudElement} that draws an old layer. */
    static Object element(LegacyHudLayer layer) {
        Optional<Class<?>> type = HostGui.type(HostGui.HUD_ELEMENT);
        return HostGui.proxy(type.orElseThrow(), "legacy HUD layer", (proxy, method, args) -> {
            if (method.getName().equals("extractRenderState") && args != null && args.length == 2) {
                layer.renderLegacyHudLayer(args[0], args[1]);
                return null;
            }
            return HostGui.defaultOf(proxy, method, args, "legacy HUD layer");
        });
    }

    /** An old layer that draws a host element, for a replacement that wraps the original. */
    static LegacyHudLayer hostLayer(Object element) {
        return (graphics, deltaTracker) -> HostGui.call(HostLink.require(HostGui.HUD_EXTRACT),
                element, graphics, deltaTracker);
    }

    /** An old identified layer built from an id and a layer. */
    record Identified(Object id, LegacyHudLayer layer) implements LegacyIdentifiedLayer {
        @Override
        public Object legacyHudLayerId() {
            return id;
        }

        @Override
        public void renderLegacyHudLayer(Object graphics, Object deltaTracker) {
            layer.renderLegacyHudLayer(graphics, deltaTracker);
        }
    }

    /** The old {@code LayeredDrawerWrapper}, over the host HUD element registry. */
    static final class Wrapper implements LegacyLayeredDrawerWrapper {

        @Override
        public LegacyLayeredDrawerWrapper addLayer(LegacyIdentifiedLayer layer) {
            HostGui.call(HostLink.require(HostGui.HUD_ADD_LAST), layer.legacyHudLayerId(), element(layer));
            return this;
        }

        @Override
        public LegacyLayeredDrawerWrapper attachLayerBefore(Object beforeThis, LegacyIdentifiedLayer layer) {
            attachElementBefore(beforeThis, layer.legacyHudLayerId(), element(layer));
            return this;
        }

        @Override
        public LegacyLayeredDrawerWrapper attachLayerBefore(Object beforeThis, Object id, LegacyHudLayer layer) {
            return attachLayerBefore(beforeThis, identified(id, layer));
        }

        @Override
        public LegacyLayeredDrawerWrapper attachLayerAfter(Object afterThis, LegacyIdentifiedLayer layer) {
            attachElementAfter(afterThis, layer.legacyHudLayerId(), element(layer));
            return this;
        }

        @Override
        public LegacyLayeredDrawerWrapper attachLayerAfter(Object afterThis, Object id, LegacyHudLayer layer) {
            return attachLayerAfter(afterThis, identified(id, layer));
        }

        @Override
        public LegacyLayeredDrawerWrapper removeLayer(Object id) {
            removeElement(id);
            return this;
        }

        @Override
        public LegacyLayeredDrawerWrapper replaceLayer(Object id,
                Function<LegacyIdentifiedLayer, LegacyIdentifiedLayer> replacer) {
            Object target = replaced(id);
            if (target == null) return this;
            // The replacer sees the host's element as an old layer, and its result draws as an element.
            Function<Object, Object> hostReplacer = original ->
                    element(replacer.apply(identified(target, hostLayer(original))));
            HostGui.call(HostLink.require(HostGui.HUD_REPLACE), target, hostReplacer);
            return this;
        }
    }
}
