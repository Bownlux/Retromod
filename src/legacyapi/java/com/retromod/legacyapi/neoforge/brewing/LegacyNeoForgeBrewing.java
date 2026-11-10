/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.brewing;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.embedded.LegacyBrewingStore;
import net.minecraft.core.RegistryAccess;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

/** Posts the old brewing registration event for each old mod that listens for it, on NeoForge hosts that removed it. */
public final class LegacyNeoForgeBrewing {

    private static boolean installed;

    private LegacyNeoForgeBrewing() {}

    /** Called first in each old {@code @Mod} constructor of a jar that names the old brewing event. */
    public static void install() {
        synchronized (LegacyNeoForgeBrewing.class) {
            if (installed) return;
            installed = true;
        }
        try {
            // The store offers last start's recipe files as a pack and writes this start's when the server is up.
            LegacyBrewingStore.listen();
            IEventBus bus = (IEventBus) Class.forName("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
            @SuppressWarnings("unchecked")
            Class<Event> started = (Class<Event>) Class.forName("net.neoforged.neoforge.event.server.ServerStartedEvent");
            // Before the store writes, which listens at NORMAL, so this start's mixes are in the files.
            bus.addListener(EventPriority.HIGHEST, started, event -> post(bus, event));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            LegacyLog.warn("Retromod could not set up the old brewing event for the mod constructing now: " + failure);
        }
    }

    private static void post(IEventBus bus, Object startedEvent) {
        try {
            Object server = startedEvent.getClass().getMethod("getServer").invoke(startedEvent);
            RegistryAccess access = (RegistryAccess) server.getClass().getMethod("registryAccess").invoke(server);
            // Each world a client opens posts again, and its listeners add every recipe again.
            LegacyBrewingStore.beginEvent();
            bus.post(new LegacyRegisterBrewingRecipesEvent(new LegacyPotionBrewingBuilder(), access));
            LegacyLog.debug("Legacy brewing: posted the old brewing registration event");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            // This runs while the server starts, where a throw would stop it.
            LegacyLog.warn("An old brewing listener failed, so its brewing recipes are not written: " + failure);
        }
    }
}
