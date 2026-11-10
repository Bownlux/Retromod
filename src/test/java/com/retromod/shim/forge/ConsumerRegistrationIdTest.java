/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A RegisterEvent consumer that builds an item into a local, or inside the register call, and
 * registers it under an id it knows gave the item no id on 26.x, which stops with "Item id not
 * set". The Forge test mod's first #302 case built its knife that way. Each case runs the
 * translated mod against stand-in host classes on every Forge-family path.
 */
class ConsumerRegistrationIdTest {

    @TempDir
    Path dir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    @AfterEach
    void clearSharedSlots() {
        System.getProperties().remove(RegistryIdBridgeSynthetic.SHARED_CURRENT_KEY);
        System.getProperties().remove(RegistryIdBridgeSynthetic.SHARED_HANDED_KEY);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{3} on {0} {1} {2}")
    @CsvSource({"forge, 26.2, runtime, forge-1.20.1", "forge, 26.3, runtime, forge-1.20.1",
            "neoforge, 26.2, runtime, forge-1.20.1", "neoforge, 26.3, runtime, forge-1.20.1",
            "forge, 26.2, cli, forge-1.20.1", "neoforge, 26.3, cli, forge-1.20.1",
            "forge, 26.3, aot, forge-1.20.1", "neoforge, 26.2, aot, forge-1.20.1",
            "neoforge, 26.2, runtime, neoforge-1.21.1", "neoforge, 26.3, cli, neoforge-1.21.1"})
    @DisplayName("what a RegisterEvent consumer builds gets the id it registers it under, on every path")
    void consumerBuiltObjectsGetTheirIds(String loader, String host, String path, String built) throws Exception {
        Path input = dir.resolve("consumermod.jar");
        Files.write(input, modJar(built));
        Path translated = path.equals("runtime") ? runtime(loader, host, input) : cli(loader, host, path.equals("aot"), input);

        ClassLoader hostClasses = new HostClasses(compile("host", hostSources()));
        try (URLClassLoader mod = new URLClassLoader(new URL[]{translated.toUri().toURL()}, hostClasses)) {
            Class<?> items = mod.loadClass("consumermod.ConsumerItems");
            Class<?> helperType = helperType(items);
            Map<String, Object> registered = new LinkedHashMap<>();
            Object helper = Proxy.newProxyInstance(hostClasses, new Class<?>[]{helperType}, (proxy, method, args) -> {
                registered.put(String.valueOf(args[0]), args[1]);
                return null;
            });

            // The #302 shape: a knife built into a local, then registered by a literal id.
            Object knife = call(items, "registerKnife", helper);
            assertEquals("ResourceKey[minecraft:item / consumermod:legacy_knife]", keyOf(knife));
            // ModernUI's: the properties come first, the item is built inside the call under a stored id.
            call(items, "registerBuilder", helper);
            assertEquals("ResourceKey[minecraft:item / consumermod:builder]", keyOf(registered.get("consumermod:builder")));
            // ModernFix's: one shared properties object, an id computed in a loop.
            call(items, "registerMany", helper);
            for (int i = 0; i < 3; i++) {
                assertEquals("ResourceKey[minecraft:item / consumermod:test" + i + "]",
                        keyOf(registered.get("consumermod:test" + i)), "each item takes its own id from the loop");
            }
            // Architectury's: each entry's id is read first, then its supplier builds the object.
            call(items, "registerEntries", helper);
            assertEquals("ResourceKey[minecraft:item / consumermod:first]", keyOf(registered.get("consumermod:first")));
            assertEquals("ResourceKey[minecraft:item / consumermod:second]", keyOf(registered.get("consumermod:second")));

            // A mod that catches a failed construction keeps its own handler, and the id does not linger.
            assertEquals("caught: broken", call(items, "registerBroken", helper));
            assertEquals("NullPointerException: Item id not set", build(mod),
                    "after every registration the thread holds no id");
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, 26.2", "neoforge, 26.3"})
    @DisplayName("an id the bytecode cannot prove in time is left alone")
    void unprovenIdsAreDeclined(String loader, String host) throws Exception {
        Path input = dir.resolve("consumermod.jar");
        Files.write(input, modJar("forge-1.20.1"));
        Path translated = runtime(loader, host, input);

        ClassLoader hostClasses = new HostClasses(compile("host", hostSources()));
        try (URLClassLoader mod = new URLClassLoader(new URL[]{translated.toUri().toURL()}, hostClasses)) {
            Class<?> items = mod.loadClass("consumermod.ConsumerItems");
            Class<?> helperType = helperType(items);
            Object helper = Proxy.newProxyInstance(hostClasses, new Class<?>[]{helperType}, (proxy, method, args) -> null);
            // YungsApi's shape: the id comes from a getter called after the item is built.
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> call(items, "registerFromGetter", helper));
            assertEquals("Item id not set", thrown.getCause().getMessage());
            // One item registered under two ids gets neither.
            thrown = assertThrows(InvocationTargetException.class, () -> call(items, "registerTwice", helper));
            assertEquals("Item id not set", thrown.getCause().getMessage());
            // Nor does one that only the other branch registers under an id the bytecode cannot prove.
            thrown = assertThrows(InvocationTargetException.class, () -> call(items, "registerAlternatives", helper));
            assertEquals("Item id not set", thrown.getCause().getMessage());

            // A static field's stamp inside the call would interleave with the consumer's window.
            Class<?> fieldCase = mod.loadClass("consumermod.FieldCase");
            thrown = assertThrows(InvocationTargetException.class, () -> call(fieldCase, "register", helper));
            assertEquals("Item id not set", thrown.getCause().getMessage(), "the wrapping item is built with no id");
            assertEquals("ResourceKey[minecraft:item / consumermod:f15]", keyOf(fieldCase.getField("F15").get(null)),
                    "the field keeps the id it is registered under elsewhere");
            assertEquals("NullPointerException: Item id not set", build(mod), "and the thread holds no id afterwards");
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, 26.2", "neoforge, 26.3"})
    @DisplayName("an item a constructor registers is built inside the outer window and takes its id, by design")
    void constructorRegistrationTakesTheOuterId(String loader, String host) throws Exception {
        Path input = dir.resolve("consumermod.jar");
        Files.write(input, modJar("forge-1.20.1"));
        Path translated = runtime(loader, host, input);

        ClassLoader hostClasses = new HostClasses(compile("host", hostSources()));
        try (URLClassLoader mod = new URLClassLoader(new URL[]{translated.toUri().toURL()}, hostClasses)) {
            Class<?> items = mod.loadClass("consumermod.ConsumerItems");
            Map<String, Object> registered = new LinkedHashMap<>();
            Object helper = Proxy.newProxyInstance(hostClasses, new Class<?>[]{helperType(items)}, (proxy, method, args) -> {
                registered.put(String.valueOf(args[0]), args[1]);
                return null;
            });
            call(items, "registerOuter", helper);

            assertEquals("ResourceKey[minecraft:item / consumermod:outer]", keyOf(registered.get("consumermod:outer")));
            // Constructors are not stamped, so what one builds shares the window around it, as inside
            // a deferred register's supplier.
            assertEquals("ResourceKey[minecraft:item / consumermod:outer]", keyOf(registered.get("consumermod:inner")));
            assertEquals("NullPointerException: Item id not set", build(mod));
        }
    }

    /** What each loader's entry point registers on the host, then one runtime transform. */
    private Path runtime(String loader, String host, Path input) throws Exception {
        target(loader, host);
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = Files.createDirectories(dir.resolve("runtime"));
        Path jar = new ForgeModTransformer(host).transformMod(input, out);
        assertNotNull(jar, "the " + loader + " runtime translated the mod");
        return jar;
    }

    /** A CLI batch, precompiled with {@code --aot} when asked. */
    private Path cli(String loader, String host, boolean aot, Path input) throws Exception {
        target(loader, host);
        Path in = Files.createDirectory(dir.resolve("batch-in"));
        Path out = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, in.resolve(input.getFileName()));
        if (aot) {
            CliHarness.batch(in, out, host, "--target-loader", loader, "--aot");
        } else {
            CliHarness.batch(in, out, host, "--target-loader", loader);
        }
        return LegacyAutoSubscribePathParityTest.onlyJar(out);
    }

    private void target(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        transformer.clearRedirectsForTesting();
    }

    /**
     * The helper type the translated mod takes. A NeoForge host moves Forge's helper to its own
     * name only when it can see that class, which an offline run cannot, and the stamp matches both.
     */
    private static Class<?> helperType(Class<?> items) throws Exception {
        Class<?> type = null;
        for (var method : items.getMethods()) {
            if (method.getName().equals("registerKnife")) type = method.getParameterTypes()[0];
        }
        assertNotNull(type, "the mod lost registerKnife");
        assertTrue(Set.of("net.minecraftforge.registries.RegisterEvent$RegisterHelper",
                "net.neoforged.neoforge.registries.RegisterEvent$RegisterHelper").contains(type.getName()), type.getName());
        return type;
    }

    private static Object call(Class<?> items, String name, Object helper) throws Exception {
        for (var method : items.getMethods()) {
            if (method.getName().equals(name)) return method.invoke(null, helper);
        }
        throw new AssertionError("the mod lost " + name);
    }

    private static String keyOf(Object item) throws Exception {
        assertNotNull(item, "the item was registered");
        return String.valueOf(item.getClass().getField("key").get(item));
    }

    /** Builds a plain item outside any registration and names what it threw. */
    private static String build(ClassLoader mod) throws Exception {
        try {
            mod.loadClass("consumermod.ConsumerItems").getMethod("plainItem").invoke(null);
            return "built";
        } catch (InvocationTargetException failed) {
            return failed.getCause().getClass().getSimpleName() + ": " + failed.getCause().getMessage();
        }
    }

    /** A Forge 1.20.1 or NeoForge 1.21.1 jar holding the consumer classes. */
    private byte[] modJar(String built) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        if (built.equals("forge-1.20.1")) {
            entries.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("consumermod"));
        } else {
            entries.put("META-INF/neoforge.mods.toml", LegacyItemRendererPathParityTest.neoforgeToml("consumermod"));
        }
        compile("mod", modSources(built)).forEach((name, bytes) -> {
            if (name.startsWith("consumermod/")) entries.put(name + ".class", bytes);
        });
        return LegacyAutoSubscribePathParityTest.jar(entries);
    }

    /** The host stand-ins the translated jar links against, and nothing of Retromod's. */
    private static final class HostClasses extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        HostClasses(Map<String, byte[]> compiled) {
            super(ClassLoader.getPlatformClassLoader());
            compiled.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** The mod, written against the API shapes of the version it was built for. */
    private static Map<String, String> modSources(String built) {
        // NeoForge 1.21.1 has its own RegisterEvent and builds ids with a factory.
        boolean neoForge = built.equals("neoforge-1.21.1");
        String helperPackage = neoForge ? "net.neoforged.neoforge.registries" : "net.minecraftforge.registries";
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/minecraft/resources/ResourceLocation.java", """
                package net.minecraft.resources;
                public class ResourceLocation {
                    public ResourceLocation(String namespace, String path) {}
                    public static ResourceLocation fromNamespaceAndPath(String namespace, String path) {
                        return new ResourceLocation(namespace, path);
                    }
                }""");
        sources.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                public class Item {
                    public Item(Item.Properties properties) {}
                    public static class Properties {}
                }""");
        sources.put(helperPackage.replace('.', '/') + "/RegisterEvent.java", """
                package %s;
                import net.minecraft.resources.ResourceLocation;
                public class RegisterEvent {
                    public interface RegisterHelper<T> {
                        void register(ResourceLocation name, T value);
                    }
                }""".formatted(helperPackage));
        sources.put("net/minecraft/core/Registry.java", """
                package net.minecraft.core;
                import net.minecraft.resources.ResourceLocation;
                public interface Registry<T> {
                    static <T> T register(Registry<? super T> registry, ResourceLocation id, T value) { return value; }
                }""");
        sources.put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                public final class BuiltInRegistries {
                    public static final net.minecraft.core.Registry<net.minecraft.world.item.Item> ITEM = null;
                }""");
        sources.put("consumermod/OuterItem.java", """
                package consumermod;
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.world.item.Item;
                import net.minecraftforge.registries.RegisterEvent;
                public class OuterItem extends Item {
                    public OuterItem(Item.Properties properties, RegisterEvent.RegisterHelper<Item> helper) {
                        super(properties);
                        helper.register(new ResourceLocation("consumermod", "inner"), new Item(new Item.Properties()));
                    }
                }""");
        // A field the mod registers elsewhere, filled inside a consumer's registration call.
        sources.put("consumermod/FieldCase.java", """
                package consumermod;
                import net.minecraft.core.Registry;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.world.item.Item;
                import net.minecraftforge.registries.RegisterEvent;
                public final class FieldCase {
                    public static Item F15;
                    static Item wrap(Item inner) { return new Item(new Item.Properties()); }
                    public static void register(RegisterEvent.RegisterHelper<Item> helper) {
                        helper.register(new ResourceLocation("consumermod", "c15"), wrap(F15 = new Item(new Item.Properties())));
                    }
                    static void registerField() {
                        Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("consumermod", "f15"), F15);
                    }
                }""");
        sources.put("consumermod/KnifeItem.java", """
                package consumermod;
                import net.minecraft.world.item.Item;
                public class KnifeItem extends Item {
                    public KnifeItem(float attackDamage, float attackSpeed, Item.Properties properties) { super(properties); }
                }""");
        sources.put("consumermod/BuilderItem.java", """
                package consumermod;
                import net.minecraft.world.item.Item;
                public class BuilderItem extends Item {
                    public BuilderItem(Item.Properties properties) { super(properties); }
                }""");
        sources.put("consumermod/BrokenItem.java", """
                package consumermod;
                import net.minecraft.world.item.Item;
                public class BrokenItem extends Item {
                    public BrokenItem(Item.Properties properties) {
                        super(properties);
                        throw new IllegalStateException("broken");
                    }
                }""");
        sources.put("consumermod/Named.java", """
                package consumermod;
                import net.minecraft.resources.ResourceLocation;
                public class Named {
                    public ResourceLocation name = new ResourceLocation("consumermod", "named");
                    public ResourceLocation name() { return name; }
                }""");
        sources.put("consumermod/ConsumerItems.java", """
                package consumermod;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.function.Supplier;
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.world.item.Item;
                import net.minecraftforge.registries.RegisterEvent;
                public final class ConsumerItems {
                    public static final ResourceLocation BUILDER_ID = new ResourceLocation("consumermod", "builder");
                    public static Item registerKnife(RegisterEvent.RegisterHelper<Item> helper) {
                        Item knife = new KnifeItem(0.5F, -2.0F, new Item.Properties());
                        helper.register(new ResourceLocation("consumermod", "legacy_knife"), knife);
                        return knife;
                    }
                    public static void registerBuilder(RegisterEvent.RegisterHelper<Item> helper) {
                        Item.Properties properties = new Item.Properties();
                        helper.register(BUILDER_ID, new BuilderItem(properties));
                    }
                    public static void registerMany(RegisterEvent.RegisterHelper<Item> helper) {
                        Item.Properties properties = new Item.Properties();
                        for (int i = 0; i < 3; i++) {
                            helper.register(new ResourceLocation("consumermod", "test" + i), new Item(properties));
                        }
                    }
                    static Map<ResourceLocation, Supplier<Object>> entries() {
                        Map<ResourceLocation, Supplier<Object>> entries = new LinkedHashMap<>();
                        entries.put(new ResourceLocation("consumermod", "first"), () -> new Item(new Item.Properties()));
                        entries.put(new ResourceLocation("consumermod", "second"), () -> new Item(new Item.Properties()));
                        return entries;
                    }
                    public static void registerEntries(RegisterEvent.RegisterHelper<Object> helper) {
                        for (Map.Entry<ResourceLocation, Supplier<Object>> entry : entries().entrySet()) {
                            ResourceLocation id = entry.getKey();
                            Object built = entry.getValue().get();
                            helper.register(id, built);
                        }
                    }
                    public static String registerBroken(RegisterEvent.RegisterHelper<Item> helper) {
                        try {
                            Item broken = new BrokenItem(new Item.Properties());
                            helper.register(new ResourceLocation("consumermod", "broken"), broken);
                            return "registered";
                        } catch (IllegalStateException e) {
                            return "caught: " + e.getMessage();
                        }
                    }
                    public static void registerFromGetter(RegisterEvent.RegisterHelper<Item> helper) {
                        Named named = new Named();
                        Item item = new Item(new Item.Properties());
                        helper.register(named.name(), item);
                    }
                    static boolean flag = true;
                    static ResourceLocation dynamic(String path) { return new ResourceLocation("dyn", path.toUpperCase()); }
                    public static void registerAlternatives(RegisterEvent.RegisterHelper<Item> helper) {
                        Item item = new Item(new Item.Properties());
                        if (!flag) {
                            helper.register(new ResourceLocation("consumermod", "c36a"), item);
                        } else {
                            helper.register(dynamic("c36b"), item);
                        }
                    }
                    public static void registerOuter(RegisterEvent.RegisterHelper<Item> helper) {
                        helper.register(new ResourceLocation("consumermod", "outer"), new OuterItem(new Item.Properties(), helper));
                    }
                    public static void registerTwice(RegisterEvent.RegisterHelper<Item> helper) {
                        Item item = new Item(new Item.Properties());
                        helper.register(new ResourceLocation("consumermod", "one"), item);
                        helper.register(new ResourceLocation("consumermod", "two"), item);
                    }
                    public static Item plainItem() {
                        return new Item(new Item.Properties());
                    }
                }""");
        if (neoForge) {
            sources.replaceAll((file, source) -> file.startsWith("consumermod/")
                    ? source.replace("net.minecraftforge.registries", helperPackage)
                            .replace("new ResourceLocation(", "ResourceLocation.fromNamespaceAndPath(")
                    : source);
        }
        return sources;
    }

    /** Just enough of each 26.x host: ids, keys, items that need an id, and both loaders' helpers. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String namespace;
                    private final String path;
                    private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    @Override public boolean equals(Object other) { return other instanceof Identifier id && id.toString().equals(toString()); }
                    @Override public int hashCode() { return toString().hashCode(); }
                    @Override public String toString() { return namespace + ":" + path; }
                }""");
        sources.put("net/minecraft/resources/ResourceKey.java", """
                package net.minecraft.resources;
                public final class ResourceKey<T> {
                    private final Identifier registry;
                    private final Identifier location;
                    private ResourceKey(Identifier registry, Identifier location) { this.registry = registry; this.location = location; }
                    public static <T> ResourceKey<T> create(ResourceKey<?> registry, Identifier location) {
                        return new ResourceKey<>(registry.location, location);
                    }
                    public static <T> ResourceKey<T> createRegistryKey(Identifier location) {
                        return new ResourceKey<>(Identifier.fromNamespaceAndPath("minecraft", "root"), location);
                    }
                    public boolean isFor(ResourceKey<?> registry) { return this.registry.toString().equals(registry.location.toString()); }
                    @Override public String toString() { return "ResourceKey[" + registry + " / " + location + "]"; }
                }""");
        sources.put("net/minecraft/core/registries/Registries.java", """
                package net.minecraft.core.registries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.resources.ResourceKey;
                public final class Registries {
                    public static final ResourceKey<?> ITEM = key("item");
                    public static final ResourceKey<?> BLOCK = key("block");
                    public static final ResourceKey<?> RECIPE_SERIALIZER = key("recipe_serializer");
                    public static final ResourceKey<?> ENCHANTMENT = key("enchantment");
                    public static final ResourceKey<?> BIOME = key("worldgen/biome");
                    public static final ResourceKey<?> PAINTING_VARIANT = key("painting_variant");
                    private static ResourceKey<?> key(String path) {
                        return ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("minecraft", path));
                    }
                }""");
        // 26.x refuses an item whose properties carry no id, with this message.
        sources.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                import net.minecraft.resources.ResourceKey;
                public class Item {
                    public final ResourceKey<?> key;
                    public Item(Item.Properties properties) {
                        this.key = java.util.Objects.requireNonNull(properties.id, "Item id not set");
                    }
                    public static class Properties {
                        ResourceKey<?> id;
                        public Properties setId(ResourceKey<?> id) { this.id = id; return this; }
                    }
                }""");
        sources.put("net/minecraft/world/item/crafting/RecipeSerializer.java", """
                package net.minecraft.world.item.crafting;
                public interface RecipeSerializer<T> {}""");
        sources.put("net/minecraft/core/Registry.java", """
                package net.minecraft.core;
                public interface Registry<T> {}""");
        sources.put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                public final class BuiltInRegistries {
                    public static final net.minecraft.core.Registry<net.minecraft.world.item.Item> ITEM = null;
                }""");
        for (String pkg : List.of("net.minecraftforge.registries", "net.neoforged.neoforge.registries")) {
            sources.put(pkg.replace('.', '/') + "/RegisterEvent.java", """
                    package %s;
                    import net.minecraft.resources.Identifier;
                    public class RegisterEvent {
                        public interface RegisterHelper<T> {
                            void register(Identifier name, T value);
                        }
                    }""".formatted(pkg));
        }
        return sources;
    }

    private Map<String, byte[]> compile(String name, Map<String, String> sources) throws Exception {
        Path src = dir.resolve(name + "-src");
        Path out = dir.resolve(name + "-classes");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK to compile its stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the fixtures must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String className = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(className.substring(0, className.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
