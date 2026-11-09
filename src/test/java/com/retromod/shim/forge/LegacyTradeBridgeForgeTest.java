/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.enumValue;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.handler;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.hasAutoSubscriber;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.subscriber;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * GlitchCore 1.20.1 on Forge 26.2: its subscriber class failed to load with NoClassDefFoundError on
 * WandererTradesEvent, which Forge 26.2 removed along with VillagerTradesEvent.
 */
class LegacyTradeBridgeForgeTest {

    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String SUBSCRIBE7 = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";
    private static final String BUS_ENUM = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;";
    private static final String VILLAGER = LegacyTradeBridge.FORGE_VILLAGER_EVENT;
    private static final String WANDERER = LegacyTradeBridge.FORGE_WANDERER_EVENT;
    private static final String HANDLERS = "mod/Trades";
    private static final String STACK = "net/minecraft/world/item/ItemStack";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        host("26.2", true, false);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    private void host(String target, boolean forge, boolean neoForge) {
        RetromodVersion.TARGET_MC_VERSION = target;
        McReflect.setForceForge(forge);
        McReflect.setForceNeoForge(neoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On Forge the trade events are EventBus 7 events that keep their own bus in BUS, and they verify")
    void eventsAreEventBus7Events() throws Exception {
        for (byte[] event : List.of(LegacyTradeBridge.generateVillagerEvent(LegacyTradeBridge.FORGE_EVENT),
                LegacyTradeBridge.generateWandererEvent(LegacyTradeBridge.FORGE_EVENT))) {
            ClassNode node = ModConstructorHooksTest.node(event);
            assertEquals(LegacyTradeBridge.FORGE_EVENT, node.superName, node.name);
            FieldNode bus = node.fields.stream().filter(f -> f.name.equals("BUS")).findFirst().orElseThrow();
            assertEquals("L" + LegacyTradeBridge.FORGE_EVENT_BUS + ";", bus.desc);
            assertEquals(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, bus.access);
            MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
            assertEquals(List.of(LegacyTradeBridge.FORGE_EVENT_BUS + ".create(Ljava/lang/Class;)L"
                    + LegacyTradeBridge.FORGE_EVENT_BUS + ";"), calls(clinit), "the bus EventBus 7 makes for the class");
            MethodNode constructor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
            assertEquals(List.of(LegacyTradeBridge.FORGE_EVENT + ".<init>()V"), calls(constructor));
            for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    @Test
    @DisplayName("The Forge 26.2 shim gives GlitchCore's subscriber the generated events, EventBus 7 listeners and the store start")
    void glitchCoreSubscriber() {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        String name = "glitchcore/forge/handlers/VillagerTradesEventHandler";
        byte[] original = subscriber(name, List.of("bus", enumValue(BUS_ENUM, "FORGE")),
                handler("onWandererTrades", WANDERER, OLD_SUBSCRIBE), handler("onVillagerTrades", VILLAGER, OLD_SUBSCRIBE));

        ClassNode node = ModConstructorHooksTest.node(
                LegacyTradeBridge.watchHandlers(transformer.transformClass(original, name + ".class")));
        assertEquals("(L" + LegacyTradeBridge.WANDERER_EVENT + ";)V", method(node, "onWandererTrades").desc);
        assertEquals("(L" + LegacyTradeBridge.VILLAGER_EVENT + ";)V", method(node, "onVillagerTrades").desc);
        for (String handler : List.of("onWandererTrades", "onVillagerTrades")) {
            assertEquals(SUBSCRIBE7, method(node, handler).visibleAnnotations.get(0).desc, "FML 26.2 subscribes it itself");
        }
        assertEquals(List.of(LegacyTradeBridge.STORE + ".watch()V"), calls(method(node, "<clinit>")));
        assertEquals(LegacyTradeBridge.FORGE_EVENT,
                ModConstructorHooksTest.node(transformer.getSyntheticClasses().get(LegacyTradeBridge.VILLAGER_EVENT)).superName);
    }

    @Test
    @DisplayName("A lone trade handler stays with FML, whose companion could not find the embedded event; a host event's still moves")
    void loneTradeHandlerStaysWithFml() {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        String name = "net/Pandarix/betterarcheology/event/ModEvents";
        byte[] trades = transformer.transformClass(subscriber(name, List.of(),
                handler("addCustomTrades", VILLAGER, OLD_SUBSCRIBE)), name + ".class");
        List<String> companions = new ArrayList<>();
        byte[] kept = ForgeEventBusSynthetics.stripLenientAutoSubscriber(trades, (companion, bytes) -> companions.add(companion));
        assertTrue(hasAutoSubscriber(kept), "FML subscribes the class and initializes it, which starts the store");
        assertEquals(List.of(), companions, "no companion naming the event before the embed moves it");
        assertSame(trades, kept, "nothing else changes");

        byte[] started = transformer.transformClass(subscriber("mod/OnStart", List.of(),
                handler("onStart", "net/minecraftforge/event/server/ServerStartedEvent", OLD_SUBSCRIBE)), "mod/OnStart.class");
        ForgeEventBusSynthetics.stripLenientAutoSubscriber(started, (companion, bytes) -> companions.add(companion));
        assertEquals(List.of(ForgeEventBusSynthetics.companionName("mod/OnStart")), companions,
                "a lone handler of a host event still goes to its companion");
    }

    @Test
    @DisplayName("Each @Mod constructor of a jar that names a trade event starts the store first; other jars are left alone")
    void modConstructorStartsTheStore() {
        LegacyTradeBridge.registerForge(transformer);
        byte[] mod = ModConstructorHooksTest.modClass("mod/MyMod", "mymod", "java/lang/Object", (writer, owner) -> { });
        byte[] handlers = subscriber("mod/OneHandler", List.of(), handler("onTrades", VILLAGER, OLD_SUBSCRIBE));

        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, handlers));
        assertNotNull(hooks, "the jar names a trade event");
        MethodNode constructor = method(ModConstructorHooksTest.node(hooks.apply(mod)), "<init>");
        assertEquals(List.of("java/lang/Object.<init>()V", LegacyTradeBridge.STORE + ".watch()V"), calls(constructor),
                "right after the super call, while FML has the mod's namespace active");
        assertSame(handlers, hooks.apply(handlers), "only @Mod classes get the call");

        byte[] other = subscriber("mod/OnStart", List.of(),
                handler("onStart", "net/minecraftforge/event/server/ServerStartedEvent", OLD_SUBSCRIBE));
        assertNull(ModConstructorHooks.forJar(transformer, List.of(mod, other)), "a jar without trade events");
    }

    @Test
    @DisplayName("NeoForge, Fabric, and Forge before 26.2 get nothing from the Forge registration")
    void onlyForgeFrom262() {
        for (Object[] other : new Object[][] {{"26.2", false, true}, {"26.2", false, false}, {"1.21.11", true, false}}) {
            host((String) other[0], (Boolean) other[1], (Boolean) other[2]);
            LegacyTradeBridge.registerForge(transformer);
            assertFalse(transformer.getSyntheticClasses().containsKey(LegacyTradeBridge.VILLAGER_EVENT), List.of(other).toString());
            assertEquals(VILLAGER, transformer.getClassRedirects().getOrDefault(VILLAGER, VILLAGER));
        }
        host("26.3", true, false);
        LegacyTradeBridge.registerForge(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyTradeBridge.VILLAGER_EVENT), "Forge 26.3 lacks the events too");
    }

    @Test
    @DisplayName("NeoForge's generated trade classes are byte for byte what they were before Forge support")
    void neoForgeOutputUnchanged() throws Exception {
        // SHA-256 of each class as generated before the Forge branch existed (ASM 9.11.1).
        Map<String, byte[]> generated = new LinkedHashMap<>();
        generated.put("f6702b0729ba3819e4ddc19e1c651553b7bbc54ed45e1f99dad6b84d2d77f9b0", LegacyTradeBridge.generateItemListing());
        generated.put("38d6a440c0c0a587f016a4a72deceedb1f72e811cf2312d689c60ebb7163453b", LegacyTradeBridge.generateBasicListing());
        generated.put("24eda7280d391fbaa1d409dcfeea2a3c553031dbc262ac2a902bda32f43bb022", LegacyTradeBridge.generateVillagerEvent());
        generated.put("d1e4f82b1e96915765d84083bcdc17c151b4db4f71baef92c37ecda30bc49dd5", LegacyTradeBridge.generateWandererEvent());
        generated.put("41b82c562e8aa03119e56d53e02492aa726f5e761ac86c994af1c552825f8957", LegacyTradeBridge.generateProfessions());
        for (Map.Entry<String, byte[]> pinned : generated.entrySet()) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pinned.getValue()));
            assertEquals(pinned.getKey(), hash, ModConstructorHooksTest.node(pinned.getValue()).name + " changed for NeoForge");
        }
    }

    @Test
    @DisplayName("The store posts the wanderer's event, then each profession in registry order, and writes the trades")
    void storeWritesTradesOnForge(@TempDir Path dir) throws Exception {
        // The handler class initializes outside the mod's construction, where FML answers minecraft.
        TradeServer server = new TradeServer(dir, name -> true, true, "minecraft");
        assertEquals(1, server.listeners("net.minecraftforge.event.AddPackFindersEvent"), "the pack is offered at pack discovery");
        assertEquals(1, server.listeners("net.minecraftforge.event.server.ServerStartedEvent"));

        assertEquals(List.of("Wanderer", "Villager farmer", "Villager cleric"), server.start(),
                "Forge 1.20.1 posted the wandering trader's event first, then walked the professions in id order");
        Path data = server.data();
        assertEquals("{\"wants\":{\"id\":\"minecraft:emerald\",\"count\":1},\"gives\":{\"id\":\"minecraft:bread\",\"count\":6},"
                + "\"max_uses\":12,\"xp\":2,\"reputation_discount\":1.0}",
                Files.readString(data.resolve("tradetest/villager_trade/legacy/minecraft/farmer/level_1/0.json")));
        assertEquals("{\"replace\":false,\"values\":[{\"id\":\"tradetest:legacy/minecraft/farmer/level_1/0\",\"required\":false}]}",
                Files.readString(data.resolve("minecraft/tags/villager_trade/farmer/level_1.json")));
        assertTrue(Files.exists(data.resolve("tradetest/villager_trade/legacy/minecraft/cleric/level_1/0.json")));
        assertEquals("{\"wants\":{\"id\":\"minecraft:emerald\",\"count\":3},\"gives\":{\"id\":\"minecraft:bread\",\"count\":2},"
                + "\"max_uses\":5,\"xp\":1,\"reputation_discount\":1.0}",
                Files.readString(data.resolve("tradetest/villager_trade/legacy/minecraft/wandering_trader/common/0.json")));
    }

    @Test
    @DisplayName("A jar that names only the villager event, as Better Archeology does, still writes its villager trades")
    void villagerEventOnly(@TempDir Path dir) throws Exception {
        TradeServer server = new TradeServer(dir, name -> !name.equals(LegacyTradeBridge.WANDERER_EVENT), true, "tradetest");
        assertEquals(List.of("Villager farmer", "Villager cleric"), server.start());
        assertTrue(Files.exists(server.data().resolve("tradetest/villager_trade/legacy/minecraft/farmer/level_1/0.json")));
        assertFalse(Files.exists(server.data().resolve("minecraft/tags/villager_trade/wandering_trader")));
    }

    @Test
    @DisplayName("A jar that names only the wandering trader event still writes its wandering trader trades")
    void wandererEventOnly(@TempDir Path dir) throws Exception {
        TradeServer server = new TradeServer(dir, name -> !name.equals(LegacyTradeBridge.VILLAGER_EVENT), true, "tradetest");
        assertEquals(List.of("Wanderer"), server.start());
        assertTrue(Files.exists(server.data().resolve("tradetest/villager_trade/legacy/minecraft/wandering_trader/common/0.json")));
        assertFalse(Files.exists(server.data().resolve("minecraft/tags/villager_trade/farmer")));
    }

    @Test
    @DisplayName("With no mod jar holding the store, the active mod owns it, and minecraft never does")
    void ownerIsNeverMinecraft(@TempDir Path dir) throws Exception {
        TradeServer active = new TradeServer(dir.resolve("active"), name -> true, false, "tradetest");
        assertEquals(1, active.listeners("net.minecraftforge.event.server.ServerStartedEvent"));
        active.start();
        assertTrue(Files.exists(active.data().resolve("tradetest/villager_trade/legacy/minecraft/farmer/level_1/0.json")));

        TradeServer none = new TradeServer(dir.resolve("none"), name -> true, false, "minecraft");
        assertEquals(0, none.listeners("net.minecraftforge.event.AddPackFindersEvent"), "no pack named after minecraft");
        assertEquals(0, none.listeners("net.minecraftforge.event.server.ServerStartedEvent"));
        none.start();
        assertFalse(Files.exists(none.game.resolve("config")), "nothing is written");
    }

    /**
     * One translated jar on a Forge 26.2 stand-in host: the jar carries the synthetics {@code embedded}
     * accepts and handlers for the events it carries, bound as FML binds them, and its store has started.
     */
    private final class TradeServer {
        final Path game;
        final ClassLoader loader;
        final List<String> posted = new ArrayList<>();

        TradeServer(Path dir, java.util.function.Predicate<String> embedded, boolean jarHoldsStore, String active)
                throws Exception {
            transformer.clearRedirectsForTesting();
            LegacyTradeBridge.registerForge(transformer);
            boolean wanderer = embedded.test(LegacyTradeBridge.WANDERER_EVENT);
            boolean villager = embedded.test(LegacyTradeBridge.VILLAGER_EVENT);
            byte[] handlers = transformer.transformClass(tradeHandlers(wanderer, villager), HANDLERS + ".class");
            loader = forgeHost().loader(dir.resolve("host"), transformer, embedded, Map.of(HANDLERS, handlers));
            game = dir.resolve("game");
            loader.loadClass("net.minecraftforge.fml.loading.FMLPaths").getField("root").set(null, game);
            loader.loadClass("net.minecraftforge.fml.ModLoadingContext").getField("active").set(null, active);
            // Minecraft and another mod first, as FML lists them, then the mod whose scan lists the store's class when it embeds one.
            addMod("minecraft", "Lnet/minecraft/server/MinecraftServer;");
            addMod("othermod", "Lother/Thing;");
            addMod("tradetest", jarHoldsStore ? "L" + LegacyTradeBridge.STORE + ";" : "Ltradetest/Main;");
            if (wanderer) bind("Wanderer");
            if (villager) bind("Villager");
            loader.loadClass(LegacyTradeBridge.STORE.replace('/', '.')).getMethod("watch").invoke(null);
        }

        @SuppressWarnings("unchecked")
        private void addMod(String namespace, String classDescriptor) throws Exception {
            Object mod = loader.loadClass("net.minecraftforge.fml.ModContainer").getConstructor(String.class, String.class)
                    .newInstance(namespace, classDescriptor);
            ((List<Object>) loader.loadClass("net.minecraftforge.fml.ModList").getField("LOADED").get(null)).add(mod);
        }

        // FML binds a listener on the bus EventBus 7 makes for its event class in the game group.
        private void bind(String kind) throws Exception {
            Class<?> eventBus = loader.loadClass("net.minecraftforge.eventbus.api.bus.EventBus");
            Class<?> busGroup = loader.loadClass("net.minecraftforge.eventbus.api.bus.BusGroup");
            Class<?> event = loader.loadClass("com.retromod.generated.Legacy" + kind + "TradesEvent");
            Method handler = loader.loadClass("mod.Trades").getMethod("on" + kind, event);
            Consumer<Object> listener = posting -> {
                posted.add(kind.equals("Villager") ? kind + " " + profession(posting) : kind);
                try {
                    handler.invoke(null, posting);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            };
            Object bus = eventBus.getMethod("create", busGroup, Class.class).invoke(null, busGroup.getField("DEFAULT").get(null), event);
            eventBus.getMethod("addListener", Consumer.class).invoke(bus, listener);
        }

        /** Posts the server start and answers what the store posted to the jar's handlers, in order. */
        List<String> start() throws Exception {
            Class<?> started = loader.loadClass("net.minecraftforge.event.server.ServerStartedEvent");
            loader.loadClass("net.minecraftforge.eventbus.api.bus.EventBus")
                    .getMethod("post", loader.loadClass("net.minecraftforge.eventbus.internal.Event"))
                    .invoke(started.getField("BUS").get(null), started.getConstructor().newInstance());
            return posted;
        }

        Path data() {
            return game.resolve("config/retromod/legacy-trades/retromod_legacy_trades_tradetest/data");
        }

        /** How many listeners the stand-in bus of a host event holds. */
        int listeners(String event) throws Exception {
            Object bus = loader.loadClass(event).getField("BUS").get(null);
            return (Integer) bus.getClass().getMethod("listenerCount").invoke(bus);
        }

        // The event answers getType as a profession and as a key; the profession prints its name.
        private String profession(Object event) {
            for (Method method : event.getClass().getMethods()) {
                if (method.getName().equals("getType") && method.getReturnType().getName().endsWith("VillagerProfession")) {
                    try {
                        return String.valueOf(method.invoke(event));
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                }
            }
            throw new AssertionError("no getType returning the profession");
        }
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths start the store and hand the handlers embedded events, outer and nested jar alike")
    void everyPathBridgesTrades(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("trades.jar");
        Files.write(input, tradeJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", tradeSummary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("trades.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", tradeSummary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", tradeSummary(aot));

        Map<String, String> expected = new TreeMap<>(Map.of(
                "mod/TradesMod", "starts the embedded store",
                "mod/BothTrades", "[Wanderer, Villager] on embedded EventBus 7 events, subscribed by FML",
                "mod/OneTrade", "[Villager] on embedded EventBus 7 events, subscribed by FML",
                "nested:tradelib/LibMod", "starts the embedded store",
                "nested:tradelib/LibTrades", "[Wanderer] on embedded EventBus 7 events, subscribed by FML"));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    /** What RetromodForge registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    /**
     * A Forge 1.20.1 mod with a two-handler subscriber, as GlitchCore ships, and a one-handler one, as
     * Better Archeology ships, and a nested library mod with a wandering trader handler.
     */
    private static byte[] tradeJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("tradelib"));
        lib.put("tradelib/LibMod.class", ModConstructorHooksTest.modClass("tradelib/LibMod", "tradelib", "java/lang/Object", (w, o) -> { }));
        lib.put("tradelib/LibTrades.class", subscriber("tradelib/LibTrades", List.of(),
                handler("onWanderer", WANDERER, OLD_SUBSCRIBE)));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("tradesmod"));
        mod.put("mod/TradesMod.class", ModConstructorHooksTest.modClass("mod/TradesMod", "tradesmod", "java/lang/Object", (w, o) -> { }));
        mod.put("mod/BothTrades.class", subscriber("mod/BothTrades", List.of("bus", enumValue(BUS_ENUM, "FORGE")),
                handler("onWanderer", WANDERER, OLD_SUBSCRIBE), handler("onVillager", VILLAGER, OLD_SUBSCRIBE)));
        mod.put("mod/OneTrade.class", subscriber("mod/OneTrade", List.of(), handler("onVillager", VILLAGER, OLD_SUBSCRIBE)));
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, String> tradeSummary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describeTrades(file, "mod/TradesMod", List.of("mod/BothTrades", "mod/OneTrade"), "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describeTrades(lib, "tradelib/LibMod", List.of("tradelib/LibTrades"), "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    /** Whether the @Mod constructor starts an embedded store, and which embedded events each subscriber handles. */
    private static void describeTrades(JarFile jar, String modClass, List<String> subscribers, String prefix,
            Map<String, String> found) throws Exception {
        String start = "does not start the store";
        for (MethodNode method : ModConstructorHooksTest.node(read(jar, modClass)).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith(LegacyTradeBridge.STORE) && call.name.equals("watch")) {
                    boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
                    start = embedded ? "starts the embedded store" : "starts a store the jar lacks";
                }
            }
        }
        found.put(prefix + modClass, start);
        for (String subscriber : subscribers) {
            byte[] bytes = read(jar, subscriber);
            List<String> events = new ArrayList<>();
            boolean embedded = true;
            for (MethodNode method : ModConstructorHooksTest.node(bytes).methods) {
                if (!method.name.startsWith("on")) continue;
                String event = org.objectweb.asm.Type.getArgumentTypes(method.desc)[0].getInternalName();
                events.add(event.endsWith(LegacyTradeBridge.VILLAGER_EVENT) ? "Villager"
                        : event.endsWith(LegacyTradeBridge.WANDERER_EVENT) ? "Wanderer" : event);
                JarEntry eventClass = jar.getJarEntry(event + ".class");
                embedded &= event.startsWith(SyntheticEmbedder.PREFIX) && eventClass != null && LegacyTradeBridge.FORGE_EVENT
                        .equals(ModConstructorHooksTest.node(jar.getInputStream(eventClass).readAllBytes()).superName);
            }
            found.put(prefix + subscriber, events + (embedded ? " on embedded EventBus 7 events" : " on events the jar lacks")
                    + (hasAutoSubscriber(bytes) ? ", subscribed by FML" : ", not subscribed by FML"));
        }
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return jar.getInputStream(entry).readAllBytes();
    }

    /** A mod's handlers in Forge 1.20.1 names, for the events asked for: each adds one emerald trade for bread. */
    private static byte[] tradeHandlers(boolean withWanderer, boolean withVillager) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, HANDLERS, null, "java/lang/Object", null);
        if (withWanderer) {
            // onWanderer(event): event.getGenericTrades().add(new BasicItemListing(3, new ItemStack(Items.BREAD, 2), 5, 1))
            MethodVisitor wanderer = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onWanderer", "(L" + WANDERER + ";)V", null, null);
            wanderer.visitCode();
            wanderer.visitVarInsn(ALOAD, 0);
            wanderer.visitMethodInsn(INVOKEVIRTUAL, WANDERER, "getGenericTrades", "()Ljava/util/List;", false);
            addListing(wanderer, 3, 2, 5, 1);
            wanderer.visitMaxs(0, 0);
            wanderer.visitEnd();
        }
        if (withVillager) {
            // onVillager(event): ((List) event.getTrades().get(1)).add(new BasicItemListing(1, new ItemStack(Items.BREAD, 6), 12, 2))
            MethodVisitor villager = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onVillager", "(L" + VILLAGER + ";)V", null, null);
            villager.visitCode();
            villager.visitVarInsn(ALOAD, 0);
            villager.visitMethodInsn(INVOKEVIRTUAL, VILLAGER, "getTrades", "()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", false);
            villager.visitInsn(ICONST_1);
            villager.visitMethodInsn(INVOKEINTERFACE, "it/unimi/dsi/fastutil/ints/Int2ObjectMap", "get", "(I)Ljava/lang/Object;", true);
            villager.visitTypeInsn(CHECKCAST, "java/util/List");
            addListing(villager, 1, 6, 12, 2);
            villager.visitMaxs(0, 0);
            villager.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    // list.add(new BasicItemListing(emeralds, new ItemStack(Items.BREAD, count), maxTrades, xp)); return
    private static void addListing(MethodVisitor mv, int emeralds, int count, int maxTrades, int xp) {
        String listing = "net/minecraftforge/common/BasicItemListing";
        mv.visitTypeInsn(NEW, listing);
        mv.visitInsn(DUP);
        mv.visitIntInsn(BIPUSH, emeralds);
        mv.visitTypeInsn(NEW, STACK);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/world/item/Items", "BREAD", "Lnet/minecraft/world/item/Item;");
        mv.visitIntInsn(BIPUSH, count);
        mv.visitMethodInsn(INVOKESPECIAL, STACK, "<init>", "(Lnet/minecraft/world/level/ItemLike;I)V", false);
        mv.visitIntInsn(BIPUSH, maxTrades);
        mv.visitIntInsn(BIPUSH, xp);
        mv.visitMethodInsn(INVOKESPECIAL, listing, "<init>", "(IL" + STACK + ";II)V", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
    }

    /** Forge 26.2 as the store sees it: EventBus 7, FML, the two server events, and the vanilla trade types. */
    private static ForgeHostStandIns forgeHost() {
        return new ForgeHostStandIns()
                .add("net/minecraftforge/eventbus/internal/Event.java",
                        "package net.minecraftforge.eventbus.internal; public interface Event {}")
                .add("net/minecraftforge/eventbus/internal/MutableEventInternals.java",
                        "package net.minecraftforge.eventbus.internal; public abstract class MutableEventInternals {}")
                .add("net/minecraftforge/eventbus/api/event/MutableEvent.java", """
                        package net.minecraftforge.eventbus.api.event;
                        public abstract class MutableEvent extends net.minecraftforge.eventbus.internal.MutableEventInternals
                                implements net.minecraftforge.eventbus.internal.Event {}""")
                .add("net/minecraftforge/eventbus/api/bus/BusGroup.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        public interface BusGroup { BusGroup DEFAULT = new BusGroup() {}; }""")
                .add("net/minecraftforge/eventbus/api/bus/EventBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        import java.util.function.Consumer;
                        import net.minecraftforge.eventbus.internal.Event;
                        public interface EventBus<T extends Event> {
                            Object addListener(Consumer<T> listener);
                            boolean post(T event);
                            static <E extends Event> EventBus<E> create(Class<E> type) { return create(BusGroup.DEFAULT, type); }
                            static <E extends Event> EventBus<E> create(BusGroup group, Class<E> type) {
                                return net.minecraftforge.eventbus.internal.StandInBus.of(group, type);
                            }
                        }""")
                .add("net/minecraftforge/eventbus/internal/StandInBus.java", """
                        package net.minecraftforge.eventbus.internal;
                        import java.util.*;
                        import java.util.function.Consumer;
                        import net.minecraftforge.eventbus.api.bus.*;
                        public final class StandInBus<T extends Event> implements EventBus<T> {
                            private static final Map<List<Object>, StandInBus<?>> BUSES = new HashMap<>();
                            private final List<Consumer<T>> listeners = new ArrayList<>();
                            @SuppressWarnings("unchecked")
                            public static synchronized <E extends Event> EventBus<E> of(BusGroup group, Class<E> type) {
                                return (EventBus<E>) BUSES.computeIfAbsent(List.of(group, type), key -> new StandInBus<>());
                            }
                            public Object addListener(Consumer<T> listener) { listeners.add(listener); return listener; }
                            public boolean post(T event) { for (Consumer<T> l : List.copyOf(listeners)) l.accept(event); return false; }
                            public int listenerCount() { return listeners.size(); }
                        }""")
                .add("net/minecraftforge/fml/ModLoadingContext.java", """
                        package net.minecraftforge.fml;
                        public class ModLoadingContext {
                            public static String active = "minecraft";
                            public static ModLoadingContext get() { return new ModLoadingContext(); }
                            public String getActiveNamespace() { return active; }
                        }""")
                .add("net/minecraftforge/fml/ModList.java", """
                        package net.minecraftforge.fml;
                        public class ModList {
                            public static final java.util.List<ModContainer> LOADED = new java.util.ArrayList<>();
                            public static java.util.List<ModContainer> getLoadedMods() { return LOADED; }
                            public static java.util.Optional<ModContainer> getModContainerById(String id) {
                                return LOADED.stream().filter(mod -> mod.getNamespace().equals(id)).findFirst();
                            }
                        }""")
                .add("net/minecraftforge/fml/ModContainer.java", """
                        package net.minecraftforge.fml;
                        import net.minecraftforge.forgespi.language.*;
                        import net.minecraftforge.forgespi.locating.IModFile;
                        public class ModContainer {
                            private final String namespace;
                            private final IModInfo info;
                            public ModContainer(String namespace, String classDescriptor) {
                                this.namespace = namespace;
                                // A scanned class prints its descriptors, as Forge's ClassData record does.
                                ModFileScanData scan = new ModFileScanData(java.util.Set.of(
                                        "ClassData[clazz=" + classDescriptor + ", parent=Ljava/lang/Object;, interfaces=[]]"));
                                IModFile file = () -> scan;
                                IModFileInfo owningFile = () -> file;
                                this.info = () -> owningFile;
                            }
                            public final String getNamespace() { return namespace; }
                            public IModInfo getModInfo() { return info; }
                        }""")
                .add("net/minecraftforge/forgespi/language/IModInfo.java",
                        "package net.minecraftforge.forgespi.language; public interface IModInfo { IModFileInfo getOwningFile(); }")
                .add("net/minecraftforge/forgespi/language/IModFileInfo.java", """
                        package net.minecraftforge.forgespi.language;
                        public interface IModFileInfo { net.minecraftforge.forgespi.locating.IModFile getFile(); }""")
                .add("net/minecraftforge/forgespi/locating/IModFile.java", """
                        package net.minecraftforge.forgespi.locating;
                        public interface IModFile { net.minecraftforge.forgespi.language.ModFileScanData getScanResult(); }""")
                .add("net/minecraftforge/forgespi/language/ModFileScanData.java", """
                        package net.minecraftforge.forgespi.language;
                        public class ModFileScanData {
                            private final java.util.Set<Object> classes;
                            public ModFileScanData(java.util.Set<Object> classes) { this.classes = classes; }
                            public java.util.Set<Object> getClasses() { return classes; }
                        }""")
                .add("net/minecraftforge/fml/loading/FMLPaths.java", """
                        package net.minecraftforge.fml.loading;
                        public enum FMLPaths {
                            GAMEDIR;
                            public static java.nio.file.Path root;
                            public java.nio.file.Path get() { return root; }
                        }""")
                .add("net/minecraftforge/event/AddPackFindersEvent.java", """
                        package net.minecraftforge.event;
                        public final class AddPackFindersEvent implements net.minecraftforge.eventbus.internal.Event {
                            public static final net.minecraftforge.eventbus.api.bus.EventBus<AddPackFindersEvent> BUS =
                                    net.minecraftforge.eventbus.api.bus.EventBus.create(AddPackFindersEvent.class);
                        }""")
                .add("net/minecraftforge/event/server/ServerStartedEvent.java", """
                        package net.minecraftforge.event.server;
                        public final class ServerStartedEvent implements net.minecraftforge.eventbus.internal.Event {
                            public static final net.minecraftforge.eventbus.api.bus.EventBus<ServerStartedEvent> BUS =
                                    net.minecraftforge.eventbus.api.bus.EventBus.create(ServerStartedEvent.class);
                        }""")
                .add("it/unimi/dsi/fastutil/ints/Int2ObjectMap.java", """
                        package it.unimi.dsi.fastutil.ints;
                        public interface Int2ObjectMap<V> { V get(int key); V put(int key, V value); }""")
                .add("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap.java", """
                        package it.unimi.dsi.fastutil.ints;
                        public class Int2ObjectOpenHashMap<V> implements Int2ObjectMap<V> {
                            private final java.util.Map<Integer, V> map = new java.util.HashMap<>();
                            public V get(int key) { return map.get(key); }
                            public V put(int key, V value) { return map.put(key, value); }
                        }""")
                .add("net/minecraft/world/level/ItemLike.java", "package net.minecraft.world.level; public interface ItemLike {}")
                .add("net/minecraft/world/item/Item.java", """
                        package net.minecraft.world.item;
                        public class Item implements net.minecraft.world.level.ItemLike {
                            private final String id;
                            public Item(String id) { this.id = id; }
                            public String toString() { return id; }
                        }""")
                .add("net/minecraft/world/item/Items.java", """
                        package net.minecraft.world.item;
                        public final class Items {
                            public static final Item EMERALD = new Item("minecraft:emerald");
                            public static final Item BREAD = new Item("minecraft:bread");
                        }""")
                .add("net/minecraft/world/item/ItemStack.java", """
                        package net.minecraft.world.item;
                        public final class ItemStack {
                            public static final ItemStack EMPTY = new ItemStack(null, 0);
                            private final net.minecraft.world.level.ItemLike item;
                            private final int count;
                            public ItemStack(net.minecraft.world.level.ItemLike item, int count) { this.item = item; this.count = count; }
                            public boolean isEmpty() { return item == null || count <= 0; }
                            public Item getItem() { return (Item) item; }
                            public int getCount() { return count; }
                        }""")
                .add("net/minecraft/resources/ResourceKey.java", """
                        package net.minecraft.resources;
                        public final class ResourceKey<T> {
                            private final String id;
                            public ResourceKey(String id) { this.id = id; }
                            public String identifier() { return id; }
                        }""")
                .add("net/minecraft/world/entity/npc/villager/VillagerProfession.java", """
                        package net.minecraft.world.entity.npc.villager;
                        public final class VillagerProfession {
                            private final String name;
                            public VillagerProfession(String name) { this.name = name; }
                            public String toString() { return name; }
                            public net.minecraft.resources.ResourceKey<Object> getTrades(int level) {
                                return level == 1 ? new net.minecraft.resources.ResourceKey<>("minecraft:" + name + "/level_1") : null;
                            }
                        }""")
                .add("net/minecraft/core/MappedRegistry.java", """
                        package net.minecraft.core;
                        import java.util.*;
                        public final class MappedRegistry implements Iterable<Object> {
                            private final LinkedHashMap<Object, Object> byId = new LinkedHashMap<>();
                            public MappedRegistry(Object... keysAndValues) {
                                for (int i = 0; i < keysAndValues.length; i += 2) byId.put(keysAndValues[i], keysAndValues[i + 1]);
                            }
                            // Iterates in id order, while entrySet answers in another order, as a hash map does.
                            public Iterator<Object> iterator() { return byId.values().iterator(); }
                            public Set<Map.Entry<Object, Object>> entrySet() {
                                List<Map.Entry<Object, Object>> entries = new ArrayList<>(byId.entrySet());
                                Collections.reverse(entries);
                                return new LinkedHashSet<>(entries);
                            }
                            public Optional<Object> getResourceKey(Object value) {
                                return byId.entrySet().stream().filter(e -> e.getValue() == value).map(Map.Entry::getKey).findFirst();
                            }
                            public Object getKey(Object value) { return value.toString(); }
                        }""")
                .add("net/minecraft/core/registries/BuiltInRegistries.java", """
                        package net.minecraft.core.registries;
                        import net.minecraft.core.MappedRegistry;
                        import net.minecraft.resources.ResourceKey;
                        import net.minecraft.world.entity.npc.villager.VillagerProfession;
                        public final class BuiltInRegistries {
                            public static final MappedRegistry VILLAGER_PROFESSION = new MappedRegistry(
                                    new ResourceKey<>("minecraft:farmer"), new VillagerProfession("farmer"),
                                    new ResourceKey<>("minecraft:cleric"), new VillagerProfession("cleric"));
                            public static final MappedRegistry ITEM = new MappedRegistry();
                        }""");
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }
}
