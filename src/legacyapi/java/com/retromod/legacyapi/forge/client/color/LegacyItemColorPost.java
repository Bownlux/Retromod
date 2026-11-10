/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.color;

import com.mojang.serialization.MapCodec;
import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.legacyapi.forge.client.event.LegacyItemColorsEvent;
import com.retromod.legacyapi.forge.eventbus.LegacyEvents;
import com.retromod.legacyapi.minecraft.client.color.ItemColors;
import com.retromod.legacyapi.minecraft.client.color.LegacyItemTint;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.ItemTintSources;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.ModList;

/** Posts every copy's old item color event after the host's block color event, and adds each mod's item tint type. */
final class LegacyItemColorPost {

    private static final Function<Object[], Object> ENDPOINT = LegacyItemColorPost::handle;
    private static final Set<String> TYPED = ConcurrentHashMap.newKeySet();

    private LegacyItemColorPost() {}

    /** Lists this copy, and adds the one shared listener if no copy added it yet. */
    static void attach() {
        LegacyItemColors.endpoints().add(ENDPOINT);
        AtomicBoolean added = LegacySharedSlots.get(LegacyItemColors.LISTENER, LegacyItemColors.PROTOCOL, AtomicBoolean.class,
                AtomicBoolean::new);
        if (!added.compareAndSet(false, true)) return;
        // Forge 1.20.1 posted the item event right after the block one. A monitor runs after every other listener,
        // whatever its priority and whenever it was added, so every block handler, those of mods constructed later
        // included, runs before the first item handler and sees the same block colors.
        RegisterColorHandlersEvent.Block.BUS.addListener(Priority.MONITOR, event -> post(event.getBlockColors()));
    }

    // Each mod in mod order, then each copy that mod installed, as Forge 1.20.1 posted mod-bus events.
    private static void post(BlockColors blockColors) {
        List<String> mods = modOrder();
        // Without FML's list, the order the copies were installed in stands in.
        for (Function<Object[], Object> copy : LegacyItemColors.endpoints()) {
            for (Object owner : ownersOf(copy)) {
                if (owner instanceof String namespace && !mods.contains(namespace)) mods.add(namespace);
            }
        }
        for (String mod : mods) {
            for (Function<Object[], Object> copy : LegacyItemColors.endpoints()) {
                // This runs while Minecraft starts, where a throw would stop the game.
                try {
                    copy.apply(new Object[]{"post", mod, blockColors});
                } catch (RuntimeException | LinkageError failure) {
                    LegacyLog.warn("An old item color listener of " + mod + " threw; Retromod went on with the next mod: "
                            + failure);
                }
            }
        }
    }

    /** One request to this copy, as LegacyItemColors.ENDPOINTS lists them. */
    static Object handle(Object[] request) {
        switch ((String) request[0]) {
            case "post":
                if (LegacyClientRenderBootstrap.namespaces().contains(request[1])) {
                    postTo(LegacyClientRenderBootstrap.modBusGroup((String) request[1]), (BlockColors) request[2]);
                }
                return null;
            case "owners":
                return LegacyClientRenderBootstrap.namespaces();
            case "protocol":
                return LegacyItemColors.PROTOCOL;
            default:
                throw new IllegalArgumentException("Unknown item color request " + request[0]);
        }
    }

    private static void postTo(BusGroup group, BlockColors blockColors) {
        if (group == null || !LegacyEvents.busOf(group, LegacyItemColorsEvent.class).hasListeners()) return;
        LegacyEvents.post(group, new LegacyItemColorsEvent(ItemColors.shared(), blockColors));
    }

    /** Adds {@code <namespace>:retromod_legacy_color} for each mod that installed this copy and has none yet. */
    static void addTintTypes() {
        for (String namespace : LegacyClientRenderBootstrap.namespaces()) {
            if (!TYPED.add(namespace)) continue;
            try {
                ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemTintSource>> ids = tintTypes();
                // Mods may construct in parallel, and every copy locks the same host object.
                synchronized (ids) {
                    ids.put(Identifier.fromNamespaceAndPath(namespace, LegacyItemColors.TINT_TYPE), LegacyItemTint.newType());
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                // Retromod's item definitions for this mod name the type, so each tinted one fails to parse.
                LegacyLog.warn("Retromod could not add the item tint type " + namespace + ":" + LegacyItemColors.TINT_TYPE
                        + " (" + failure + "), so the tinted item models of " + namespace + " will not load and show the"
                        + " missing model");
            }
        }
    }

    // Minecraft keeps its tint type ids in a private static field. Its jar has no module descriptor, so its packages
    // are open to reflection; the field is found by its type, so a rename does not lose it.
    @SuppressWarnings("unchecked")
    private static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemTintSource>> tintTypes()
            throws ReflectiveOperationException {
        for (Field field : ItemTintSources.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ExtraCodecs.LateBoundIdMapper.class) {
                field.setAccessible(true);
                return (ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemTintSource>>) field.get(null);
            }
        }
        throw new NoSuchFieldException("ItemTintSources keeps no tint type ids");
    }

    // FML's mod order, or none when FML cannot give it.
    private static List<String> modOrder() {
        List<String> mods = new ArrayList<>();
        try {
            ModList.forEachModInOrder(mod -> mods.add(mod.getNamespace()));
        } catch (RuntimeException | LinkageError noModList) {
            mods.clear();
        }
        return mods;
    }

    // The namespaces that installed a copy, or none when the copy cannot say.
    private static List<?> ownersOf(Function<Object[], Object> copy) {
        try {
            return copy.apply(new Object[]{"owners"}) instanceof List<?> owners ? owners : List.of();
        } catch (RuntimeException | LinkageError unreadable) {
            return List.of();
        }
    }
}
