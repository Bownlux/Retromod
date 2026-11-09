/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A NeoForge 1.21.1 mod's own single loot entry, shaped like Moonlight's, translated for 26.3 and run against the real
 * 26.3 game classes and DataFixerUpper: it decodes, expands with its weight, quality and luck, tests its condition,
 * applies its function and writes 26.3's shape back.
 */
class LegacyLootSingletonHostTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    /** The 26.3 client and the libraries its bootstrap loads. */
    private static final List<String> HOST = List.of("com/mojang/minecraft/26.3/minecraft-26.3-client.jar",
            "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar", "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
            "com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar", "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar",
            "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar", "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
            "com/mojang/logging/1.7.12/logging-1.7.12.jar", "com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar",
            "com/mojang/authlib/10.0.77/authlib-10.0.77.jar", "org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar",
            "io/netty/netty-buffer/4.2.16.Final/netty-buffer-4.2.16.Final.jar", "io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar",
            "io/netty/netty-codec-base/4.2.16.Final/netty-codec-base-4.2.16.Final.jar",
            "io/netty/netty-transport/4.2.16.Final/netty-transport-4.2.16.Final.jar",
            "io/netty/netty-handler/4.2.16.Final/netty-handler-4.2.16.Final.jar", "org/joml/joml/1.10.9/joml-1.10.9.jar",
            "com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar", "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
            "com/ibm/icu/icu4j/78.3/icu4j-78.3.jar", "commons-io/commons-io/2.20.0/commons-io-2.20.0.jar");

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the translated entry decodes, rolls by weight and luck, tests its condition, applies its function and writes 26.3 data")
    void oldEntryRunsOnTheRealHost() throws Exception {
        List<URL> host = new ArrayList<>();
        for (String jar : HOST) {
            Path file = LIBRARIES.resolve(jar);
            assumeTrue(Files.isRegularFile(file), "no local " + file.getFileName() + "; the 26.3 game jars are local test material");
            host.add(file.toUri().toURL());
        }
        Path translated = translate(LIBRARIES.resolve(HOST.get(1)));
        host.add(0, translated.toUri().toURL());

        try (URLClassLoader game = new URLClassLoader(host.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Game mc = new Game(game);
            Class<?> entryType = game.loadClass("mod.NamedItemEntry");
            Field stack = entryType.getField("STACK");
            stack.set(null, (Supplier<Object>) mc::emerald);
            Object codec = mc.codecOf(entryType.getField("CODEC").get(null));

            // Weight 5 and quality 2 at luck 1.5 weigh floor(5 + 2 * 1.5) = 8; the count function makes four.
            Object entry = mc.parse(codec, "{\"name\":\"emerald\",\"weight\":5,\"quality\":2,"
                    + "\"condition\":{\"type\":\"minecraft:random_chance\",\"chance\":1.0},"
                    + "\"modifier\":{\"type\":\"minecraft:set_count\",\"count\":4}}");
            assertEquals(1, entryType.getMethod("conditionCount").invoke(entry), "the old list holds the one condition");
            assertEquals(1, entryType.getMethod("functionCount").invoke(entry), "the old list holds the one function");
            List<Object> expanded = mc.expand(entry);
            assertEquals(1, expanded.size(), "the condition passes, so the entry expands");
            assertEquals(8, mc.weight(expanded.get(0), 1.5F));
            List<Object> stacks = mc.items(expanded.get(0));
            assertEquals(1, stacks.size());
            assertEquals(4, mc.count(stacks.get(0)), "the modifier runs on the stack the old entry makes");

            Object never = mc.parse(codec, "{\"name\":\"emerald\",\"condition\":{\"type\":\"minecraft:random_chance\",\"chance\":0.0}}");
            assertEquals(List.of(), mc.expand(never), "a condition that fails keeps the entry out");

            String written = mc.encode(codec, entry);
            for (String part : List.of("\"weight\":5", "\"quality\":2", "\"condition\"", "minecraft:set_count")) {
                assertTrue(written.contains(part), part + " in " + written);
            }

            Object built = entryType.getMethod("weighted").invoke(null);
            assertTrue(entryType.isInstance(built), "the old simple builder builds the mod's entry");
            assertEquals(3, mc.uniformWeight(built), "setWeight reaches the entry's 26.3 weight");
            assertSame(entryType.getField("TYPE").get(null), built.getClass().getMethod("codec").invoke(built),
                    "the entry's codec is the one its old getType names");

            // The reads a loot describer such as Bookshelf's makes, from a class that is not an entry.
            Class<?> reads = game.loadClass("mod.LootReads");
            Class<?> container = mc.type("net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer");
            Class<?> singleton = mc.type("net.minecraft.world.level.storage.loot.entries.UniformContainerBase");
            Object item = reads.getMethod("itemType").invoke(null);
            assertSame(item, reads.getMethod("typeOf", container).invoke(null, mc.lootItem()),
                    "a vanilla entry's type is the removed constant's");
            assertSame(entryType.getField("TYPE").get(null), reads.getMethod("typeOf", container).invoke(null, entry),
                    "an old entry's type is the one it registered");
            assertEquals(1, ((List<?>) reads.getMethod("functionsOf", singleton).invoke(null, entry)).size());
            assertEquals(1, ((List<?>) reads.getMethod("conditionsOf", container).invoke(null, entry)).size());

            // A nested table named by key, in code with no registries, rolls through the loot context's own.
            Object key = mc.lootTableKey("test/rolled");
            Object byName = reads.getMethod("nested", mc.type("net.minecraft.resources.ResourceKey")).invoke(null, key);
            assertEquals(2, mc.uniformWeight(byName));
            List<Object> rolled = mc.expandWithTable(byName, key, mc.emeraldTable());
            assertEquals(1, rolled.size(), "the named table's one item");
            assertEquals(1, mc.count(rolled.get(0)));
        }
    }

    @Test
    @DisplayName("the removed villager schedule registry reads as an empty, frozen registry under its old key")
    void removedScheduleRegistryIsEmpty() throws Exception {
        List<URL> host = new ArrayList<>();
        for (String jar : HOST) {
            Path file = LIBRARIES.resolve(jar);
            assumeTrue(Files.isRegularFile(file), "no local " + file.getFileName() + "; the 26.3 game jars are local test material");
            host.add(file.toUri().toURL());
        }
        try (URLClassLoader game = new URLClassLoader(host.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Game mc = new Game(game);
            assertThrows(NoSuchFieldException.class, () -> mc.type("net.minecraft.core.registries.BuiltInRegistries").getField("SCHEDULE"),
                    "26.3 has no schedule registry, which is why the stand-in exists");
            ClassLoader withSupport = new SupportLoader(game, com.retromod.shim.common.embedded.LegacyRemovedRegistrySupport.class,
                    com.retromod.shim.common.embedded.LegacyPublicMethods.class);
            Class<?> support = withSupport.loadClass(com.retromod.shim.common.embedded.LegacyRemovedRegistrySupport.class.getName());

            Object registry = support.getMethod("registry", String.class).invoke(null, "schedule");
            assertTrue(mc.type("net.minecraft.core.Registry").isInstance(registry));
            assertSame(registry, support.getMethod("registry", String.class).invoke(null, "schedule"), "every read is the same registry");
            assertEquals(0, ((java.util.Set<?>) registry.getClass().getMethod("keySet").invoke(registry)).size());
            Object key = support.getMethod("registryKey", String.class).invoke(null, "schedule");
            assertEquals(key, registry.getClass().getMethod("key").invoke(registry));
            assertTrue(key.toString().contains("minecraft:schedule"), key.toString());
        }
    }

    @Test
    @DisplayName("requireParameter returns a present loot parameter and throws for a missing one, as getParameter did before 26.3")
    void requireParameterThrowsLikeTheOldGetter() throws Exception {
        List<URL> host = new ArrayList<>();
        for (String jar : HOST) {
            Path file = LIBRARIES.resolve(jar);
            assumeTrue(Files.isRegularFile(file), "no local " + file.getFileName() + "; the 26.3 game jars are local test material");
            host.add(file.toUri().toURL());
        }
        try (URLClassLoader game = new URLClassLoader(host.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Game mc = new Game(game);
            Class<?> params = mc.type("net.minecraft.world.level.storage.loot.parameters.LootContextParams");
            Object origin = params.getField("ORIGIN").get(null);
            Object entity = params.getField("THIS_ENTITY").get(null);
            Object zero = mc.type("net.minecraft.world.phys.Vec3").getField("ZERO").get(null);
            Object context = mc.contextWith(origin, zero);

            Class<?> lootContext = mc.type("net.minecraft.world.level.storage.loot.LootContext");
            Class<?> contextKey = mc.type("net.minecraft.util.context.ContextKey");
            assertNull(lootContext.getMethod("getOptional", contextKey).invoke(context, entity),
                    "26.3's only getter answers a missing parameter with null");

            String name = LegacyMemberRenames.PARAMETERS.replace('/', '.');
            Class<?> helper = new GeneratedLoader(game, name, LegacyMemberRenames.generateParameters()).loadClass(name);
            Method require = helper.getMethod("requireParameter", lootContext, contextKey);
            assertSame(zero, require.invoke(null, context, origin), "a present parameter is returned as before");
            java.lang.reflect.InvocationTargetException missing = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> require.invoke(null, context, entity));
            assertInstanceOf(java.util.NoSuchElementException.class, missing.getCause(), "a missing parameter throws as before");
        }
    }

    /** Defines one generated class beside the game, as the embedder puts it in a mod. */
    private static final class GeneratedLoader extends ClassLoader {
        private final String name;
        private final byte[] bytes;

        GeneratedLoader(ClassLoader game, String name, byte[] bytes) {
            super(game);
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String requested) throws ClassNotFoundException {
            if (!requested.equals(name)) throw new ClassNotFoundException(requested);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Defines Retromod's embedded support classes beside the game, as the embedder puts them in a mod. */
    private static final class SupportLoader extends ClassLoader {
        private final Map<String, Class<?>> classes = new HashMap<>();

        SupportLoader(ClassLoader game, Class<?>... support) {
            super(game);
            for (Class<?> type : support) classes.put(type.getName(), type);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                Class<?> source = classes.get(name);
                if (source == null) return super.loadClass(name, resolve);
                try (java.io.InputStream in = source.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    /** The fixture mod, translated for NeoForge 26.3 by the CLI, so the embedded copies are what runs. */
    private Path translate(Path dfu) throws Exception {
        Map<String, byte[]> classes = BridgeHarness.only(BridgeHarness.compile(dir.resolve("compile"), sources121(), dfu.toString()), "mod/");
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\n"
                + "modId=\"named\"\nversion=\"1.0.0\"\n[[dependencies.named]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                + "versionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        classes.forEach((name, bytes) -> jar.put(name + ".class", bytes));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("named.jar");
        Files.write(input, LoaderApiFixtures.jar(jar));
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path output = Files.createDirectories(dir.resolve("out")).resolve("named.jar");
        CliHarness.transform(input, output, "26.3");
        assertTrue(Files.isRegularFile(output), "the CLI must write " + output);
        return output;
    }

    /** Moonlight's entry shape and Bookshelf's loot reads, compiled against the 1.21.1 loot classes and a real DataFixerUpper. */
    private static Map<String, String> sources121() {
        String loot = "net/minecraft/world/level/storage/loot/";
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public final class ItemStack {}");
        sources.put(loot + "LootContext.java", "package net.minecraft.world.level.storage.loot; public class LootContext {}");
        sources.put(loot + "functions/LootItemFunction.java", "package net.minecraft.world.level.storage.loot.functions; public interface LootItemFunction {}");
        sources.put(loot + "predicates/LootItemCondition.java", "package net.minecraft.world.level.storage.loot.predicates; public interface LootItemCondition {}");
        sources.put(loot + "entries/LootPoolEntryType.java", """
                package net.minecraft.world.level.storage.loot.entries;
                public record LootPoolEntryType(com.mojang.serialization.MapCodec<? extends LootPoolEntryContainer> codec) {}""");
        sources.put(loot + "entries/LootPoolEntryContainer.java", """
                package net.minecraft.world.level.storage.loot.entries;
                import java.util.List;
                import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
                public abstract class LootPoolEntryContainer {
                    public final List<LootItemCondition> conditions;
                    protected LootPoolEntryContainer(List<LootItemCondition> conditions) { this.conditions = conditions; }
                    public abstract LootPoolEntryType getType();
                    public abstract static class Builder<T extends Builder<T>> {
                        protected abstract T getThis();
                        public abstract LootPoolEntryContainer build();
                    }
                }""");
        sources.put(loot + "entries/LootPoolSingletonContainer.java", """
                package net.minecraft.world.level.storage.loot.entries;
                import com.mojang.datafixers.Products;
                import com.mojang.serialization.codecs.RecordCodecBuilder;
                import java.util.List;
                import java.util.function.Consumer;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.storage.loot.LootContext;
                import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
                import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
                public abstract class LootPoolSingletonContainer extends LootPoolEntryContainer {
                    public final List<LootItemFunction> functions;
                    protected LootPoolSingletonContainer(int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions) {
                        super(conditions);
                        this.functions = functions;
                    }
                    protected abstract void createItemStack(Consumer<ItemStack> output, LootContext context);
                    protected static <T extends LootPoolSingletonContainer> Products.P4<RecordCodecBuilder.Mu<T>, Integer, Integer,
                            List<LootItemCondition>, List<LootItemFunction>> singletonFields(RecordCodecBuilder.Instance<T> instance) { return null; }
                    public static Builder<?> simpleBuilder(EntryConstructor constructor) { return null; }
                    public abstract static class Builder<T extends Builder<T>> extends LootPoolEntryContainer.Builder<T> {
                        public T setWeight(int weight) { return getThis(); }
                    }
                    @FunctionalInterface
                    public interface EntryConstructor {
                        LootPoolSingletonContainer build(int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions);
                    }
                }""");
        sources.put("net/minecraft/resources/ResourceKey.java", "package net.minecraft.resources; public final class ResourceKey<T> {}");
        sources.put(loot + "LootTable.java", "package net.minecraft.world.level.storage.loot; public class LootTable {}");
        sources.put(loot + "entries/LootPoolEntries.java", """
                package net.minecraft.world.level.storage.loot.entries;
                public class LootPoolEntries {
                    public static final LootPoolEntryType ITEM = null;
                }""");
        sources.put(loot + "entries/NestedLootTable.java", """
                package net.minecraft.world.level.storage.loot.entries;
                public class NestedLootTable {
                    public static LootPoolSingletonContainer.Builder<?> lootTableReference(
                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key) { return null; }
                }""");
        sources.put("mod/LootReads.java", """
                package mod;
                import java.util.List;
                import net.minecraft.resources.ResourceKey;
                import net.minecraft.world.level.storage.loot.LootTable;
                import net.minecraft.world.level.storage.loot.entries.LootPoolEntries;
                import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
                import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
                import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
                public class LootReads {
                    public static Object itemType() { return LootPoolEntries.ITEM; }
                    public static Object typeOf(LootPoolEntryContainer entry) { return entry.getType(); }
                    public static List<?> functionsOf(LootPoolSingletonContainer entry) { return entry.functions; }
                    public static List<?> conditionsOf(LootPoolEntryContainer entry) { return entry.conditions; }
                    public static LootPoolEntryContainer nested(ResourceKey<LootTable> key) {
                        return NestedLootTable.lootTableReference(key).setWeight(2).build();
                    }
                }""");
        sources.put("mod/NamedItemEntry.java", """
                package mod;
                import com.mojang.serialization.Codec;
                import com.mojang.serialization.MapCodec;
                import com.mojang.serialization.codecs.RecordCodecBuilder;
                import java.util.List;
                import java.util.function.Consumer;
                import java.util.function.Supplier;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.storage.loot.LootContext;
                import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
                import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
                import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
                import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
                import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
                public class NamedItemEntry extends LootPoolSingletonContainer {
                    public static Supplier<ItemStack> STACK;
                    public static final MapCodec<NamedItemEntry> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                            Codec.STRING.fieldOf("name").forGetter((NamedItemEntry entry) -> entry.name))
                            .and(singletonFields(instance)).apply(instance, NamedItemEntry::new));
                    public static final LootPoolEntryType TYPE = new LootPoolEntryType(CODEC);
                    private final String name;
                    NamedItemEntry(String name, int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions) {
                        super(weight, quality, conditions, functions);
                        this.name = name;
                    }
                    public static LootPoolSingletonContainer.Builder<?> named(String name) {
                        return simpleBuilder((weight, quality, conditions, functions) -> new NamedItemEntry(name, weight, quality, conditions, functions));
                    }
                    public static LootPoolEntryContainer weighted() { return named("built").setWeight(3).build(); }
                    public int conditionCount() { return conditions.size(); }
                    public int functionCount() { return functions.size(); }
                    @Override public void createItemStack(Consumer<ItemStack> output, LootContext context) { output.accept(STACK.get()); }
                    @Override public LootPoolEntryType getType() { return TYPE; }
                }""");
        return sources;
    }

    /** The bootstrapped 26.3 game in its own loader, reached through reflection. */
    private static final class Game {
        private final ClassLoader loader;
        private final Object jsonOps;
        private final Object context;

        Game(ClassLoader loader) throws Exception {
            this.loader = loader;
            // Bootstrap routes the standard streams into the game's logger, which this test JVM keeps using.
            PrintStream out = System.out;
            PrintStream err = System.err;
            try {
                type("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
                type("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
            jsonOps = type("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
            // A loaded world binds each item's components; this one binds the emerald's stack size only.
            Object emerald = type("net.minecraft.world.item.Items").getField("EMERALD").get(null);
            Object holder = type("net.minecraft.world.item.Item").getMethod("builtInRegistryHolder").invoke(emerald);
            Class<?> components = type("net.minecraft.core.component.DataComponentMap");
            Object builder = components.getMethod("builder").invoke(null);
            builder.getClass().getMethod("set", type("net.minecraft.core.component.DataComponentType"), Object.class).invoke(builder,
                    type("net.minecraft.core.component.DataComponents").getField("MAX_STACK_SIZE").get(null), 64);
            holder.getClass().getMethod("bindComponents", components).invoke(holder, builder.getClass().getMethod("build").invoke(builder));
            // A loot context with only a seeded random, which is all the conditions and functions here read.
            Object random = type("net.minecraft.util.RandomSource").getMethod("create", long.class).invoke(null, 42L);
            Constructor<?> constructor = type("net.minecraft.world.level.storage.loot.LootContext").getDeclaredConstructor(
                    type("net.minecraft.world.level.storage.loot.LootParams"), type("net.minecraft.util.RandomSource"),
                    type("net.minecraft.core.HolderGetter$Provider"));
            constructor.setAccessible(true);
            context = constructor.newInstance(null, random, null);
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object emerald() {
            try {
                Object item = type("net.minecraft.world.item.Items").getField("EMERALD").get(null);
                return type("net.minecraft.world.item.ItemStack").getConstructor(type("net.minecraft.world.level.ItemLike")).newInstance(item);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        Object codecOf(Object mapCodec) throws Exception {
            return type("com.mojang.serialization.MapCodec").getMethod("codec").invoke(mapCodec);
        }

        Object parse(Object codec, String json) throws Exception {
            Object element = type("com.google.gson.JsonParser").getMethod("parseString", String.class).invoke(null, json);
            Object result = type("com.mojang.serialization.Decoder").getMethod("parse", type("com.mojang.serialization.DynamicOps"), Object.class)
                    .invoke(codec, jsonOps, element);
            return orThrow(result);
        }

        String encode(Object codec, Object value) throws Exception {
            Object result = type("com.mojang.serialization.Encoder").getMethod("encodeStart", type("com.mojang.serialization.DynamicOps"), Object.class)
                    .invoke(codec, jsonOps, value);
            return orThrow(result).toString();
        }

        private Object orThrow(Object dataResult) throws Exception {
            Class<?> type = type("com.mojang.serialization.DataResult");
            java.util.Optional<?> value = (java.util.Optional<?>) type.getMethod("result").invoke(dataResult);
            assertTrue(value.isPresent(), () -> "DataFixerUpper reported " + dataResult);
            return value.get();
        }

        List<Object> expand(Object entry) throws Exception {
            List<Object> entries = new ArrayList<>();
            Method expand = type("net.minecraft.world.level.storage.loot.entries.ComposableEntryContainer")
                    .getMethod("expand", type("net.minecraft.world.level.storage.loot.LootContext"), Consumer.class);
            boolean expanded = (boolean) expand.invoke(entry, context, (Consumer<Object>) entries::add);
            assertEquals(expanded, !entries.isEmpty(), "expand reports what it produced");
            return entries;
        }

        int weight(Object poolEntry, float luck) throws Exception {
            return (int) type("net.minecraft.world.level.storage.loot.entries.LootPoolEntry").getMethod("getWeight", float.class)
                    .invoke(poolEntry, luck);
        }

        List<Object> items(Object poolEntry) throws Exception {
            List<Object> stacks = new ArrayList<>();
            type("net.minecraft.world.level.storage.loot.entries.LootPoolEntry")
                    .getMethod("createItemStack", Consumer.class, type("net.minecraft.world.level.storage.loot.LootContext"))
                    .invoke(poolEntry, (Consumer<Object>) stacks::add, context);
            return stacks;
        }

        int count(Object stack) throws Exception {
            return (int) type("net.minecraft.world.item.ItemStack").getMethod("getCount").invoke(stack);
        }

        /** {@code LootItem.lootTableItem(Items.EMERALD).build()}: a vanilla entry. */
        Object lootItem() throws Exception {
            Object emerald = type("net.minecraft.world.item.Items").getField("EMERALD").get(null);
            Object builder = type("net.minecraft.world.level.storage.loot.entries.LootItem")
                    .getMethod("lootTableItem", type("net.minecraft.world.level.ItemLike")).invoke(null, emerald);
            return type("net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer$Builder").getMethod("build").invoke(builder);
        }

        /** A table with one pool that rolls one emerald. */
        Object emeraldTable() throws Exception {
            Object pool = type("net.minecraft.world.level.storage.loot.LootPool").getMethod("lootPool").invoke(null);
            Object entryBuilder = type("net.minecraft.world.level.storage.loot.entries.LootItem")
                    .getMethod("lootTableItem", type("net.minecraft.world.level.ItemLike"))
                    .invoke(null, type("net.minecraft.world.item.Items").getField("EMERALD").get(null));
            type("net.minecraft.world.level.storage.loot.LootPool$Builder")
                    .getMethod("add", type("net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer$Builder")).invoke(pool, entryBuilder);
            Object table = type("net.minecraft.world.level.storage.loot.LootTable").getMethod("lootTable").invoke(null);
            type("net.minecraft.world.level.storage.loot.LootTable$Builder")
                    .getMethod("withPool", type("net.minecraft.world.level.storage.loot.LootPool$Builder")).invoke(table, pool);
            return type("net.minecraft.world.level.storage.loot.LootTable$Builder").getMethod("build").invoke(table);
        }

        /** A loot context whose parameters hold only {@code value} under {@code key}. */
        Object contextWith(Object key, Object value) throws Exception {
            Class<?> contextKey = type("net.minecraft.util.context.ContextKey");
            Constructor<?> builderConstructor = type("net.minecraft.util.context.ContextMap$Builder").getDeclaredConstructor();
            builderConstructor.setAccessible(true);
            Object builder = builderConstructor.newInstance();
            builder.getClass().getMethod("set", contextKey, Object.class).invoke(builder, key, value);
            Object map = builder.getClass().getMethod("build").invoke(builder);
            Object params = type("net.minecraft.world.level.storage.loot.LootParams").getConstructor(
                    type("net.minecraft.server.level.ServerLevel"), type("net.minecraft.util.context.ContextMap"), Map.class, float.class)
                    .newInstance(null, map, Map.of(), 0F);
            Object random = type("net.minecraft.util.RandomSource").getMethod("create", long.class).invoke(null, 3L);
            Constructor<?> constructor = type("net.minecraft.world.level.storage.loot.LootContext").getDeclaredConstructor(
                    type("net.minecraft.world.level.storage.loot.LootParams"), type("net.minecraft.util.RandomSource"),
                    type("net.minecraft.core.HolderGetter$Provider"));
            constructor.setAccessible(true);
            return constructor.newInstance(params, random, null);
        }

        Object lootTableKey(String path) throws Exception {
            Object id = type("net.minecraft.resources.Identifier").getMethod("withDefaultNamespace", String.class).invoke(null, path);
            Object registry = type("net.minecraft.core.registries.Registries").getField("LOOT_TABLE").get(null);
            return type("net.minecraft.resources.ResourceKey").getMethod("create", type("net.minecraft.resources.ResourceKey"),
                    type("net.minecraft.resources.Identifier")).invoke(null, registry, id);
        }

        /** Expands {@code entry} and rolls what it made, in a context whose registries hold {@code table} under {@code key}. */
        List<Object> expandWithTable(Object entry, Object key, Object table) throws Exception {
            Class<?> owner = type("net.minecraft.core.HolderOwner");
            Object holderOwner = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {owner},
                    (proxy, method, args) -> method.isDefault() ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args)
                            : method.getName().equals("hashCode") ? System.identityHashCode(proxy) : method.getName().equals("equals") ? proxy == args[0] : null);
            Class<?> reference = type("net.minecraft.core.Holder$Reference");
            Object held = reference.getMethod("createStandAlone", owner, type("net.minecraft.resources.ResourceKey")).invoke(null, holderOwner, key);
            Method bind = reference.getDeclaredMethod("bindValue", Object.class);
            bind.setAccessible(true);
            bind.invoke(held, table);
            Class<?> provider = type("net.minecraft.core.HolderGetter$Provider");
            Object registries = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {provider}, (proxy, method, args) ->
                    method.getName().equals("get") && args.length == 1 && key.equals(args[0]) ? java.util.Optional.of(held)
                            : method.getName().equals("lookup") ? java.util.Optional.empty() : java.util.Optional.empty());
            Object params = type("net.minecraft.world.level.storage.loot.LootParams").getConstructor(
                    type("net.minecraft.server.level.ServerLevel"), type("net.minecraft.util.context.ContextMap"), Map.class, float.class)
                    .newInstance(null, type("net.minecraft.util.context.ContextMap").getField("EMPTY").get(null), Map.of(), 0F);
            Object random = type("net.minecraft.util.RandomSource").getMethod("create", long.class).invoke(null, 7L);
            Constructor<?> constructor = type("net.minecraft.world.level.storage.loot.LootContext").getDeclaredConstructor(
                    type("net.minecraft.world.level.storage.loot.LootParams"), type("net.minecraft.util.RandomSource"), provider);
            constructor.setAccessible(true);
            Object rolling = constructor.newInstance(params, random, registries);
            List<Object> entries = new ArrayList<>();
            boolean expanded = (boolean) type("net.minecraft.world.level.storage.loot.entries.ComposableEntryContainer")
                    .getMethod("expand", type("net.minecraft.world.level.storage.loot.LootContext"), Consumer.class)
                    .invoke(entry, rolling, (Consumer<Object>) entries::add);
            assertTrue(expanded && entries.size() == 1, "the named table entry expands once");
            List<Object> stacks = new ArrayList<>();
            type("net.minecraft.world.level.storage.loot.entries.LootPoolEntry")
                    .getMethod("createItemStack", Consumer.class, type("net.minecraft.world.level.storage.loot.LootContext"))
                    .invoke(entries.get(0), (Consumer<Object>) stacks::add, rolling);
            return stacks;
        }

        int uniformWeight(Object entry) throws Exception {
            Field weight = type("net.minecraft.world.level.storage.loot.entries.UniformContainerBase").getDeclaredField("weight");
            weight.setAccessible(true);
            return weight.getInt(entry);
        }
    }
}
