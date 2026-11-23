/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.gamerules;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.Codec;
import com.retromod.generated.LegacyGameRuleValue;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import com.retromod.shim.forge.embedded.LateVanillaRegistration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.resources.Identifier;

/** Registers an old mod's game rule as a real rule in the host's game rule registry, and runs its change callback. */
public final class LegacyGameRules {

    private static final Map<GameRule<?>, BiConsumer<Object, Object>> CALLBACKS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    // The old name each rule was registered under, to find its value in a world saved before 26.x.
    private static final Map<GameRule<?>, String> OLD_NAMES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<GameRule<?>, LegacyGameRuleType> TYPES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<Object> WATCHED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Object> CARRIED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static boolean listening;
    // The ids of Minecraft's own rules, the constants on GameRules, so no mod's rule counts as one.
    private static final Set<Identifier> VANILLA_IDS = vanillaIds();

    private LegacyGameRules() {}

    /** Old {@code GameRules.register(name, category, type)}: the rule goes into the registry, so it saves, syncs and shows in /gamerule. */
    public static GameRule<?> register(String name, GameRuleCategory category, LegacyGameRuleType type) {
        GameRule<?> rule = type.bool ? booleanRule(category, type) : integerRule(category, type);
        String id = freeId(name);
        // NeoForge and Forge freeze the vanilla registries early; this reopens them while the game still loads.
        try {
            LateVanillaRegistration.register(BuiltInRegistries.GAME_RULE, id, rule);
        } catch (Throwable failure) {
            throw new IllegalStateException("Retromod could not register the old game rule '" + name + "' as " + id
                    + ". A rule registered after the game finished loading, or a second rule with the same name, "
                    + "cannot be added: " + failure, failure);
        }
        type.rule = rule;
        OLD_NAMES.put(rule, name);
        TYPES.put(rule, type);
        if (type.callback != null) CALLBACKS.put(rule, type.callback);
        listen();
        return rule;
    }

    private static GameRule<Boolean> booleanRule(GameRuleCategory category, LegacyGameRuleType type) {
        return new GameRule<>(category, GameRuleType.BOOL, BoolArgumentType.bool(),
                (visitor, rule) -> visitor.visitBoolean(rule), Codec.BOOL, on -> on ? 1 : 0,
                (Boolean) type.defaultValue, FeatureFlagSet.of());
    }

    private static GameRule<Integer> integerRule(GameRuleCategory category, LegacyGameRuleType type) {
        return new GameRule<>(category, GameRuleType.INT, IntegerArgumentType.integer(type.min, type.max),
                (visitor, rule) -> visitor.visitInteger(rule), Codec.intRange(type.min, type.max), value -> value,
                (Integer) type.defaultValue, FeatureFlagSet.of());
    }

    /**
     * The id for an old name, moved under Retromod's namespace when the plain id is a vanilla rule, as
     * {@code doFireTick} would be. The old name still finds the rule: lookups and carried-over values
     * key on the rule, not its id. A clash with another mod's rule is left to fail, as the old duplicate did,
     * so which rule moves never depends on the order mods load in.
     */
    static String freeId(String name) {
        String id = id(name);
        Identifier parsed = Identifier.parse(id);
        if (!VANILLA_IDS.contains(parsed)) return id;
        String moved = "retromod:" + parsed.getPath();
        LegacyLog.warn("The old game rule '" + name + "' would take the id " + id + ", which Minecraft already uses, "
                + "so it is registered as " + moved + ".");
        return moved;
    }

    private static Set<Identifier> vanillaIds() {
        Set<Identifier> ids = new HashSet<>();
        for (Field field : GameRules.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            try {
                if (field.get(null) instanceof GameRule<?> rule) ids.add(Identifier.parse(rule.id()));
            } catch (IllegalAccessException unreadable) {
                // A field the class does not let us read is not a rule we could clash with.
            }
        }
        return ids;
    }

    /** The registry id for an old name: camel case becomes snake case, and anything an id cannot hold becomes '_'. */
    static String id(String name) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (id.length() > 0 && id.charAt(id.length() - 1) != '_' && id.charAt(id.length() - 1) != ':') id.append('_');
                id.append((char) (c - 'A' + 'a'));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "_-./:".indexOf(c) >= 0) {
                id.append(c);
            } else {
                id.append('_');
            }
        }
        return id.toString();
    }

    /** The type this jar registered a rule with, or the host rule's own type, for old visitors. */
    static LegacyGameRuleType typeOf(GameRule<?> rule) {
        LegacyGameRuleType type = TYPES.get(rule);
        return type != null ? type : LegacyGameRuleType.of(rule);
    }

    /** Prepares one server whose levels are loaded: carries old saved values over, then watches rule changes. */
    public static void watch(Object server) {
        carryOver(server);
        listenTo(server);
    }

    /** Carries old saved values over, once per server; the server's levels must be loaded. */
    static void carryOver(Object server) {
        if (server instanceof MinecraftServer minecraftServer && CARRIED.add(server)) {
            LegacyGameRuleCarryOver.apply(minecraftServer, OLD_NAMES);
        }
    }

    /** Watches one server's rule changes for the old callbacks, once. */
    static void listenTo(Object server) {
        if (!(server instanceof MinecraftServer minecraftServer) || CALLBACKS.isEmpty() || !WATCHED.add(server)) return;
        minecraftServer.notificationManager().registerService(new LegacyGameRuleNotifications(minecraftServer));
    }

    /** Runs the old callback for a changed rule with the server and the old-shaped value. */
    static void changed(MinecraftServer server, GameRule<?> rule) {
        BiConsumer<Object, Object> callback = CALLBACKS.get(rule);
        // The overworld's rules are the server's rules on every host from 1.21.11, which has no server getter.
        if (callback != null) callback.accept(server, LegacyGameRuleValue.of(server.overworld().getGameRules(), rule));
    }

    /** Hooks the server start event of whichever loader runs, so each server is watched before a command can change a rule. */
    private static synchronized void listen() {
        if (listening) return;
        listening = true;
        Consumer<Object> started = event -> watch(serverOf(event));
        if (listenNeoForge(started) || listenForge(started) || listenFabric()) return;
        LegacyLog.warn("Retromod found no server start event, so old game rules keep no value saved by an older "
                + "Minecraft and their change callbacks do not run.");
    }

    /** NeoForge's game bus, reached by reflection so this class names no NeoForge type. */
    private static boolean listenNeoForge(Consumer<Object> started) {
        try {
            Object bus = Class.forName("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
            Class<?> starting = Class.forName("net.neoforged.neoforge.event.server.ServerStartingEvent");
            for (Method method : LegacyPublicMethods.methods(bus.getClass())) {
                if (method.getName().equals("addListener") && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == Class.class && method.getParameterTypes()[1] == Consumer.class) {
                    LegacyPublicMethods.invocable(bus.getClass(), method).invoke(bus, starting, started);
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }

    /** Forge 26.x: each event type carries its own bus. */
    private static boolean listenForge(Consumer<Object> started) {
        try {
            Object bus = Class.forName("net.minecraftforge.event.server.ServerStartingEvent").getField("BUS").get(null);
            for (Method method : LegacyPublicMethods.methods(bus.getClass())) {
                if (method.getName().equals("addListener") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == Consumer.class) {
                    LegacyPublicMethods.invocable(bus.getClass(), method).invoke(bus, started);
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Fabric API's server events. Its starting event fires before the server loads its levels, so the change
     * watch starts there and the carry-over waits for the started event, when the world's rules exist.
     */
    private static boolean listenFabric() {
        try {
            String events = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents";
            hookFabric(events, "SERVER_STARTING", "ServerStarting", "onServerStarting", LegacyGameRules::listenTo);
            hookFabric(events, "SERVER_STARTED", "ServerStarted", "onServerStarted", LegacyGameRules::carryOver);
            return true;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }

    /** Registers {@code action} on one Fabric API server event, with a listener built for its callback interface. */
    private static void hookFabric(String events, String field, String type, String callback, Consumer<Object> action)
            throws ReflectiveOperationException {
        Class<?> listenerType = Class.forName(events + "$" + type);
        Object event = Class.forName(events).getField(field).get(null);
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        case "toString" -> { return "Retromod old game rules"; }
                        default -> { if (method.getName().equals(callback)) action.accept(args[0]); }
                    }
                    return null;
                });
        Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, listener);
    }

    private static Object serverOf(Object event) {
        try {
            return LegacyPublicMethods.method(event.getClass(), "getServer").invoke(event);
        } catch (ReflectiveOperationException failure) {
            LegacyLog.warn("Retromod could not read the server from " + event.getClass().getName() + ": " + failure);
            return null;
        }
    }
}
