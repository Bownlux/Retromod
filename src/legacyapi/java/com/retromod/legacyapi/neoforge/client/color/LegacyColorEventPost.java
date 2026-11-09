/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.color;

import com.retromod.legacyapi.minecraft.client.color.ItemColors;
import com.retromod.legacyapi.minecraft.client.color.LegacyItemColorTable;
import com.retromod.legacyapi.minecraft.client.color.LegacyItemTint;
import com.retromod.legacyapi.neoforge.client.event.LegacyBlockColorsEvent;
import com.retromod.legacyapi.neoforge.client.event.LegacyItemColorsEvent;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Posts this copy's old color events while NeoForge registers block tints, and adds each of its mods' item tint types. */
final class LegacyColorEventPost {

    // The mods that installed this copy, in install order. An addon that reuses its library's copy installs it too.
    private static final Set<String> NAMESPACES = new LinkedHashSet<>();
    private static boolean listening;

    private LegacyColorEventPost() {}

    /** Notes the constructing mod, and listens on its bus the first time this copy installs. */
    static void listen() {
        // Construction is the only time FML names an active mod.
        ModContainer container = ModLoadingContext.get().getActiveContainer();
        IEventBus bus = container == null ? null : container.getEventBus();
        if (bus == null) return;
        synchronized (NAMESPACES) {
            NAMESPACES.add(container.getNamespace());
            if (listening) return;
            listening = true;
        }
        // NeoForge posts its item tint event in Minecraft's constructor, after every mod constructed and before the
        // first resource reload reads the item definitions that name these types.
        bus.addListener(RegisterColorHandlersEvent.ItemTintSources.class, LegacyColorEventPost::addTintTypes);
        // NeoForge posts a mod-bus event one priority at a time across every mod. Every copy posts its old block event
        // at NORMAL and its old item event at LOWEST, so every old block handler runs before the first old item
        // handler, as NeoForge 1.21.1 posted the item event after the block event.
        bus.addListener(EventPriority.NORMAL, RegisterColorHandlersEvent.BlockTintSources.class,
                event -> post(new LegacyBlockColorsEvent(event.getBlockColors())));
        bus.addListener(EventPriority.LOWEST, RegisterColorHandlersEvent.BlockTintSources.class,
                event -> post(new LegacyItemColorsEvent(ItemColors.shared(), event.getBlockColors())));
    }

    // Every mod's bus, one priority at a time in mod order, as NeoForge posts a mod-bus event. A library such as
    // Zeta may add its listener to the bus of the mod that uses it, so no bus is left out.
    private static void post(Event event) {
        List<ModContainer> mods = ModList.get().getSortedMods();
        for (EventPriority phase : EventPriority.values()) {
            for (ModContainer mod : mods) {
                IEventBus bus = mod.getEventBus();
                if (bus == null) continue;
                // This runs while Minecraft starts, where a throw would stop the game.
                try {
                    bus.post(phase, event);
                } catch (RuntimeException | LinkageError failure) {
                    LegacyLog.warn("An old color listener on the bus of " + mod.getNamespace() + " threw; Retromod went on"
                            + " with the next one: " + failure);
                }
            }
        }
    }

    /** Adds {@code <namespace>:retromod_legacy_color} for each mod that installed this copy. */
    private static void addTintTypes(RegisterColorHandlersEvent.ItemTintSources event) {
        for (String namespace : namespaces()) {
            try {
                // NeoForge maps a codec to one id, so each namespace gets its own.
                event.register(Identifier.fromNamespaceAndPath(namespace, LegacyItemColorTable.TINT_TYPE), LegacyItemTint.newType());
            } catch (RuntimeException | LinkageError failure) {
                LegacyLog.warn("Retromod could not add the item tint type " + namespace + ":" + LegacyItemColorTable.TINT_TYPE
                        + " (" + failure + "), so the item definitions of " + namespace + " that name it show the missing model");
            }
        }
    }

    private static List<String> namespaces() {
        synchronized (NAMESPACES) {
            return new ArrayList<>(NAMESPACES);
        }
    }
}
