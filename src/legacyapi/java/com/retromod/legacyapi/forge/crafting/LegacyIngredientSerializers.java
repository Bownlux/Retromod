/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.crafting;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.forge.eventbus.LegacyListenerSets;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.Identifier;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.EventListener;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

/** Forge 1.20.1's {@code CraftingHelper.register} and {@code getID} for ingredient serializers, on Forge 26.2. */
public final class LegacyIngredientSerializers {

    // Every translated jar's copy meets in these slots, which hold only JDK and host types.
    static final String IDS = "legacy-ingredient-serializers.ids";
    static final String CLOSED = "legacy-ingredient-serializers.closed";
    static final int PROTOCOL = 1;

    // This copy's serializers, in the order the mods registered them, until Forge registers ingredient serializers.
    private static final Map<Identifier, LegacyIngredientSerializer<?>> PENDING = new LinkedHashMap<>();
    private static boolean listening;
    private static boolean registered;

    private LegacyIngredientSerializers() {}

    /** Called from each old {@code @Mod} constructor of the jar, so this copy listens before anything registers. */
    public static synchronized void watch() {
        listen();
    }

    /** {@code CraftingHelper.register(id, serializer)}: keeps the serializer for Forge's serializer registry. */
    public static synchronized LegacyIngredientSerializer<?> register(Identifier id,
            LegacyIngredientSerializer<?> serializer) {
        Map<Object, Object> ids = ids();
        synchronized (ids) {
            // Forge 1.20.1 refused a second registration of an id or of a serializer.
            if (ids.containsValue(id)) throw new IllegalStateException("Duplicate recipe ingredient serializer: " + id);
            if (ids.containsKey(serializer)) {
                throw new IllegalStateException("Duplicate recipe ingredient serializer: " + id + " " + serializer);
            }
            ids.put(serializer, id);
        }
        // Forge 26.2 accepts entries only while it registers ingredient serializers, before any mod's setup.
        if (registered || closed().get()) {
            LegacyLog.warnOnce("late ingredient serializer " + id, "Retromod did not register the old ingredient"
                    + " serializer " + id + ", because Forge had already registered ingredient serializers.");
            return serializer;
        }
        PENDING.put(id, serializer);
        listen();
        return serializer;
    }

    /** {@code CraftingHelper.getID(serializer)}: the id any jar's copy registered it under, or null. */
    public static Identifier getID(LegacyIngredientSerializer<?> serializer) {
        return ids().get(serializer) instanceof Identifier id ? id : null;
    }

    // One listener per copy, on the bus of the mod constructing or registering now. It never throws, because an old
    // @Mod constructor calls it, also in a jar that names the old serializer only in code that never runs.
    private static void listen() {
        if (listening) return;
        String copy = LegacyIngredientSerializers.class.getName();
        BusGroup group = busGroup();
        if (group == null) {
            LegacyLog.warnOnce("ingredient serializer bus " + copy, "Retromod found no mod bus to register old"
                    + " ingredient serializers from " + copy + ", so it registered none of them.");
            return;
        }
        listening = true;
        try {
            EventBus<RegisterEvent> bus = RegisterEvent.getBus(group);
            EventListener listener = bus.addListener(LegacyIngredientSerializers::onRegister);
            LegacyListenerSets.remember(group, LegacyIngredientSerializers.class, bus, listener);
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("ingredient serializer listener " + copy, "Retromod could not listen for Forge's"
                    + " ingredient serializer registration from " + copy
                    + ", so it registered none of its serializers: " + failure);
        }
    }

    // Forge posts every registry event to every mod's bus, so Forge's own bus serves a call made outside any mod.
    private static BusGroup busGroup() {
        try {
            ModContainer active = ModLoadingContext.get().getContainer();
            BusGroup group = active == null ? null : active.getModBusGroup();
            if (group != null) return group;
            return ModList.getModContainerById("forge").map(ModContainer::getModBusGroup).orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** Registers this copy's serializers, in registration order, when Forge registers ingredient serializers. */
    static synchronized void onRegister(RegisterEvent event) {
        if (registered || !ForgeRegistries.Keys.INGREDIENT_SERIALIZERS.equals(event.getRegistryKey())) return;
        registered = true;
        closed().set(true);
        for (Map.Entry<Identifier, LegacyIngredientSerializer<?>> entry : PENDING.entrySet()) {
            LegacyIngredientSerializer<?> serializer = entry.getValue();
            event.register(ForgeRegistries.Keys.INGREDIENT_SERIALIZERS, entry.getKey(), () -> serializer);
        }
        PENDING.clear();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> ids() {
        return LegacySharedSlots.get(IDS, PROTOCOL, Map.class, () -> Collections.synchronizedMap(new HashMap<>()));
    }

    private static AtomicBoolean closed() {
        return LegacySharedSlots.get(CLOSED, PROTOCOL, AtomicBoolean.class, AtomicBoolean::new);
    }
}
