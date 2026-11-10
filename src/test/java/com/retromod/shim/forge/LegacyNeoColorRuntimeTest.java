/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.neoforge.LegacyNeoForgeColorBridge;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.commons.Remapper;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.retromod.shim.forge.LegacyColorRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two old NeoForge mods' color handlers, built for 1.21.1 and 1.20.4, each through its own embedded copy, on NeoForge's real event bus. */
class LegacyNeoColorRuntimeTest {

    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    private static final String LEGACYAPI = RebuiltApiClasses.ROOT;
    private static final String SECOND = "com/retromod/embedded/second/";
    private static final String SHARED_SLOTS = "retromod.shared.forge.itemColors.";
    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    private static final List<String> BUS_JARS = List.of("net/neoforged/bus/8.0.5/bus-8.0.5.jar",
            "net/jodah/typetools/0.6.3/typetools-0.6.3.jar", "org/apache/logging/log4j/log4j-api/2.25.2/log4j-api-2.25.2.jar");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void neoForge263() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
        new NeoForge_1_21_11_to_26_1().registerRedirects(transformer);
        clearSharedSlots();
    }

    @AfterEach
    void restore() {
        clearSharedSlots();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    private static void clearSharedSlots() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(SHARED_SLOTS));
    }

    @Test
    @DisplayName("@SubscribeEvent and addListener method references get both old events, every block handler before the first item handler")
    void oldListenersGetTheEvents() throws Throwable {
        Game game = game();
        // NeoForge's mod order is moda, modb, then a mod without a bus; modb's copy installs first.
        Object busA = game.mod("moda", true);
        Object busB = game.mod("modb", true);
        game.mod("nobus", false);
        game.construct("modb", SECOND);
        game.construct("moda", "");
        // moda subscribes a class with @SubscribeEvent methods, modb adds method references, and moda's library code adds
        // one listener to modb's bus, as Zeta does for the mods that use it.
        game.call("testhost/Host", "register", busA, game.type("moda/ColorsA"));
        game.call("modb/ColorsB", "subscribe", busB);
        game.call("moda/ColorsA", "subscribeLibrary", busB);

        Object ids = game.type("net/minecraft/util/ExtraCodecs$LateBoundIdMapper").getConstructor().newInstance();
        game.postToMods("net/neoforged/neoforge/client/event/RegisterColorHandlersEvent$ItemTintSources", ids);
        Object blockColors = game.type("net/minecraft/client/color/block/BlockColors").getConstructor().newInstance();
        game.postToMods("net/neoforged/neoforge/client/event/RegisterColorHandlersEvent$BlockTintSources", blockColors);

        assertEquals(List.of("A block", "A library on modb's bus", "A block lowest", "B block", "A item sees B leaves ff0000aa", "B item"),
                game.notes(), "each copy posts in mod order a priority at a time, and every block handler runs before the item handlers");

        // The tints reach the host's block colors, opaque, with -1 left untinted.
        Object leavesA = game.state("LEAVES_A");
        assertEquals(0xFF225500, game.tint(blockColors, leavesA, 0));
        assertEquals(-1, game.tint(blockColors, leavesA, 1));
        assertEquals(0xFF0000AA, game.tint(blockColors, game.state("LEAVES_B"), 0), "BlockColors.register from the other copy");

        // Each mod's tint type, added on NeoForge's item tint event, reads the one shared item color table.
        Object tintA = game.itemTint(ids, "moda", 0);
        assertEquals(0xFF225500, game.calculate(tintA, game.item("LEAVES_A")), "a block item takes its block's color, alpha included");
        assertEquals(0x80123456, game.calculate(tintA, game.item("GEM_A")),
                "a 1.21.1 mod's translucent tint keeps its alpha, as 1.21's item renderer and 26.x draw it");
        assertEquals(0xFFABCDEF, game.calculate(game.itemTint(ids, "modb", 0), game.item("LEAVES_B")),
                "a mod built before 1.21 is drawn opaque whatever alpha it answers, as its item renderer drew it");
        assertEquals(0xFF00FF00, game.calculate(tintA, game.item("PLAIN_B")), "Minecraft.getItemColors() from the other copy, opaque");
        assertEquals(-1, game.calculate(tintA, game.item("STONE")), "an item no mod colors stays untinted");
    }

    @Test
    @DisplayName("On a dedicated server the install returns before loading any client or NeoForge class")
    void dedicatedServerInstallLoadsNoClientClass() throws Throwable {
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        ClassLoader server = loader(new HashMap<>(transformer.getSyntheticClasses()), requested);
        MethodHandles.publicLookup().findStatic(Class.forName(dotted(LegacyNeoForgeColorBridge.INSTALLER), true, server),
                "install", MethodType.methodType(void.class)).invoke();
        assertEquals(List.of("net.minecraft.client.Minecraft"),
                requested.stream().filter(name -> name.startsWith("net.")).distinct().toList(), "only the client probe, which finds nothing");
        assertTrue(requested.stream().noneMatch(name -> name.endsWith("LegacyColorEventPost")), "the client part never loads");
    }

    // ---- the game ----

    private Game game() throws Exception {
        List<Path> jars = new ArrayList<>();
        for (String jar : BUS_JARS) jars.add(LIBRARIES.resolve(jar));
        Assumptions.assumeTrue(jars.stream().allMatch(Files::isRegularFile), "no NeoForge bus 8 jars under " + LIBRARIES);
        Path bus = jars.get(0);
        Path hostDir = dir.resolve("host");
        Map<String, byte[]> standIns = compile(hostDir, hostSources(), bus);
        Map<String, byte[]> classes = new HashMap<>();
        for (Path jar : jars) classes.putAll(jarClasses(jar));
        // Log4j finds its caller through the Java 9 classes of its multi-release jar.
        classes.putAll(releaseClasses(jars.get(2), 9));
        // The bus writes a listener class for each @SubscribeEvent method with ASM, which the tests' class path has.
        classes.putAll(jarClasses(Path.of(org.objectweb.asm.Type.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        // Every other host class is its compile-only stub; the real bus stands in for NeoForge's.
        for (String root : List.of("net", "com/mojang")) {
            try (Stream<Path> files = Files.walk(LEGACY_CLASSES.resolve(root))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = LEGACY_CLASSES.relativize(file).toString().replace(File.separatorChar, '/');
                    name = name.substring(0, name.length() - ".class".length());
                    if (name.startsWith("net/neoforged/bus/") || standIns.containsKey(name)) continue;
                    classes.put(name, Files.readAllBytes(file));
                }
            }
        }
        classes.putAll(standIns);
        transformer.getSyntheticClasses().forEach(classes::put);
        // A second jar's copy, relocated under its own package as the embedder does.
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith(LEGACYAPI)) classes.put(SECOND + name, remap(bytes, relocation()));
        });
        // The old mods, compiled against NeoForge's old color API and translated as Retromod translates them: moda was
        // built for 1.21.1, modb for 1.20.4, before Minecraft's item renderer read a tint's alpha.
        Map<String, byte[]> oldMods = compile(dir.resolve("old"), oldModSources(), bus, hostDir.resolve("out"));
        for (String mod : List.of("moda/ColorsA", "modb/ColorsB")) {
            transformer.setModSourceVersion(mod.startsWith("modb") ? "1.20.4" : "1.21.1");
            for (Map.Entry<String, byte[]> entry : oldMods.entrySet()) {
                if (!entry.getKey().startsWith(mod)) continue;
                byte[] translated = transformer.transformClass(entry.getValue(), entry.getKey() + ".class");
                classes.put(entry.getKey(), mod.startsWith("modb") ? remap(translated, relocation()) : translated);
            }
        }
        return new Game(loader(classes, null));
    }

    /** A multi-release jar's classes for one Java release, under their plain names. */
    private static Map<String, byte[]> releaseClasses(Path jar, int release) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        String prefix = "META-INF/versions/" + release + "/";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                classes.put(name.substring(prefix.length(), name.length() - ".class".length()), zip.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    private static Remapper relocation() {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith(LEGACYAPI) ? SECOND + internalName : internalName;
            }
        };
    }

    /** Reflection over one test game's classes. */
    private record Game(ClassLoader loader) {

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(dotted(internalName), true, loader);
        }

        Object call(String owner, String name, Object... arguments) throws Throwable {
            for (Method method : type(owner).getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                method.setAccessible(true);
                try {
                    return method.invoke(null, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
            throw new AssertionError(owner + " has no " + name);
        }

        /** A mod in NeoForge's mod list, with a started mod bus or none; answers the bus. */
        Object mod(String id, boolean withBus) throws Throwable {
            return call("testhost/Host", "addMod", id, withBus);
        }

        /** One mod constructing: FML names it, then the install Retromod put at the head of its constructor. */
        void construct(String modId, String copy) throws Throwable {
            call("testhost/Host", "activate", modId);
            MethodHandles.publicLookup().findStatic(type(copy + LegacyNeoForgeColorBridge.INSTALLER), "install",
                    MethodType.methodType(void.class)).invoke();
            call("testhost/Host", "activate", (Object) null);
        }

        /** {@code ModLoader.postEvent}: one host event to every mod's bus, a priority at a time in mod order. */
        void postToMods(String event, Object argument) throws Throwable {
            Class<?> type = type(event);
            call("testhost/Host", "postToMods", type.getConstructors()[0].newInstance(argument));
        }

        @SuppressWarnings("unchecked")
        List<String> notes() throws Exception {
            return (List<String>) type("testhost/Notes").getField("SEEN").get(null);
        }

        Object state(String block) throws Exception {
            Object value = type("testhost/Notes").getField(block).get(null);
            return value.getClass().getMethod("defaultBlockState").invoke(value);
        }

        Object item(String field) throws Exception {
            Object like = type("testhost/Notes").getField(field).get(null);
            return type("net/minecraft/world/item/ItemStack").getConstructor(type("net/minecraft/world/level/ItemLike")).newInstance(like);
        }

        int tint(Object blockColors, Object state, int index) throws Exception {
            Object source = blockColors.getClass().getMethod("getTintSource", state.getClass(), int.class).invoke(blockColors, state, index);
            assertNotNull(source, "a tint source at index " + index);
            return (Integer) type("net/minecraft/client/color/block/BlockTintSource").getMethod("color", state.getClass())
                    .invoke(source, state);
        }

        /** The tint a mod's {@code <mod>:retromod_legacy_color} type decodes from {@code {"index": n}}. */
        Object itemTint(Object ids, String modId, int index) throws Exception {
            Object codec = ids.getClass().getMethod("get", String.class).invoke(ids, modId + ":retromod_legacy_color");
            assertNotNull(codec, modId + " has a tint type");
            return codec.getClass().getMethod("decode", Object.class).invoke(codec, index);
        }

        int calculate(Object tint, Object stack) throws Exception {
            Method calculate = type("net/minecraft/client/color/item/ItemTintSource").getMethod("calculate", stack.getClass(),
                    type("net/minecraft/client/multiplayer/ClientLevel"), type("net/minecraft/world/entity/LivingEntity"));
            return (Integer) calculate.invoke(tint, stack, null, null);
        }
    }

    // ---- stand-in host classes ----

    /** The Forge test's client, world and codec stand-ins, with FML and NeoForge 26.3's color events in place of Forge's. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>(LegacyColorRuntimeTest.hostSources());
        sources.keySet().removeIf(name -> name.startsWith("net/minecraftforge/"));
        sources.put("net/neoforged/fml/ModContainer.java", """
                package net.neoforged.fml;
                public class ModContainer {
                    private final String id;
                    private final net.neoforged.bus.api.IEventBus bus;
                    public ModContainer(String id, net.neoforged.bus.api.IEventBus bus) { this.id = id; this.bus = bus; }
                    public final String getNamespace() { return id; }
                    public net.neoforged.bus.api.IEventBus getEventBus() { return bus; }
                }""");
        sources.put("net/neoforged/fml/ModList.java", """
                package net.neoforged.fml;
                public class ModList {
                    public static final java.util.List<ModContainer> MODS = new java.util.ArrayList<>();
                    private static final ModList INSTANCE = new ModList();
                    public static ModList get() { return INSTANCE; }
                    public java.util.List<ModContainer> getSortedMods() { return MODS; }
                }""");
        sources.put("net/neoforged/fml/ModLoadingContext.java", """
                package net.neoforged.fml;
                public class ModLoadingContext {
                    public static ModContainer active;
                    public static ModLoadingContext get() { return new ModLoadingContext(); }
                    public ModContainer getActiveContainer() { return active; }
                }""");
        // NeoForge 26.3's color events: block tint sources on BlockColors, and item tint types on the id mapper.
        sources.put("net/neoforged/neoforge/client/event/RegisterColorHandlersEvent.java", """
                package net.neoforged.neoforge.client.event;
                import com.mojang.serialization.MapCodec;
                import net.minecraft.client.color.block.BlockColors;
                import net.minecraft.client.color.item.ItemTintSource;
                import net.minecraft.resources.Identifier;
                import net.minecraft.util.ExtraCodecs;
                public abstract class RegisterColorHandlersEvent extends net.neoforged.bus.api.Event implements net.neoforged.fml.event.IModBusEvent {
                    protected RegisterColorHandlersEvent() {}
                    public static class BlockTintSources extends RegisterColorHandlersEvent {
                        private final BlockColors colors;
                        public BlockTintSources(BlockColors colors) { this.colors = colors; }
                        public BlockColors getBlockColors() { return colors; }
                        public void register(java.util.List<net.minecraft.client.color.block.BlockTintSource> sources,
                                net.minecraft.world.level.block.Block... blocks) { colors.register(sources, blocks); }
                    }
                    public static class ItemTintSources extends RegisterColorHandlersEvent {
                        private final ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemTintSource>> ids;
                        public ItemTintSources(ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemTintSource>> ids) { this.ids = ids; }
                        public void register(Identifier id, MapCodec<? extends ItemTintSource> codec) { ids.put(id, codec); }
                    }
                }""");
        // FML's side: mod buses built as FML builds them, and ModLoader.postEvent's order.
        sources.put("testhost/Host.java", """
                package testhost;
                import net.neoforged.bus.api.BusBuilder;
                import net.neoforged.bus.api.EventPriority;
                import net.neoforged.bus.api.IEventBus;
                import net.neoforged.fml.ModContainer;
                import net.neoforged.fml.ModList;
                import net.neoforged.fml.ModLoadingContext;
                public final class Host {
                    public static IEventBus addMod(String id, boolean withBus) {
                        IEventBus bus = withBus ? BusBuilder.builder().markerType(net.neoforged.fml.event.IModBusEvent.class)
                                .allowPerPhasePost().build() : null;
                        if (bus != null) bus.start();
                        ModList.MODS.add(new ModContainer(id, bus));
                        return bus;
                    }
                    public static void activate(String id) {
                        ModLoadingContext.active = null;
                        for (ModContainer mod : ModList.MODS) if (mod.getNamespace().equals(id)) ModLoadingContext.active = mod;
                    }
                    public static void register(IEventBus bus, Class<?> listeners) { bus.register(listeners); }
                    public static void postToMods(net.neoforged.bus.api.Event event) {
                        for (EventPriority phase : EventPriority.values()) {
                            for (ModContainer mod : ModList.get().getSortedMods()) {
                                if (mod.getEventBus() != null) mod.getEventBus().post(phase, event);
                            }
                        }
                    }
                }""");
        return sources;
    }

    // ---- the old mods ----

    /** NeoForge's old color API as the old mods saw it, in Minecraft 26.x class names, and two mods that use it. */
    private static Map<String, String> oldModSources() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/client/color/block/BlockColor.java", """
                package net.minecraft.client.color.block;
                public interface BlockColor {
                    int getColor(net.minecraft.world.level.block.state.BlockState state, net.minecraft.client.renderer.block.BlockAndTintGetter level,
                            net.minecraft.core.BlockPos pos, int tintIndex);
                }""");
        sources.put("net/minecraft/client/color/block/BlockColors.java", """
                package net.minecraft.client.color.block;
                public class BlockColors {
                    public int getColor(net.minecraft.world.level.block.state.BlockState state, net.minecraft.client.renderer.block.BlockAndTintGetter level,
                            net.minecraft.core.BlockPos pos, int tintIndex) { return 0; }
                    public void register(BlockColor color, net.minecraft.world.level.block.Block... blocks) {}
                }""");
        sources.put("net/minecraft/client/color/item/ItemColor.java", """
                package net.minecraft.client.color.item;
                public interface ItemColor { int getColor(net.minecraft.world.item.ItemStack stack, int tintIndex); }""");
        sources.put("net/minecraft/client/color/item/ItemColors.java", """
                package net.minecraft.client.color.item;
                public class ItemColors {
                    public void register(ItemColor color, net.minecraft.world.level.ItemLike... items) {}
                }""");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public net.minecraft.client.color.item.ItemColors getItemColors() { return null; }
                }""");
        sources.put("net/neoforged/neoforge/client/event/RegisterColorHandlersEvent.java", """
                package net.neoforged.neoforge.client.event;
                import net.minecraft.client.color.block.BlockColor;
                import net.minecraft.client.color.block.BlockColors;
                import net.minecraft.client.color.item.ItemColor;
                import net.minecraft.client.color.item.ItemColors;
                public abstract class RegisterColorHandlersEvent extends net.neoforged.bus.api.Event implements net.neoforged.fml.event.IModBusEvent {
                    public static class Block extends RegisterColorHandlersEvent {
                        public BlockColors getBlockColors() { return null; }
                        public void register(BlockColor color, net.minecraft.world.level.block.Block... blocks) {}
                    }
                    public static class Item extends RegisterColorHandlersEvent {
                        public ItemColors getItemColors() { return null; }
                        public BlockColors getBlockColors() { return null; }
                        public void register(ItemColor color, net.minecraft.world.level.ItemLike... items) {}
                    }
                }""");
        sources.put("moda/ColorsA.java", """
                package moda;
                import net.minecraft.client.color.block.BlockColors;
                import net.neoforged.bus.api.EventPriority;
                import net.neoforged.bus.api.IEventBus;
                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
                import testhost.Notes;
                public class ColorsA {
                    @SubscribeEvent
                    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
                        Notes.SEEN.add("A block");
                        // A 1.21 mod answers opaque colors, since its item renderer read the alpha.
                        event.register((state, level, pos, tint) -> tint == 1 ? -1 : 0xFF225500 + tint, Notes.LEAVES_A);
                    }
                    @SubscribeEvent(priority = EventPriority.LOWEST)
                    public static void lateBlockColors(RegisterColorHandlersEvent.Block event) {
                        Notes.SEEN.add("A block lowest");
                    }
                    @SubscribeEvent
                    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
                        BlockColors blocks = event.getBlockColors();
                        int leavesB = blocks.getColor(Notes.LEAVES_B.defaultBlockState(), null, null, 0);
                        Notes.SEEN.add("A item sees B leaves " + Integer.toHexString(leavesB));
                        event.register((stack, tint) -> blocks.getColor(Notes.LEAVES_A.defaultBlockState(), null, null, tint), Notes.LEAVES_A);
                        event.register((stack, tint) -> 0x80123456, Notes.GEM_A);
                    }
                    // A library's listener on the bus of a mod that uses it.
                    public static void subscribeLibrary(IEventBus bus) {
                        bus.addListener(RegisterColorHandlersEvent.Block.class, event -> Notes.SEEN.add("A library on modb's bus"));
                    }
                }""");
        sources.put("modb/ColorsB.java", """
                package modb;
                import net.minecraft.client.Minecraft;
                import net.neoforged.bus.api.EventPriority;
                import net.neoforged.bus.api.IEventBus;
                import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
                import testhost.Notes;
                public class ColorsB {
                    public static void subscribe(IEventBus bus) {
                        bus.addListener(ColorsB::onBlockColors);
                        bus.addListener(EventPriority.HIGH, ColorsB::onItemColors);
                    }
                    private static void onBlockColors(RegisterColorHandlersEvent.Block event) {
                        Notes.SEEN.add("B block");
                        event.getBlockColors().register((state, level, pos, tint) -> 0x0000AA, Notes.LEAVES_B);
                    }
                    private static void onItemColors(RegisterColorHandlersEvent.Item event) {
                        Notes.SEEN.add("B item");
                        event.register((stack, tint) -> 0x80ABCDEF, Notes.LEAVES_B);
                        Minecraft.getInstance().getItemColors().register((stack, tint) -> 0x00FF00, Notes.PLAIN_B);
                    }
                }""");
        return sources;
    }
}
