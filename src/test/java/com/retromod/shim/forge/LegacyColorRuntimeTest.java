/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
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
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Two old mods' color handlers, each through its own embedded copy, on Forge's real EventBus 7 with stand-in client classes. */
class LegacyColorRuntimeTest {

    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    private static final String LEGACYAPI = "com/retromod/legacyapi/";
    private static final String SECOND = "com/retromod/embedded/second/";
    private static final String BLOCK_EVENT = "net/minecraftforge/client/event/RegisterColorHandlersEvent$Block";
    private static final String SHARED_SLOTS = "retromod.shared.forge.itemColors.";
    // FML's loading states are left out, so the rebuilt dispatch treats loading as done, as on a running client.
    private static final List<String> LEFT_OUT = List.of("net/minecraftforge/fml/ModLoader", "net/minecraftforge/fml/core/ModStateProvider");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion("1.20.1");
        clearSharedSlots();
    }

    @AfterEach
    void restore() {
        clearSharedSlots();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    // The shared slots live in system properties, so no test sees another's copies.
    private static void clearSharedSlots() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(SHARED_SLOTS));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("Every block handler runs first, then each mod's item handlers in mod order, and the colors reach the tint sources")
    void blockThenItemColorsAcrossTwoCopies(String host) throws Throwable {
        Game game = game(host);
        // FML's mod order is moda, modb, but modb's copy installs first.
        game.modContainer("moda");
        game.modContainer("modb");
        game.construct("modb", SECOND);
        game.construct("moda", "");
        game.construct("moda", "");
        game.subscribeItems("", "moda/ColorsA", "moda");
        game.subscribeItems(SECOND, "modb/ColorsB", "modb");
        // Block handlers as FML adds a translated @SubscribeEvent: on the host's bus, one of them after every install.
        game.addBlockHandler("moda/ColorsA", "onBlockColors", (byte) 0);
        game.addBlockHandler("modb/ColorsB", "onBlockColors", (byte) 64);
        game.addBlockHandler("moda/ColorsA", "lateBlockColors", (byte) -127);

        Object blockColors = game.newBlockColors();
        game.postBlockColors(blockColors);

        assertEquals(List.of("B block high", "A block normal", "A block lowest", "A item sees B leaves aa", "B item"),
                game.notes(), "every block handler before the first item handler, item handlers in mod order;"
                        + " BlockColors.getColor answers RGB, as Forge 1.20.1 did");

        // Old handler A colors tint indexes 0, 2 and 3 and leaves 1 untinted with -1; the host stops at index 4.
        Object leavesA = game.state("LEAVES_A");
        assertEquals(0xFF225500, game.tint(blockColors, leavesA, 0), "old RGB is drawn opaque");
        assertEquals(-1, game.tint(blockColors, leavesA, 1), "-1 stays -1, no tint");
        assertEquals(0xFF225503, game.tint(blockColors, leavesA, 3));
        assertNull(game.tintSource(blockColors, leavesA, 4), "past the old handler's slots the quad stays untinted");
        assertEquals(0xFF0000AA, game.tint(blockColors, game.state("LEAVES_B"), 0), "BlockColors.register from the other copy");

        // Each mod got its own item tint type, and every type reads the one item color table.
        Object tintA = game.itemTint("moda", 0);
        Object tintB = game.itemTint("modb", 1);
        assertEquals(0xFF225500, game.calculate(tintA, game.item("LEAVES_A")), "a block item takes its block's old color");
        assertEquals(-1, game.calculate(game.itemTint("moda", 1), game.item("LEAVES_A")), "the block's -1 stays no tint");
        assertEquals(-1, game.calculate(tintA, game.item("GEM_A")), "an item color's -1 for index 0");
        assertEquals(0xFF123456, game.calculate(tintB, game.item("GEM_A")),
                "Forge 1.20.1 drew item colors opaque whatever alpha the handler returned, at index 1");
        assertEquals(0xFF00FF00, game.calculate(tintA, game.item("PLAIN_B")), "Minecraft.getItemColors() from the other copy");
        assertEquals(-1, game.calculate(tintA, game.item("STONE")), "an item no mod colors stays untinted");

        // A handler that reaches a removed vanilla API while the game draws leaves its tint untinted.
        assertEquals(-1, game.tint(blockColors, game.state("LEAVES_C"), 0), "a block handler's LinkageError");
        assertEquals(-1, game.calculate(tintA, game.item("BROKEN")), "an item handler's RuntimeException");
        assertEquals(-1, game.calculate(tintA, game.item("BROKEN")), "and again, noted once");
    }

    @Test
    @DisplayName("On a dedicated server the constructor's installs return before loading any client class")
    void dedicatedServerInstallLoadsNoClientClass() throws Throwable {
        Path eventBus = eventBusJar("26.2");
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge 26.2");
        Map<String, byte[]> classes = new HashMap<>(jarClasses(eventBus));
        transformer.getSyntheticClasses().forEach(classes::put);
        classes.putAll(compile(dir.resolve("server"), Map.of(
                "net/minecraftforge/fml/ModContainer.java", """
                        package net.minecraftforge.fml;
                        public class ModContainer {
                            public final String getNamespace() { return "servermod"; }
                            public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return null; }
                        }""",
                "net/minecraftforge/fml/ModLoadingContext.java", """
                        package net.minecraftforge.fml;
                        public class ModLoadingContext {
                            public static ModLoadingContext get() { return new ModLoadingContext(); }
                            public String getActiveNamespace() { return "servermod"; }
                            public ModContainer getContainer() { return new ModContainer(); }
                        }"""), eventBus));
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        ClassLoader server = loader(classes, requested);

        // Called as the constructor calls it: reflection over every method would load each parameter type.
        MethodHandles.publicLookup().findStatic(Class.forName(dotted(LegacyForgeColorBridge.INSTALLER), true, server),
                "install", MethodType.methodType(void.class)).invoke();

        List<String> client = requested.stream().filter(name -> name.startsWith("net.minecraft.client.")).distinct().toList();
        assertEquals(List.of("net.minecraft.client.Minecraft"), client, "only the client probe, which finds nothing");
        assertTrue(requested.stream().noneMatch(name -> name.endsWith("LegacyItemColorPost")), "the client part never loads");
        assertTrue(System.getProperties().keySet().stream().noneMatch(key -> key.toString().startsWith(SHARED_SLOTS)),
                "a server lists no copy");
    }

    // ---- the game ----

    private Game game(String host) throws Exception {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host);
        Path hostDir = dir.resolve("host");
        Map<String, byte[]> standIns = compile(hostDir, hostSources(), eventBus);
        Map<String, byte[]> classes = new HashMap<>(jarClasses(eventBus));
        // Every other host class is its compile-only stub.
        for (String root : List.of("net", "com/mojang")) {
            try (Stream<Path> files = Files.walk(LEGACY_CLASSES.resolve(root))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = LEGACY_CLASSES.relativize(file).toString().replace(File.separatorChar, '/');
                    name = name.substring(0, name.length() - ".class".length());
                    if (name.startsWith("net/minecraftforge/eventbus/") || standIns.containsKey(name) || LEFT_OUT.contains(name)) continue;
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
        // The old mods, compiled against Forge 1.20.1's color API and translated as Retromod translates them.
        Map<String, byte[]> oldMods = compile(dir.resolve("old"), oldModSources(), eventBus, hostDir.resolve("out"));
        for (String mod : List.of("moda/ColorsA", "modb/ColorsB")) {
            for (Map.Entry<String, byte[]> entry : oldMods.entrySet()) {
                if (!entry.getKey().startsWith(mod)) continue;
                byte[] translated = transformer.transformClass(entry.getValue(), entry.getKey() + ".class");
                classes.put(entry.getKey(), mod.startsWith("modb") ? remap(translated, relocation()) : translated);
            }
        }
        Game game = new Game(loader(classes, null));
        game.type("net/minecraftforge/eventbus/api/bus/BusGroup").getMethod("startup").invoke(game.defaultGroup());
        return game;
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

        Object defaultGroup() throws Exception {
            return type("net/minecraftforge/eventbus/api/bus/BusGroup").getField("DEFAULT").get(null);
        }

        @SuppressWarnings("unchecked")
        Object modContainer(String modId) throws Exception {
            List<Object> mods = (List<Object>) type("net/minecraftforge/fml/ModList").getField("MODS").get(null);
            for (Object mod : mods) {
                if (mod.getClass().getMethod("getModId").invoke(mod).equals(modId)) return mod;
            }
            Class<?> busGroup = type("net/minecraftforge/eventbus/api/bus/BusGroup");
            Object group = busGroup.getMethod("create", String.class, Class.class)
                    .invoke(null, modId, type("net/minecraftforge/fml/event/IModBusEvent"));
            busGroup.getMethod("startup").invoke(group);
            Object mod = type("net/minecraftforge/fml/ModContainer").getConstructor(String.class, busGroup).newInstance(modId, group);
            mods.add(mod);
            return mod;
        }

        /** One mod constructing: FML names it, then the two install calls Retromod put at the head of its constructor. */
        void construct(String modId, String copy) throws Throwable {
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, modContainer(modId));
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            lookup.findStatic(type(copy + LegacyClientRenderBootstrapInjector.BOOTSTRAP), "install", MethodType.methodType(void.class)).invoke();
            lookup.findStatic(type(copy + LegacyForgeColorBridge.INSTALLER), "install", MethodType.methodType(void.class)).invoke();
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, null);
        }

        /** {@code LegacyEvents.subscribe(modGroup, Colors.class)} in one copy, as the bus bridge's register does. */
        void subscribeItems(String copy, String listeners, String modId) throws Exception {
            Object mod = modContainer(modId);
            Object group = mod.getClass().getMethod("getModBusGroup").invoke(mod);
            for (Method method : type(copy + LEGACYAPI + "forge/eventbus/LegacyEvents").getMethods()) {
                if (method.getName().equals("subscribe") && method.getParameterCount() == 2) {
                    method.invoke(null, group, type(listeners));
                    return;
                }
            }
            throw new AssertionError("LegacyEvents has no subscribe");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void addBlockHandler(String owner, String name, byte priority) throws Exception {
            Class<?> event = type(BLOCK_EVENT);
            Method handler = type(owner).getMethod(name, event);
            Consumer<Object> call = value -> {
                try {
                    handler.invoke(null, value);
                } catch (InvocationTargetException failure) {
                    throw new AssertionError("the block handler threw", failure.getCause());
                } catch (IllegalAccessException failure) {
                    throw new AssertionError(failure);
                }
            };
            Object bus = event.getField("BUS").get(null);
            type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("addListener", byte.class, Consumer.class)
                    .invoke(bus, priority, (Consumer) call);
        }

        Object newBlockColors() throws Exception {
            return type("net/minecraft/client/color/block/BlockColors").getConstructor().newInstance();
        }

        /** {@code ForgeHooksClient.onBlockColorsInit}: the host posts its block color event on the event's own bus. */
        void postBlockColors(Object blockColors) throws Exception {
            Class<?> event = type(BLOCK_EVENT);
            Object bus = event.getField("BUS").get(null);
            Method post = type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("post", type("net/minecraftforge/eventbus/internal/Event"));
            try {
                post.invoke(bus, event.getConstructor(type("net/minecraft/client/color/block/BlockColors")).newInstance(blockColors));
            } catch (InvocationTargetException failure) {
                throw new AssertionError("the post threw", failure.getCause());
            }
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

        Object tintSource(Object blockColors, Object state, int index) throws Exception {
            return blockColors.getClass().getMethod("getTintSource", state.getClass(), int.class).invoke(blockColors, state, index);
        }

        int tint(Object blockColors, Object state, int index) throws Exception {
            Object source = tintSource(blockColors, state, index);
            assertNotNull(source, "a tint source at index " + index);
            return (Integer) type("net/minecraft/client/color/block/BlockTintSource").getMethod("color", state.getClass())
                    .invoke(source, state);
        }

        /** The tint a mod's {@code <mod>:retromod_legacy_color} type decodes from {@code {"index": n}}. */
        Object itemTint(String modId, int index) throws Exception {
            Object codec = type("net/minecraft/client/color/item/ItemTintSources").getMethod("type", String.class)
                    .invoke(null, modId + ":retromod_legacy_color");
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

    /** Client, world, codec and FML classes that behave as far as the colors need. */
    static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String id;
                    private Identifier(String id) { this.id = id; }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace + ":" + path); }
                    @Override public String toString() { return id; }
                }""");
        sources.put("net/minecraft/world/level/ItemLike.java",
                "package net.minecraft.world.level; public interface ItemLike { net.minecraft.world.item.Item asItem(); }");
        sources.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                public class Item implements net.minecraft.world.level.ItemLike {
                    private final String name;
                    public Item(String name) { this.name = name; }
                    public Item asItem() { return this; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack {
                    private final Item item;
                    public ItemStack(net.minecraft.world.level.ItemLike item) { this.item = item.asItem(); }
                    public Item getItem() { return item; }
                }""");
        sources.put("net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                public class Block implements net.minecraft.world.level.ItemLike {
                    private final net.minecraft.world.item.Item item;
                    private final net.minecraft.world.level.block.state.BlockState state = new net.minecraft.world.level.block.state.BlockState(this);
                    public Block(String name) { this.item = new net.minecraft.world.item.Item(name); }
                    public net.minecraft.world.item.Item asItem() { return item; }
                    public net.minecraft.world.level.block.state.BlockState defaultBlockState() { return state; }
                }""");
        sources.put("net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                public class BlockState {
                    private final net.minecraft.world.level.block.Block block;
                    public BlockState(net.minecraft.world.level.block.Block block) { this.block = block; }
                    public net.minecraft.world.level.block.Block getBlock() { return block; }
                }""");
        sources.put("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}");
        sources.put("net/minecraft/client/renderer/block/BlockAndTintGetter.java",
                "package net.minecraft.client.renderer.block; public interface BlockAndTintGetter {}");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public static Minecraft getInstance() { return INSTANCE; }
                }""");
        // Minecraft 26.2's block colors: a list of tint sources per block, null past its end.
        sources.put("net/minecraft/client/color/block/BlockTintSource.java", """
                package net.minecraft.client.color.block;
                import net.minecraft.world.level.block.state.BlockState;
                public interface BlockTintSource {
                    int color(BlockState state);
                    default int colorInWorld(BlockState state, net.minecraft.client.renderer.block.BlockAndTintGetter level,
                            net.minecraft.core.BlockPos pos) { return color(state); }
                }""");
        sources.put("net/minecraft/client/color/block/BlockColors.java", """
                package net.minecraft.client.color.block;
                import java.util.List;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.BlockState;
                public class BlockColors {
                    private final java.util.Map<Block, List<BlockTintSource>> sources = new java.util.HashMap<>();
                    public List<BlockTintSource> getTintSources(BlockState state) { return sources.getOrDefault(state.getBlock(), List.of()); }
                    public BlockTintSource getTintSource(BlockState state, int index) {
                        List<BlockTintSource> list = getTintSources(state);
                        return index >= list.size() ? null : list.get(index);
                    }
                    public void register(List<BlockTintSource> list, Block... blocks) { for (Block block : blocks) sources.put(block, list); }
                }""");
        sources.put("net/minecraftforge/client/event/RegisterColorHandlersEvent.java", """
                package net.minecraftforge.client.event;
                import net.minecraft.client.color.block.BlockColors;
                import net.minecraftforge.eventbus.api.bus.EventBus;
                public interface RegisterColorHandlersEvent {
                    record Block(BlockColors getBlockColors) implements net.minecraftforge.eventbus.api.event.RecordEvent, RegisterColorHandlersEvent {
                        public static final EventBus<Block> BUS = EventBus.create(Block.class);
                        public void register(java.util.List<net.minecraft.client.color.block.BlockTintSource> sources,
                                net.minecraft.world.level.block.Block... blocks) { getBlockColors.register(sources, blocks); }
                    }
                }""");
        // Item tint types: a private id mapper that refuses one codec under two ids, as the host's BiMap does.
        sources.put("net/minecraft/util/ExtraCodecs.java", """
                package net.minecraft.util;
                public class ExtraCodecs {
                    public static class LateBoundIdMapper<I, V> {
                        private final java.util.Map<String, V> byId = new java.util.HashMap<>();
                        public LateBoundIdMapper<I, V> put(I id, V value) {
                            if (byId.containsValue(value)) throw new IllegalArgumentException("value already present: " + value);
                            byId.put(id.toString(), value);
                            return this;
                        }
                        public V get(String id) { return byId.get(id); }
                    }
                }""");
        sources.put("net/minecraft/client/color/item/ItemTintSource.java", """
                package net.minecraft.client.color.item;
                public interface ItemTintSource {
                    int calculate(net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.world.entity.LivingEntity entity);
                    com.mojang.serialization.MapCodec<? extends ItemTintSource> type();
                }""");
        sources.put("net/minecraft/client/color/item/ItemTintSources.java", """
                package net.minecraft.client.color.item;
                public class ItemTintSources {
                    private static final net.minecraft.util.ExtraCodecs.LateBoundIdMapper<net.minecraft.resources.Identifier,
                            com.mojang.serialization.MapCodec<? extends ItemTintSource>> ID_MAPPER = new net.minecraft.util.ExtraCodecs.LateBoundIdMapper<>();
                    public static Object type(String id) { return ID_MAPPER.get(id); }
                }""");
        sources.put("com/mojang/serialization/Codec.java", """
                package com.mojang.serialization;
                public interface Codec<A> {
                    com.mojang.serialization.codecs.PrimitiveCodec<Integer> INT = new com.mojang.serialization.codecs.PrimitiveCodec<>() {};
                    @SuppressWarnings("unchecked")
                    default MapCodec<A> fieldOf(String name) { return new MapCodec<>(value -> (A) value); }
                }""");
        sources.put("com/mojang/serialization/codecs/PrimitiveCodec.java",
                "package com.mojang.serialization.codecs; public interface PrimitiveCodec<A> extends com.mojang.serialization.Codec<A> {}");
        sources.put("com/mojang/serialization/MapCodec.java", """
                package com.mojang.serialization;
                import java.util.function.Function;
                public class MapCodec<A> {
                    private final Function<Object, A> decoder;
                    public MapCodec(Function<Object, A> decoder) { this.decoder = decoder; }
                    public <S> MapCodec<S> xmap(Function<? super A, ? extends S> to, Function<? super S, ? extends A> from) {
                        return new MapCodec<>(value -> to.apply(decoder.apply(value)));
                    }
                    public A decode(Object value) { return decoder.apply(value); }
                }""");
        sources.put("net/minecraftforge/fml/ModContainer.java", """
                package net.minecraftforge.fml;
                public class ModContainer {
                    private final String id;
                    private final net.minecraftforge.eventbus.api.bus.BusGroup group;
                    public ModContainer(String id, net.minecraftforge.eventbus.api.bus.BusGroup group) { this.id = id; this.group = group; }
                    public final String getModId() { return id; }
                    public final String getNamespace() { return id; }
                    public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return group; }
                }""");
        sources.put("net/minecraftforge/fml/ModLoadingContext.java", """
                package net.minecraftforge.fml;
                public class ModLoadingContext {
                    public static ModContainer active;
                    public static ModLoadingContext get() { return new ModLoadingContext(); }
                    public String getActiveNamespace() { return active == null ? null : active.getNamespace(); }
                    public ModContainer getContainer() { return active; }
                }""");
        sources.put("net/minecraftforge/fml/ModList.java", """
                package net.minecraftforge.fml;
                public final class ModList {
                    public static final java.util.List<ModContainer> MODS = new java.util.ArrayList<>();
                    public static void forEachModInOrder(java.util.function.Consumer<ModContainer> action) { MODS.forEach(action); }
                }""");
        sources.put("testhost/Notes.java", """
                package testhost;
                import net.minecraft.world.item.Item;
                import net.minecraft.world.level.block.Block;
                public final class Notes {
                    public static final java.util.List<String> SEEN = new java.util.ArrayList<>();
                    public static final Block LEAVES_A = new Block("leaves_a");
                    public static final Block LEAVES_B = new Block("leaves_b");
                    public static final Item GEM_A = new Item("gem_a");
                    public static final Item PLAIN_B = new Item("plain_b");
                    public static final Item STONE = new Item("stone");
                    public static final Block LEAVES_C = new Block("leaves_c");
                    public static final Item BROKEN = new Item("broken");
                }""");
        return sources;
    }

    // ---- the old mods ----

    /** Forge 1.20.1's color API as the old mods saw it, in Minecraft 26.x class names, and two mods that use it. */
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
                    public int getColor(net.minecraft.world.item.ItemStack stack, int tintIndex) { return 0; }
                }""");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public net.minecraft.client.color.item.ItemColors getItemColors() { return null; }
                }""");
        sources.put("net/minecraftforge/client/event/RegisterColorHandlersEvent.java", """
                package net.minecraftforge.client.event;
                import net.minecraft.client.color.block.BlockColor;
                import net.minecraft.client.color.block.BlockColors;
                import net.minecraft.client.color.item.ItemColor;
                import net.minecraft.client.color.item.ItemColors;
                public abstract class RegisterColorHandlersEvent {
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
        sources.put("net/minecraftforge/eventbus/api/SubscribeEvent.java", """
                package net.minecraftforge.eventbus.api;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface SubscribeEvent {}""");
        sources.put("moda/ColorsA.java", """
                package moda;
                import net.minecraft.client.color.block.BlockColors;
                import net.minecraftforge.client.event.RegisterColorHandlersEvent;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import testhost.Notes;
                public class ColorsA {
                    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
                        Notes.SEEN.add("A block normal");
                        event.register((state, level, pos, tint) -> tint == 1 ? -1 : 0x225500 + tint, Notes.LEAVES_A);
                        // As BiomesOPlenty calls FoliageColor.getDefaultColor() outside a world, which 26.x removed.
                        event.register((state, level, pos, tint) -> {
                            if (level == null) throw new NoSuchMethodError("FoliageColor.getDefaultColor()");
                            return 0x123456;
                        }, Notes.LEAVES_C);
                    }
                    public static void lateBlockColors(RegisterColorHandlersEvent.Block event) {
                        Notes.SEEN.add("A block lowest");
                    }
                    @SubscribeEvent
                    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
                        BlockColors blocks = event.getBlockColors();
                        int leavesB = blocks.getColor(Notes.LEAVES_B.defaultBlockState(), null, null, 0);
                        Notes.SEEN.add("A item sees B leaves " + Integer.toHexString(leavesB));
                        event.register((stack, tint) -> blocks.getColor(Notes.LEAVES_A.defaultBlockState(), null, null, tint), Notes.LEAVES_A);
                        event.register((stack, tint) -> tint == 0 ? -1 : 0x80123456, Notes.GEM_A);
                        event.register((stack, tint) -> { throw new IllegalStateException("no level"); }, Notes.BROKEN);
                    }
                }""");
        sources.put("modb/ColorsB.java", """
                package modb;
                import net.minecraft.client.Minecraft;
                import net.minecraftforge.client.event.RegisterColorHandlersEvent;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import testhost.Notes;
                public class ColorsB {
                    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
                        Notes.SEEN.add("B block high");
                        event.getBlockColors().register((state, level, pos, tint) -> 0x0000AA, Notes.LEAVES_B);
                    }
                    @SubscribeEvent
                    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
                        Notes.SEEN.add("B item");
                        Minecraft.getInstance().getItemColors().register((stack, tint) -> 0x00FF00, Notes.PLAIN_B);
                    }
                }""");
        return sources;
    }

    // ---- class loading ----

    static ClassLoader loader(Map<String, byte[]> classes, List<String> requested) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (requested != null) requested.add(name);
                return super.loadClass(name, resolve);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** Compiles sources against the given class path entries, then the compiled stubs. */
    static Map<String, byte[]> compile(Path work, Map<String, String> sources, Path... classpath) throws Exception {
        Path src = work.resolve("src");
        Path out = work.resolve("out");
        List<String> entries = new ArrayList<>();
        for (Path entry : classpath) entries.add(entry.toString());
        entries.add(LEGACY_CLASSES.toString());
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none",
                "-classpath", String.join(File.pathSeparator, entries)));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    static Map<String, byte[]> jarClasses(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.startsWith("META-INF/")) continue;
                classes.put(name.substring(0, name.length() - ".class".length()), zip.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /** The EventBus 7 jar of Forge {@code host}: a system property, Prism's libraries, then a local copy. */
    private static Path eventBusJar(String host) {
        String version = host.startsWith("26.2") ? "7.0.1" : "7.0.6";
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("retromod.test.eventbus" + version.replace(".", ""));
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        String home = System.getProperty("user.home");
        candidates.add(Path.of(home, "Library/Application Support/PrismLauncher/libraries/net/minecraftforge/eventbus",
                version, "eventbus-" + version + ".jar"));
        if (version.equals("7.0.6")) candidates.add(Path.of(home, ".claude/jobs/3da2a019/tmp/forge263/eventbus-7.0.6.jar"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
