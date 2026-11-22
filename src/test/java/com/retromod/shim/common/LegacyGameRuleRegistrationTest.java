/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old mod's own game rules become real host rules: registered, read, written, and their change callbacks run. */
// Public, because the old-shaped visitor in the test loader records into these lists.
public class LegacyGameRuleRegistrationTest {

    private static final String OLD = LegacyGameRuleValueBridge.OLD;
    private static final String RULES = LegacyGameRuleValueBridge.RULES;
    private static final String RULE = LegacyGameRuleValueBridge.RULE;
    private static final String MOD = "mod/OldRuleRegistry";
    private static final String VISITOR = "mod/OldRuleVisitor";
    private static final String TEST = "com/retromod/shim/common/LegacyGameRuleRegistrationTest";

    @TempDir
    static Path stubDir;

    private static Map<String, byte[]> host;

    /** What the old callbacks were given, in order. */
    public static final List<Object[]> CALLS = new ArrayList<>();
    /** What the old visitor saw: boolean rules, integer rules, and the defaults its types made. */
    public static final List<Object> SEEN_BOOLEAN = new ArrayList<>();
    public static final List<Object> SEEN_INTEGER = new ArrayList<>();
    public static final List<Object> DEFAULTS = new ArrayList<>();

    /** The old {@code BiConsumer<MinecraftServer, BooleanValue>} an old mod passes, recording each call. */
    public static final class Recorder implements BiConsumer<Object, Object> {
        @Override
        public void accept(Object server, Object value) {
            CALLS.add(new Object[] {server, value});
        }
    }

    @BeforeAll
    static void compileHost() throws Exception {
        Map<String, String> sources = new HashMap<>();
        sources.put("com/mojang/serialization/DataResult.java", """
                package com.mojang.serialization;
                public interface DataResult<T> { java.util.Optional<T> result(); }""");
        sources.put("com/mojang/serialization/Codec.java", """
                package com.mojang.serialization;
                public interface Codec<A> {
                    com.mojang.serialization.codecs.PrimitiveCodec<Boolean> BOOL = new com.mojang.serialization.codecs.PrimitiveCodec<>() {};
                    static Codec<Integer> intRange(int min, int max) { return new Range(min, max); }
                    record Range(int min, int max) implements Codec<Integer> {}
                }""");
        sources.put("com/mojang/serialization/codecs/PrimitiveCodec.java", """
                package com.mojang.serialization.codecs;
                public interface PrimitiveCodec<A> extends com.mojang.serialization.Codec<A> {}""");
        sources.put("com/mojang/brigadier/arguments/ArgumentType.java", """
                package com.mojang.brigadier.arguments;
                public interface ArgumentType<T> {}""");
        sources.put("com/mojang/brigadier/arguments/BoolArgumentType.java", """
                package com.mojang.brigadier.arguments;
                public class BoolArgumentType implements ArgumentType<Boolean> {
                    public static BoolArgumentType bool() { return new BoolArgumentType(); }
                }""");
        sources.put("com/mojang/brigadier/arguments/IntegerArgumentType.java", """
                package com.mojang.brigadier.arguments;
                public class IntegerArgumentType implements ArgumentType<Integer> {
                    public final int min, max;
                    private IntegerArgumentType(int min, int max) { this.min = min; this.max = max; }
                    public static IntegerArgumentType integer(int min, int max) { return new IntegerArgumentType(min, max); }
                }""");
        sources.put("net/minecraft/world/flag/FeatureFlagSet.java", """
                package net.minecraft.world.flag;
                public final class FeatureFlagSet { public static FeatureFlagSet of() { return new FeatureFlagSet(); } }""");
        sources.put("net/minecraft/world/flag/FeatureFlagRegistry.java", """
                package net.minecraft.world.flag;
                public class FeatureFlagRegistry { public FeatureFlagSet allFlags() { return FeatureFlagSet.of(); } }""");
        sources.put("net/minecraft/world/flag/FeatureFlags.java", """
                package net.minecraft.world.flag;
                public class FeatureFlags { public static final FeatureFlagRegistry REGISTRY = new FeatureFlagRegistry(); }""");
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public record Identifier(String namespace, String path) {
                    public String getPath() { return path; }
                    public static Identifier parse(String id) {
                        int colon = id.indexOf(':');
                        return colon < 0 ? new Identifier("minecraft", id) : new Identifier(id.substring(0, colon), id.substring(colon + 1));
                    }
                }""");
        sources.put("net/minecraft/world/level/storage/LevelResource.java", """
                package net.minecraft.world.level.storage;
                public final class LevelResource {
                    public static final LevelResource DATA = new LevelResource("data");
                    public static final LevelResource LEVEL_DATA_FILE = new LevelResource("level.dat");
                    public final String path;
                    private LevelResource(String path) { this.path = path; }
                }""");
        sources.put("net/minecraft/nbt/CompoundTag.java", """
                package net.minecraft.nbt;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Optional;
                public final class CompoundTag {
                    public final Map<String, Object> values = new HashMap<>();
                    public Optional<CompoundTag> getCompound(String key) {
                        return values.get(key) instanceof CompoundTag tag ? Optional.of(tag) : Optional.empty();
                    }
                    public Optional<String> getString(String key) {
                        return values.get(key) instanceof String text ? Optional.of(text) : Optional.empty();
                    }
                }""");
        sources.put("net/minecraft/nbt/NbtAccounter.java", """
                package net.minecraft.nbt;
                public class NbtAccounter { public static NbtAccounter unlimitedHeap() { return new NbtAccounter(); } }""");
        // The stand-in reads key=value lines, nested the way each file nests its rules.
        sources.put("net/minecraft/nbt/NbtIo.java", """
                package net.minecraft.nbt;
                import java.io.IOException;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class NbtIo {
                    public static CompoundTag readCompressed(Path file, NbtAccounter accounter) throws IOException {
                        CompoundTag rules = new CompoundTag();
                        String key = "game_rules";
                        for (String line : Files.readAllLines(file)) {
                            if (line.startsWith("@")) {
                                key = line.substring(1);
                                continue;
                            }
                            String[] pair = line.split("=", 2);
                            rules.values.put(pair[0], pair[1]);
                        }
                        CompoundTag root = new CompoundTag();
                        if (file.getFileName().toString().equals("level.dat")) {
                            CompoundTag data = new CompoundTag();
                            data.values.put(key, rules);
                            root.values.put("Data", data);
                        } else {
                            root.values.put("data", rules);
                        }
                        return root;
                    }
                }""");
        sources.put("net/minecraft/core/Registry.java", """
                package net.minecraft.core;
                public interface Registry<T> extends Iterable<T> {
                    void add(String id, T value);
                    boolean containsKey(net.minecraft.resources.Identifier id);
                    static <V, T extends V> T register(Registry<V> registry, String id, T value) { registry.add(id, value); return value; }
                }""");
        sources.put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import net.minecraft.core.Registry;
                import net.minecraft.world.level.gamerules.GameRule;
                public class BuiltInRegistries {
                    public static final Map<String, Object> GAME_RULES = new LinkedHashMap<>();
                    @SuppressWarnings("unchecked")
                    public static final Registry<GameRule<?>> GAME_RULE = new Registry<>() {
                        public void add(String id, GameRule<?> value) {
                            if (GAME_RULES.putIfAbsent(id, value) != null) throw new IllegalStateException("duplicate " + id);
                        }
                        public boolean containsKey(net.minecraft.resources.Identifier id) {
                            return GAME_RULES.keySet().stream().anyMatch(key -> net.minecraft.resources.Identifier.parse(key).equals(id));
                        }
                        public java.util.Iterator<GameRule<?>> iterator() {
                            return (java.util.Iterator<GameRule<?>>) (java.util.Iterator<?>) GAME_RULES.values().iterator();
                        }
                    };
                }""");
        sources.put("net/minecraft/world/level/gamerules/GameRuleCategory.java", """
                package net.minecraft.world.level.gamerules;
                public record GameRuleCategory(String id) {
                    public static final GameRuleCategory PLAYER = new GameRuleCategory("player");
                    public static final GameRuleCategory MOBS = new GameRuleCategory("mobs");
                    public static final GameRuleCategory SPAWNING = new GameRuleCategory("spawning");
                    public static final GameRuleCategory DROPS = new GameRuleCategory("drops");
                    public static final GameRuleCategory UPDATES = new GameRuleCategory("updates");
                    public static final GameRuleCategory CHAT = new GameRuleCategory("chat");
                    public static final GameRuleCategory MISC = new GameRuleCategory("misc");
                }""");
        sources.put("net/minecraft/world/level/gamerules/GameRuleType.java", """
                package net.minecraft.world.level.gamerules;
                public enum GameRuleType { INT, BOOL }""");
        sources.put("net/minecraft/world/level/gamerules/GameRuleTypeVisitor.java", """
                package net.minecraft.world.level.gamerules;
                public interface GameRuleTypeVisitor {
                    default void visitBoolean(GameRule<Boolean> rule) {}
                    default void visitInteger(GameRule<Integer> rule) {}
                }""");
        sources.put("net/minecraft/world/level/gamerules/GameRule.java", """
                package net.minecraft.world.level.gamerules;
                import com.mojang.brigadier.arguments.ArgumentType;
                import com.mojang.serialization.Codec;
                import com.mojang.serialization.DataResult;
                import java.util.Optional;
                import java.util.function.ToIntFunction;
                import net.minecraft.world.flag.FeatureFlagSet;
                public final class GameRule<T> {
                    private final GameRuleCategory category;
                    private final GameRuleType type;
                    private final ArgumentType<T> argument;
                    private final GameRules.VisitorCaller<T> visitorCaller;
                    private final Codec<T> codec;
                    private final ToIntFunction<T> commandResult;
                    private final T defaultValue;
                    public GameRule(GameRuleCategory category, GameRuleType type, ArgumentType<T> argument,
                            GameRules.VisitorCaller<T> visitorCaller, Codec<T> codec, ToIntFunction<T> commandResult,
                            T defaultValue, FeatureFlagSet features) {
                        this.category = category; this.type = type; this.argument = argument; this.visitorCaller = visitorCaller;
                        this.codec = codec; this.commandResult = commandResult; this.defaultValue = defaultValue;
                    }
                    public String id() {
                        return net.minecraft.core.registries.BuiltInRegistries.GAME_RULES.entrySet().stream()
                                .filter(entry -> entry.getValue() == this).map(java.util.Map.Entry::getKey).findFirst().orElse("?");
                    }
                    @SuppressWarnings("unchecked")
                    public Class<T> valueClass() { return (Class<T>) defaultValue.getClass(); }
                    public T defaultValue() { return defaultValue; }
                    public GameRuleCategory category() { return category; }
                    public GameRuleType gameRuleType() { return type; }
                    public ArgumentType<T> argument() { return argument; }
                    public Codec<T> valueCodec() { return codec; }
                    public int getCommandResult(T value) { return commandResult.applyAsInt(value); }
                    public void callVisitor(GameRuleTypeVisitor visitor) { visitorCaller.call(visitor, this); }
                    @SuppressWarnings("unchecked")
                    public DataResult<T> deserialize(String text) {
                        if (defaultValue instanceof Boolean) {
                            boolean valid = text.equals("true") || text.equals("false");
                            return () -> valid ? Optional.of((T) Boolean.valueOf(text)) : Optional.empty();
                        }
                        try {
                            Integer parsed = Integer.valueOf(text);
                            return () -> Optional.of((T) parsed);
                        } catch (NumberFormatException e) {
                            return Optional::empty;
                        }
                    }
                }""");
        sources.put("net/minecraft/world/level/gamerules/GameRules.java", """
                package net.minecraft.world.level.gamerules;
                import java.util.HashMap;
                import java.util.Map;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.server.MinecraftServer;
                public class GameRules {
                    public interface VisitorCaller<T> { void call(GameRuleTypeVisitor visitor, GameRule<T> rule); }
                    // One vanilla rule, registered when the class loads, as Minecraft's own rules are.
                    public static final GameRule<Boolean> FIRE_DAMAGE = new GameRule<>(GameRuleCategory.PLAYER, GameRuleType.BOOL,
                            null, null, null, value -> value ? 1 : 0, true, null);
                    static { BuiltInRegistries.GAME_RULE.add("fire_damage", FIRE_DAMAGE); }
                    private final Map<Object, Object> values = new HashMap<>();
                    public GameRules() {
                        for (Object rule : BuiltInRegistries.GAME_RULES.values()) values.put(rule, ((GameRule<?>) rule).defaultValue());
                    }
                    public GameRules(net.minecraft.world.flag.FeatureFlagSet features) { this(); }
                    @SuppressWarnings("unchecked")
                    public <T> T get(GameRule<T> rule) { return (T) values.get(rule); }
                    public <T> void set(GameRule<T> rule, T value, MinecraftServer server) {
                        values.put(rule, value);
                        if (server != null) server.onGameRuleChanged(rule, value);
                    }
                    public <T> String getAsString(GameRule<T> rule) { return String.valueOf(get(rule)); }
                }""");
        sources.put("net/minecraft/server/level/ServerLevel.java", """
                package net.minecraft.server.level;
                import net.minecraft.world.level.gamerules.GameRules;
                public class ServerLevel {
                    private GameRules rules;
                    public GameRules getGameRules() { if (rules == null) rules = new GameRules(); return rules; }
                }""");
        sources.put("net/minecraft/server/notifications/NotificationService.java", """
                package net.minecraft.server.notifications;
                import net.minecraft.world.level.gamerules.GameRule;
                public interface NotificationService { <T> void onGameRuleChanged(GameRule<T> rule, T value); }""");
        sources.put("net/minecraft/server/notifications/EmptyNotificationService.java", """
                package net.minecraft.server.notifications;
                import net.minecraft.world.level.gamerules.GameRule;
                public class EmptyNotificationService implements NotificationService {
                    public <T> void onGameRuleChanged(GameRule<T> rule, T value) {}
                }""");
        sources.put("net/minecraft/server/notifications/NotificationManager.java", """
                package net.minecraft.server.notifications;
                import java.util.ArrayList;
                import java.util.List;
                import net.minecraft.world.level.gamerules.GameRule;
                public class NotificationManager implements NotificationService {
                    private final List<NotificationService> services = new ArrayList<>();
                    public void registerService(NotificationService service) { services.add(service); }
                    public <T> void onGameRuleChanged(GameRule<T> rule, T value) { services.forEach(s -> s.onGameRuleChanged(rule, value)); }
                }""");
        sources.put("net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                import net.minecraft.server.notifications.NotificationManager;
                import net.minecraft.world.level.gamerules.GameRule;
                import net.minecraft.world.level.gamerules.GameRules;
                public class MinecraftServer {
                    private final NotificationManager notifications = new NotificationManager();
                    private final net.minecraft.server.level.ServerLevel overworld = new net.minecraft.server.level.ServerLevel();
                    public NotificationManager notificationManager() { return notifications; }
                    public final net.minecraft.server.level.ServerLevel overworld() { return overworld; }
                    public java.nio.file.Path world;
                    public java.nio.file.Path getWorldPath(net.minecraft.world.level.storage.LevelResource resource) { return world.resolve(resource.path); }
                    public <T> void onGameRuleChanged(GameRule<T> rule, T value) { notifications.onGameRuleChanged(rule, value); }
                }""");
        sources.put("net/fabricmc/fabric/api/event/Event.java", """
                package net.fabricmc.fabric.api.event;
                public class Event<T> {
                    public final java.util.List<Object> listeners = new java.util.ArrayList<>();
                    public void register(Object listener) { listeners.add(listener); }
                }""");
        sources.put("net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.java", """
                package net.fabricmc.fabric.api.event.lifecycle.v1;
                import net.fabricmc.fabric.api.event.Event;
                import net.minecraft.server.MinecraftServer;
                public final class ServerLifecycleEvents {
                    public static final Event<ServerStarting> SERVER_STARTING = new Event<>();
                    public static final Event<ServerStarted> SERVER_STARTED = new Event<>();
                    public interface ServerStarting { void onServerStarting(MinecraftServer server); }
                    public interface ServerStarted { void onServerStarted(MinecraftServer server); }
                }""");
        host = compile(sources);
    }

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        CALLS.clear();
        SEEN_BOOLEAN.clear();
        SEEN_INTEGER.clear();
        DEFAULTS.clear();
    }

    @Test
    @DisplayName("registration follows the host shape, and Fabric API's old classes are replaced only for a Fabric target without them")
    void followsTheHost() {
        assertTrue(LegacyGameRuleValueBridge.shape(this::hostClass).registration());
        assertFalse(LegacyGameRuleValueBridge.shape(this::hostClass).fabricApi());
        assertTrue(LegacyGameRuleValueBridge.shape(this::hostClass, true).fabricApi());
        assertFalse(LegacyGameRuleValueBridge.shape(name -> name.startsWith("net/minecraft/server/notifications/")
                ? null : hostClass(name), true).registration(), "a host without the change hook gets no registration");
        assertFalse(LegacyGameRuleValueBridge.shape(name -> null, true).registration());
    }

    @Test
    @DisplayName("an old boolean rule is registered under a snake case id, keeps its default and category, and its callback hears changes")
    void booleanRuleRegistersAndCallsBack() throws Throwable {
        Run run = transformed(false);
        Object rule = run.call("registerBoolean", new Recorder());
        Map<?, ?> registry = registered(run);
        assertSame(rule, registry.get("do_pokemon_spawning"), "the rule is in the host registry under its new id");
        assertEquals(true, invoke(rule, "defaultValue"));
        assertSame(staticField(run, "net.minecraft.world.level.gamerules.GameRuleCategory", "SPAWNING"), run.call("category", rule));

        // The host's /gamerule command writes with the server, which tells its notification services.
        Object server = run.newInstance("net.minecraft.server.MinecraftServer");
        run.callStatic("com.retromod.legacyapi.minecraft.gamerules.LegacyGameRules", "watch", server);
        Object rules = invoke(invoke(server, "overworld"), "getGameRules");
        Method set = rules.getClass().getMethod("set", rule.getClass(), Object.class, server.getClass());
        set.invoke(rules, rule, false, server);

        assertEquals(1, CALLS.size(), "the old callback runs once for the change");
        assertSame(server, CALLS.get(0)[0]);
        assertEquals(false, invoke(CALLS.get(0)[1], "get"), "the callback's value is the old boolean view of the new value");
    }

    @Test
    @DisplayName("an old integer rule and Fabric API's bounded rule keep their defaults and bounds")
    void integerRulesKeepBounds() throws Throwable {
        Run run = transformed(true);
        Object plain = run.call("registerInteger");
        assertEquals(5, invoke(plain, "defaultValue"));
        Object bounded = run.call("registerFabric", new Recorder());
        assertEquals(3, invoke(bounded, "defaultValue"));
        Object argument = invoke(bounded, "argument");
        assertEquals(1, argument.getClass().getField("min").get(argument));
        assertEquals(10, argument.getClass().getField("max").get(argument));
        assertTrue(registered(run).containsKey("fabric_bounded_rule"));
    }

    @Test
    @DisplayName("old rule names that are not ids are turned into valid ids")
    void ruleIds() throws Throwable {
        Run run = transformed(false);
        Method id = run.loadClass("com.retromod.legacyapi.minecraft.gamerules.LegacyGameRules").getDeclaredMethod("id", String.class);
        id.setAccessible(true);
        assertEquals("do_pokemon_spawning", id.invoke(null, "doPokemonSpawning"));
        assertEquals("keep_accessory_inventory", id.invoke(null, "KeepAccessoryInventory"));
        assertEquals("mod:max_mobs_2", id.invoke(null, "mod:maxMobs 2"));
    }

    @Test
    @DisplayName("an old visitor sees every registered rule by kind, and a rule's old type makes a value at its default")
    void oldVisitorSeesEveryRule() throws Throwable {
        Run run = transformed(false);
        Object bool = run.call("registerBoolean", new Recorder());
        Object integer = run.call("registerInteger");
        Object vanilla = staticField(run, "net.minecraft.world.level.gamerules.GameRules", "FIRE_DAMAGE");
        run.call("visitAll");
        assertEquals(List.of(bool, vanilla), SEEN_BOOLEAN, "every rule, in id order as the old sorted map gave them");
        assertEquals(List.of(integer), SEEN_INTEGER);
        assertEquals(List.of("true", "true"), DEFAULTS, "createRule() serializes each boolean rule's default");
        assertEquals(3, run.call("typeCount"), "GAME_RULE_TYPES lists the vanilla rule and both old ones");
    }

    @Test
    @DisplayName("the old no-argument rules hold every registered rule at its default")
    void noArgumentRules() throws Throwable {
        Run run = transformed(false);
        Object rule = run.call("registerBoolean", new Recorder());
        Object rules = run.call("newRules");
        assertEquals("net.minecraft.world.level.gamerules.GameRules", rules.getClass().getName());
        assertEquals(true, rules.getClass().getMethod("get", rule.getClass()).invoke(rules, rule));
    }

    @Test
    @DisplayName("a value saved under the old name by an upgraded world carries over to the new id, from either save file")
    void savedValuesCarryOver(@TempDir Path world) throws Throwable {
        Files.createDirectories(world.resolve("data/minecraft"));
        Files.writeString(world.resolve("data/minecraft/game_rules.dat"), "doPokemonSpawning=false\nspawnRadius=9\n");
        Run run = transformed(false);
        Object bool = run.call("registerBoolean", new Recorder());
        Object integer = run.call("registerInteger");
        Object server = serverIn(run, world);
        assertEquals(false, ruleValue(server, bool));
        assertEquals(9, ruleValue(server, integer));
        assertTrue(CALLS.isEmpty(), "loading a saved value is not a change, so the old callback does not run");
    }

    @Test
    @DisplayName("a 1.21.11 level file carries over too, and a value set since the upgrade or one the rule cannot read stays")
    void levelFileAndKeptValues(@TempDir Path world) throws Throwable {
        Files.writeString(world.resolve("level.dat"), "doPokemonSpawning=false\nspawnRadius=lots\n");
        Run run = transformed(false);
        Object bool = run.call("registerBoolean", new Recorder());
        Object integer = run.call("registerInteger");
        Object server = serverIn(run, world);
        assertEquals(false, ruleValue(server, bool), "the 1.21.11 level file's value carries over");
        assertEquals(5, ruleValue(server, integer), "a value the rule cannot read leaves the default");

        Object changed = run.newInstance("net.minecraft.server.MinecraftServer");
        changed.getClass().getField("world").set(changed, world);
        Object rules = invoke(invoke(changed, "overworld"), "getGameRules");
        rules.getClass().getMethod("set", integer.getClass(), Object.class, changed.getClass()).invoke(rules, integer, 7, null);
        Files.writeString(world.resolve("level.dat"), "spawnRadius=9\n");
        run.callStatic("com.retromod.legacyapi.minecraft.gamerules.LegacyGameRules", "watch", changed);
        assertEquals(7, ruleValue(changed, integer), "a value already changed from the default is kept");
    }

    @Test
    @DisplayName("an old rule whose id is a vanilla rule registers under Retromod's namespace instead of stopping the game")
    void vanillaIdMovesToRetromodNamespace() throws Throwable {
        Run run = transformed(false);
        Object moved = run.call("registerNamed", "fireDamage");
        Map<?, ?> registry = registered(run);
        assertSame(staticField(run, "net.minecraft.world.level.gamerules.GameRules", "FIRE_DAMAGE"), registry.get("fire_damage"));
        assertSame(moved, registry.get("retromod:fire_damage"));
    }

    @Test
    @DisplayName("two old mod rules with the same id fail as the old duplicate did, so no rule moves by load order")
    void modClashFails() throws Throwable {
        Run run = transformed(false);
        Object first = run.call("registerNamed", "doThing");
        IllegalStateException clash = assertThrows(IllegalStateException.class, () -> run.call("registerNamed", "doThing"));
        assertTrue(clash.getMessage().contains("do_thing"), clash.getMessage());
        assertSame(first, registered(run).get("do_thing"));
        assertFalse(registered(run).containsKey("retromod:do_thing"));
    }

    @Test
    @DisplayName("a 1.21.1 level file that 1.21.11 has not saved yet still holds the rules under GameRules, and they carry over")
    void unsavedLevelFileCarriesOver(@TempDir Path world) throws Throwable {
        Files.writeString(world.resolve("level.dat"), "@GameRules\ndoPokemonSpawning=false\n");
        Run run = transformed(false);
        Object bool = run.call("registerBoolean", new Recorder());
        assertEquals(false, ruleValue(serverIn(run, world), bool));
    }

    @Test
    @DisplayName("on Fabric the change watch starts with the server, and the carry-over waits until the server has started")
    void fabricCarriesOverOnceStarted(@TempDir Path world) throws Throwable {
        Files.writeString(world.resolve("level.dat"), "doPokemonSpawning=false\n");
        Run run = transformed(true);
        Object bool = run.call("registerBoolean", new Recorder());
        Object server = run.newInstance("net.minecraft.server.MinecraftServer");
        server.getClass().getField("world").set(server, world);

        fire(run, "SERVER_STARTING", "onServerStarting", server);
        assertEquals(true, ruleValue(server, bool), "nothing carries over while the server is still loading its levels");
        fire(run, "SERVER_STARTED", "onServerStarted", server);
        assertEquals(false, ruleValue(server, bool), "the saved value carries over once the server has started");

        Object rules = invoke(invoke(server, "overworld"), "getGameRules");
        rules.getClass().getMethod("set", bool.getClass(), Object.class, server.getClass()).invoke(rules, bool, true, server);
        assertEquals(1, CALLS.size(), "the change watch registered at the starting event hears the change");
    }

    /** Calls every listener registered on one of the Fabric API stand-in's server events. */
    private static void fire(Run run, String event, String callback, Object server) throws Exception {
        String events = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents";
        Object holder = staticField(run, events, event);
        for (Object listener : (List<?>) holder.getClass().getField("listeners").get(holder)) {
            for (Class<?> type : listener.getClass().getInterfaces()) {
                type.getMethod(callback, server.getClass()).invoke(listener, server);
            }
        }
    }

    @Test
    @DisplayName("Fabric API's rule classes are replaced only for a Fabric or Quilt target, never offline for NeoForge or Forge")
    void fabricTargetNeedsFabric() {
        try {
            com.retromod.util.McReflect.setForceNeoForge(true);
            com.retromod.core.ModVersionDetector.setDefaultPreferredLoader("neoforge");
            assertFalse(LegacyGameRuleValueBridge.fabricTarget(), "an offline NeoForge target");
            com.retromod.util.McReflect.setForceNeoForge(false);
            com.retromod.util.McReflect.setForceForge(true);
            com.retromod.core.ModVersionDetector.setDefaultPreferredLoader("forge");
            assertFalse(LegacyGameRuleValueBridge.fabricTarget(), "an offline Forge target");
            com.retromod.util.McReflect.setForceForge(false);
            com.retromod.core.ModVersionDetector.setDefaultPreferredLoader("neoforge");
            assertFalse(LegacyGameRuleValueBridge.fabricTarget(), "a NeoForge preference alone");
            com.retromod.core.ModVersionDetector.setDefaultPreferredLoader("quilt");
            assertTrue(LegacyGameRuleValueBridge.fabricTarget(), "an offline Quilt target");
            com.retromod.core.ModVersionDetector.setDefaultPreferredLoader("fabric");
            assertTrue(LegacyGameRuleValueBridge.fabricTarget(), "an offline Fabric target");
        } finally {
            com.retromod.util.McReflect.setForceNeoForge(false);
            com.retromod.util.McReflect.setForceForge(false);
            com.retromod.core.ModVersionDetector.setDefaultPreferredLoader(null);
        }
    }

    /** A stand-in server for a world folder, prepared the way a server start prepares it. */
    private static Object serverIn(Run run, Path world) throws Throwable {
        Object server = run.newInstance("net.minecraft.server.MinecraftServer");
        server.getClass().getField("world").set(server, world);
        run.callStatic("com.retromod.legacyapi.minecraft.gamerules.LegacyGameRules", "watch", server);
        return server;
    }

    private static Object ruleValue(Object server, Object rule) throws Exception {
        Object rules = invoke(invoke(server, "overworld"), "getGameRules");
        return rules.getClass().getMethod("get", rule.getClass()).invoke(rules, rule);
    }

    private Run transformed(boolean fabric) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyGameRuleValueBridge.Shape shape = LegacyGameRuleValueBridge.shape(this::hostClass, fabric);
        LegacyGameRuleCallBridge.register(transformer, shape);
        Map<String, byte[]> classes = new HashMap<>(host);
        classes.put(MOD, transformer.transformClass(oldMod(), MOD + ".class"));
        classes.put(VISITOR, transformer.transformClass(oldVisitor(), VISITOR + ".class"));
        classes.putAll(transformer.getSyntheticClasses());
        return new Run(classes);
    }

    private ClassNode hostClass(String name) {
        byte[] bytes = host.get(name);
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Map<?, ?> registered(Run run) throws Exception {
        return (Map<?, ?>) staticField(run, "net.minecraft.core.registries.BuiltInRegistries", "GAME_RULES");
    }

    private static Object staticField(Run run, String owner, String name) throws Exception {
        return run.loadClass(owner).getField(name).get(null);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /** A 1.21.1 mod's rule registration after the 26.1 class move, through vanilla and through Fabric API. */
    private static byte[] oldMod() {
        String category = "L" + OLD + "$Category;";
        String type = "L" + OLD + "$Type;";
        String register = "(Ljava/lang/String;" + category + type + ")L" + RULE + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerBoolean",
                "(Ljava/util/function/BiConsumer;)L" + RULE + ";", null, null);
        mv.visitCode();
        mv.visitLdcInsn("doPokemonSpawning");
        mv.visitFieldInsn(GETSTATIC, OLD + "$Category", "SPAWNING", category);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, OLD + "$BooleanValue", "create", "(ZLjava/util/function/BiConsumer;)" + type, false);
        mv.visitMethodInsn(INVOKESTATIC, RULES, "register", register, false);
        end(mv, ARETURN);

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerInteger", "()L" + RULE + ";", null, null);
        mv.visitCode();
        mv.visitLdcInsn("spawnRadius");
        mv.visitFieldInsn(GETSTATIC, OLD + "$Category", "MOBS", category);
        mv.visitInsn(ICONST_5);
        mv.visitMethodInsn(INVOKESTATIC, OLD + "$IntegerValue", "create", "(I)" + type, false);
        mv.visitMethodInsn(INVOKESTATIC, RULES, "register", register, false);
        end(mv, ARETURN);

        String fabricFactory = LegacyGameRuleValueBridge.FABRIC_FACTORY;
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerFabric", "(Ljava/util/function/BiConsumer;)L" + RULE + ";", null, null);
        mv.visitCode();
        mv.visitLdcInsn("fabricBoundedRule");
        mv.visitFieldInsn(GETSTATIC, OLD + "$Category", "MISC", category);
        mv.visitInsn(ICONST_3);
        mv.visitInsn(ICONST_1);
        mv.visitIntInsn(BIPUSH, 10);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, fabricFactory, "createIntRule", "(IIILjava/util/function/BiConsumer;)" + type, false);
        mv.visitMethodInsn(INVOKESTATIC, LegacyGameRuleValueBridge.FABRIC_REGISTRY, "register", register, false);
        end(mv, ARETURN);

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerNamed", "(Ljava/lang/String;)L" + RULE + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, OLD + "$Category", "PLAYER", category);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESTATIC, OLD + "$BooleanValue", "create", "(Z)" + type, false);
        mv.visitMethodInsn(INVOKESTATIC, RULES, "register", register, false);
        end(mv, ARETURN);

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "visitAll", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, VISITOR);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, VISITOR, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESTATIC, RULES, "visitGameRuleTypes", "(L" + OLD + "$GameRuleTypeVisitor;)V", false);
        end(mv, RETURN);

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "typeCount", "()I", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, RULES, "GAME_RULE_TYPES", "Ljava/util/Map;");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "size", "()I", true);
        end(mv, IRETURN);

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "newRules", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, RULES);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, RULES, "<init>", "()V", false);
        end(mv, ARETURN);

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "category", "(L" + RULE + ";)" + category, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, RULE, "getCategory", "()" + category, false);
        end(mv, ARETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An old mod's visitor of every rule, after the 26.1 class move: it records what it sees in the test's lists. */
    private static byte[] oldVisitor() {
        String visitor = OLD + "$GameRuleTypeVisitor";
        String visit = "(L" + RULE + ";L" + OLD + "$Type;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, VISITOR, null, "java/lang/Object", new String[] {visitor});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        end(init, RETURN);

        MethodVisitor bool = cw.visitMethod(ACC_PUBLIC, "visitBoolean", visit, null, null);
        bool.visitCode();
        record(bool, "SEEN_BOOLEAN", 1);
        bool.visitFieldInsn(GETSTATIC, TEST, "DEFAULTS", "Ljava/util/List;");
        bool.visitVarInsn(ALOAD, 2);
        bool.visitMethodInsn(INVOKEVIRTUAL, OLD + "$Type", "createRule", "()L" + OLD + "$Value;", false);
        bool.visitMethodInsn(INVOKEVIRTUAL, OLD + "$Value", "serialize", "()Ljava/lang/String;", false);
        bool.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        bool.visitInsn(POP);
        end(bool, RETURN);

        MethodVisitor integer = cw.visitMethod(ACC_PUBLIC, "visitInteger", visit, null, null);
        integer.visitCode();
        record(integer, "SEEN_INTEGER", 1);
        end(integer, RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Adds local {@code slot} to the test's list {@code field}. */
    private static void record(MethodVisitor mv, String field, int slot) {
        mv.visitFieldInsn(GETSTATIC, TEST, field, "Ljava/util/List;");
        mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
    }

    private static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The transformed mod class, Retromod's generated and rebuilt classes and the stand-ins in one loader. */
    private static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes;

        Run(Map<String, byte[]> classes) {
            super(LegacyGameRuleRegistrationTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Object newInstance(String name) throws Exception {
            return loadClass(name).getConstructor().newInstance();
        }

        Object call(String name, Object... args) throws Throwable {
            return callStatic(MOD.replace('/', '.'), name, args);
        }

        Object callStatic(String owner, String name, Object... args) throws Throwable {
            for (Method method : loadClass(owner).getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    try {
                        return method.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no method " + owner + "." + name);
        }
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = stubDir.resolve("src");
        Path out = stubDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.x stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 26.x stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
