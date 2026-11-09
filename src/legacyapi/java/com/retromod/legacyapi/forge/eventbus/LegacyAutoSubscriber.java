/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.embedded.LegacyEventBus;
import com.retromod.shim.forge.embedded.LegacyEventBusImpl;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Subscribes an old mod's {@code @Mod.EventBusSubscriber} classes when its constructor finishes, where Forge 1.20.1 did. */
public final class LegacyAutoSubscriber {

    // Every @Mod class of a jar shares this copy, and the game bus took a class only once.
    private static final Set<String> ON_GAME_BUS = ConcurrentHashMap.newKeySet();

    private LegacyAutoSubscriber() {}

    /** Subscribes each listed class for this side; a line holds its binary name, FORGE or MOD, and its sides. */
    public static void subscribe(Object mod, String subscribers) {
        String side = FMLEnvironment.dist.name();
        LegacyEventBus modBus = null;
        for (String line : subscribers.split("\n")) {
            String[] fields = line.split(" ");
            // A class for the other side never loads, as in Forge 1.20.1.
            if (fields.length != 3 || !Arrays.asList(fields[2].split(",")).contains(side)) continue;
            boolean onModBus = fields[1].equals("MOD");
            if (!onModBus && !ON_GAME_BUS.add(fields[0])) continue;
            try {
                // Forge 1.20.1 initialized the class before the bus read its listeners.
                Class<?> type = Class.forName(fields[0], true, mod.getClass().getClassLoader());
                // The mod's own bus group is known only while it constructs, which is now.
                if (onModBus && modBus == null) {
                    modBus = LegacyEventBusImpl.of(ModLoadingContext.get().getContainer().getModBusGroup());
                }
                (onModBus ? modBus : LegacyEventBusImpl.GAME_BUS).register(type);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                LegacyLog.warn("Could not subscribe " + fields[0] + " of " + mod.getClass().getName() + " to the "
                        + (onModBus ? "mod" : "game") + " event bus, so its event listeners will not run. "
                        + "Report it with the log. Cause: " + failure);
            }
        }
    }
}
