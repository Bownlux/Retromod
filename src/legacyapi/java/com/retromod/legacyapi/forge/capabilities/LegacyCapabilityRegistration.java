/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.capabilities;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.shim.common.embedded.LegacyLog;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.registries.NewRegistryEvent;

/** Posts old mods' {@code RegisterCapabilitiesEvent} when Forge creates its registries, to each mod in mod order as Forge 1.20.1 did. */
public final class LegacyCapabilityRegistration {

    // Every translated jar's copy meets in these slots, which hold only JDK and host types.
    static final String POSTERS = "legacy-capabilities.posters";
    static final String LISTENING = "legacy-capabilities.listening";
    static final int PROTOCOL = 1;

    private static boolean watched;

    private LegacyCapabilityRegistration() {}

    /** Called from each old {@code @Mod} constructor of the jar: offers this jar's event to the shared posting, once. */
    public static synchronized void watch() {
        if (watched) return;
        watched = true;
        String copy = LegacyCapabilityRegistration.class.getName();
        String owner = copy;
        try {
            // The mod constructing now owns this copy; its place in the mod order sets when its event goes out.
            String modId = ModLoadingContext.get().getContainer().getModId();
            owner = modId;
            if (posters().putIfAbsent(modId, LegacyCapabilityRegistration::postTo) != null) {
                LegacyLog.warnOnce("register capabilities " + copy, "Retromod did not post the old RegisterCapabilitiesEvent"
                        + " of " + copy + ", because another jar's copy is already listed under the mod " + modId);
            }
            // The first copy to get here listens for every copy. LOWEST, so the NewRegistryEvent listeners old mods
            // add while they construct finish first, as every one did before RegisterCapabilitiesEvent on Forge 1.20.1.
            AtomicBoolean listening = LegacySharedSlots.get(LISTENING, PROTOCOL, AtomicBoolean.class, AtomicBoolean::new);
            if (listening.compareAndSet(false, true)) {
                NewRegistryEvent.BUS.addListener(Priority.LOWEST, event -> postAll(posters()));
            }
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not post the old RegisterCapabilitiesEvent for " + owner + ": " + failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Consumer<Object>> posters() {
        return LegacySharedSlots.get(POSTERS, PROTOCOL, Map.class, ConcurrentHashMap::new);
    }

    /** Forge 1.20.1 posted the event to each mod's bus in mod order; each jar's copy goes to every mod that listens for it. */
    static void postAll(Map<String, Consumer<Object>> posters) {
        List<ModContainer> mods = new ArrayList<>();
        ModList.forEachModInOrder(mods::add);
        // The copies in the order of the mods that brought them, then any whose mod the list does not name.
        Set<Consumer<Object>> copies = new LinkedHashSet<>();
        for (ModContainer mod : mods) {
            Consumer<Object> poster = posters.get(mod.getModId());
            if (poster != null) copies.add(poster);
        }
        copies.addAll(posters.values());
        for (ModContainer mod : mods) {
            for (Consumer<Object> poster : copies) poster.accept(mod);
        }
    }

    // This jar's event to one mod's group, when anything there listens for it; a library's listener sits on its user's group.
    private static void postTo(Object container) {
        ModContainer mod = (ModContainer) container;
        BusGroup group = mod.getModBusGroup();
        if (group == null) return;
        try {
            EventBus<LegacyRegisterCapabilitiesEvent> bus = IModBusEvent.getBus(group, LegacyRegisterCapabilitiesEvent.class);
            if (bus.hasListeners()) bus.post(new LegacyRegisterCapabilitiesEvent());
        } catch (RuntimeException | LinkageError failure) {
            // One mod's failing listener leaves the other mods' registrations in place.
            LegacyLog.warn("A RegisterCapabilitiesEvent listener of " + mod.getModId() + " threw; Retromod went on with"
                    + " the next mod. " + stackTrace(failure));
        }
    }

    private static String stackTrace(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }
}
