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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sophisticated Backpacks registers Sophisticated Core's upgrade items from its own deferred
 * register, and the item's constructor builds its own {@code Item.Properties}. Each translated jar
 * embeds its own registry id bridge, so the library's copy has to see the key the addon's copy set.
 */
class CrossJarRegistryIdTest {

    private static final String ITEM_KEY = "ResourceKey[minecraft:item / addonmod:pickup]";

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

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({"forge, 26.2, runtime", "forge, 26.3, runtime", "neoforge, 26.2, runtime", "neoforge, 26.3, runtime",
            "forge, 26.2, cli", "neoforge, 26.3, cli", "forge, 26.3, aot", "neoforge, 26.2, aot"})
    @DisplayName("a library item built in an addon's supplier gets the addon's id, and only on that thread")
    void libraryItemTakesTheAddonsId(String loader, String host, String path) throws Exception {
        Map<String, byte[]> compiled = compile();
        Path library = dir.resolve("corelib.jar");
        Path addon = dir.resolve("addonmod.jar");
        Files.write(library, modJar(compiled, "corelib", "corelib/"));
        Files.write(addon, modJar(compiled, "addonmod", "addon/"));
        List<Path> translated = path.equals("runtime") ? runtime(loader, host, library, addon)
                : cli(loader, host, path.equals("aot"), library, addon);

        // Each jar carries its own relocated bridge, as Sophisticated Core and Backpacks do.
        String bridge = loader.equals("forge") ? RegistryIdBridgeSynthetic.FORGE_INTERNAL : RegistryIdBridgeSynthetic.INTERNAL;
        String libraryBridge = embeddedBridge(translated.get(0), bridge);
        String addonBridge = embeddedBridge(translated.get(1), bridge);
        assertNotEquals(libraryBridge, addonBridge, "the two jars embed separate copies");

        // Both jars load in one class loader over the host stand-ins, with no Retromod class reachable.
        ClassLoader hostClasses = new HostClasses(compiled);
        try (URLClassLoader mods = new URLClassLoader(new URL[]{translated.get(0).toUri().toURL(),
                translated.get(1).toUri().toURL()}, hostClasses)) {
            Class<?> pickup = mods.loadClass("corelib.PickupItem");
            Object register = loader.equals("forge")
                    ? hostClasses.loadClass("net.minecraftforge.registries.DeferredRegister")
                            .getConstructor(String.class).newInstance("addonmod")
                    : hostClasses.loadClass("net.neoforged.neoforge.registries.DeferredRegister")
                            .getConstructor(Object.class, String.class).newInstance(registry(hostClasses, "ITEM"), "addonmod");
            invokeRegister(mods.loadClass("addon.AddonItems"), register);

            // While the addon's supplier holds the key, library code on another thread builds an item.
            Object[] otherThread = new Object[1];
            setHook(hostClasses, () -> {
                Thread thread = new Thread(() -> otherThread[0] = build(pickup));
                thread.start();
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            });
            Object entry = register.getClass().getField("entries").get(register);
            Object first = ((List<?>) entry).get(0);
            Object item = loader.equals("forge") ? ((Supplier<?>) first).get()
                    : cast(first).apply(identifier(hostClasses, "addonmod", "pickup"));

            assertSame(pickup, item.getClass());
            assertEquals(ITEM_KEY, String.valueOf(pickup.getSuperclass().getField("key").get(item)),
                    "the library's constructor reads the key the addon's copy set");
            assertNull(pickup.getField("ID_DURING_INIT").get(null),
                    "the library's static initializer starts with no id, even inside the addon's supplier");
            assertEquals("NullPointerException: Item id not set", otherThread[0],
                    "another thread never sees the key");
            assertEquals("NullPointerException: Item id not set", build(pickup),
                    "the key is cleared once the supplier returns");

            // Both copies hold the same slots, so the handed-back check works across jars too.
            Class<?> libraryCopy = mods.loadClass(libraryBridge.replace('/', '.'));
            Class<?> addonCopy = mods.loadClass(addonBridge.replace('/', '.'));
            assertSame(addonCopy.getField("CURRENT").get(null), libraryCopy.getField("CURRENT").get(null));
            assertSame(addonCopy.getField(RegistryIdBridgeSynthetic.HANDED).get(null),
                    libraryCopy.getField(RegistryIdBridgeSynthetic.HANDED).get(null));
        }
    }

    /** What each loader's entry point registers on the host, then one runtime transform per jar. */
    private List<Path> runtime(String loader, String host, Path... inputs) throws Exception {
        target(loader, host);
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        List<Path> outputs = new ArrayList<>();
        for (Path input : inputs) {
            Path out = Files.createDirectories(dir.resolve("runtime-" + outputs.size()));
            Path jar = new ForgeModTransformer(host).transformMod(input, out);
            assertNotNull(jar, "the " + loader + " runtime translated " + input.getFileName());
            outputs.add(jar);
        }
        return outputs;
    }

    /** One CLI batch over both jars, as a modpack author would run it, precompiled with {@code --aot} when asked. */
    private List<Path> cli(String loader, String host, boolean aot, Path... inputs) throws Exception {
        target(loader, host);
        Path in = Files.createDirectory(dir.resolve("batch-in"));
        Path out = Files.createDirectory(dir.resolve("batch-out"));
        for (Path input : inputs) Files.copy(input, in.resolve(input.getFileName()));
        if (aot) {
            CliHarness.batch(in, out, host, "--target-loader", loader, "--aot");
        } else {
            CliHarness.batch(in, out, host, "--target-loader", loader);
        }
        List<Path> outputs = new ArrayList<>();
        for (String owner : List.of("corelib/PickupItem.class", "addon/AddonItems.class")) {
            try (var jars = Files.list(out)) {
                outputs.add(jars.filter(jar -> contains(jar, owner)).findFirst()
                        .orElseThrow(() -> new AssertionError("the batch wrote no jar with " + owner)));
            }
        }
        return outputs;
    }

    private void target(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        transformer.clearRedirectsForTesting();
    }

    private static boolean contains(Path jar, String entry) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getJarEntry(entry) != null;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** The relocated name of {@code bridge} inside a translated jar. */
    private static String embeddedBridge(Path jar, String bridge) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().map(entry -> entry.getName())
                    .filter(name -> name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith("/" + bridge + ".class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .findFirst().orElseThrow(() -> new AssertionError(jar.getFileName() + " embeds no " + bridge));
        }
    }

    private static void invokeRegister(Class<?> addonItems, Object register) throws Exception {
        for (var method : addonItems.getMethods()) {
            if (method.getName().equals("register")) {
                method.invoke(null, register);
                return;
            }
        }
        fail("the addon lost its register method");
    }

    /** Builds a library item outside any registration and names what it threw, or "built" when it got an id. */
    private static String build(Class<?> pickup) {
        try {
            pickup.getConstructor().newInstance();
            return "built";
        } catch (InvocationTargetException failed) {
            return failed.getCause().getClass().getSimpleName() + ": " + failed.getCause().getMessage();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Function<Object, ?> cast(Object function) {
        return (Function<Object, ?>) function;
    }

    private static void setHook(ClassLoader host, Runnable hook) throws Exception {
        host.loadClass("fixture.Hooks").getField("inside").set(null, hook);
    }

    private static Object registry(ClassLoader host, String name) throws Exception {
        return host.loadClass("net.minecraft.core.registries.Registries").getField(name).get(null);
    }

    private static Object identifier(ClassLoader host, String namespace, String path) throws Exception {
        return host.loadClass("net.minecraft.resources.Identifier")
                .getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
    }

    /** A Forge 1.20.1 jar holding the compiled classes under {@code prefix}. */
    private static byte[] modJar(Map<String, byte[]> compiled, String modId, String prefix) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
        compiled.forEach((name, bytes) -> {
            if (name.startsWith(prefix)) entries.put(name + ".class", bytes);
        });
        return LegacyAutoSubscribePathParityTest.jar(entries);
    }

    /** The host stand-ins the translated jars link against, and nothing of Retromod's. */
    private static final class HostClasses extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        HostClasses(Map<String, byte[]> compiled) {
            super(ClassLoader.getPlatformClassLoader());
            compiled.forEach((name, bytes) -> {
                if (name.startsWith("net/") || name.startsWith("com/mojang/") || name.startsWith("fixture/")) {
                    classes.put(name.replace('/', '.'), bytes);
                }
            });
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** The two mods, written as 1.20.1 code, and just enough of each 26.x host to run them. */
    private Map<String, byte[]> compile() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String namespace;
                    private final String path;
                    private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
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
        // The register wrappers load Retromod's codec value class, which extends these.
        sources.put("com/mojang/serialization/Codec.java", """
                package com.mojang.serialization;
                public interface Codec<A> {}""");
        sources.put("com/mojang/serialization/MapCodec.java", """
                package com.mojang.serialization;
                public abstract class MapCodec<A> {}""");
        sources.put("net/minecraftforge/registries/DeferredRegister.java", """
                package net.minecraftforge.registries;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.function.Supplier;
                import net.minecraft.core.registries.Registries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.resources.ResourceKey;
                public class DeferredRegister<T> {
                    public final List<Supplier<?>> entries = new ArrayList<>();
                    private final String namespace;
                    public DeferredRegister(String namespace) { this.namespace = namespace; }
                    public ResourceKey<?> getRegistryKey() { return Registries.ITEM; }
                    public <I extends T> RegistryObject<I> register(String name, Supplier<? extends I> supplier) {
                        entries.add(supplier);
                        return new RegistryObject<>(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(namespace, name)));
                    }
                }""");
        sources.put("net/minecraftforge/registries/RegistryObject.java", """
                package net.minecraftforge.registries;
                import java.util.function.Supplier;
                import net.minecraft.resources.ResourceKey;
                public final class RegistryObject<T> implements Supplier<T> {
                    private final ResourceKey<T> key;
                    RegistryObject(ResourceKey<T> key) { this.key = key; }
                    public ResourceKey<T> getKey() { return key; }
                    @Override public T get() { throw new IllegalStateException("not registered in this test"); }
                }""");
        sources.put("net/neoforged/neoforge/registries/DeferredRegister.java", """
                package net.neoforged.neoforge.registries;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.function.Function;
                import net.minecraft.resources.Identifier;
                import net.minecraft.resources.ResourceKey;
                public class DeferredRegister<T> {
                    public final List<Function<Identifier, ?>> entries = new ArrayList<>();
                    private final ResourceKey<?> registryKey;
                    private final String namespace;
                    public DeferredRegister(Object registryKey, String namespace) {
                        this.registryKey = (ResourceKey<?>) registryKey;
                        this.namespace = namespace;
                    }
                    public ResourceKey<?> getRegistryKey() { return registryKey; }
                    public <I extends T> DeferredHolder<T, I> register(String name, Function<Identifier, ? extends I> function) {
                        entries.add(function);
                        return new DeferredHolder<>(Identifier.fromNamespaceAndPath(namespace, name));
                    }
                }""");
        sources.put("net/neoforged/neoforge/registries/DeferredHolder.java", """
                package net.neoforged.neoforge.registries;
                import net.minecraft.resources.Identifier;
                public class DeferredHolder<R, T extends R> {
                    private final Identifier id;
                    DeferredHolder(Identifier id) { this.id = id; }
                    public Identifier getId() { return id; }
                }""");
        // Test hooks: a step inside the addon's supplier, and a look at the shared slot.
        sources.put("fixture/Hooks.java", """
                package fixture;
                public final class Hooks {
                    public static volatile Runnable inside = () -> {};
                    public static void inside() { inside.run(); }
                    public static Object currentId() {
                        Object slot = System.getProperties().get("%s");
                        return slot instanceof ThreadLocal<?> local ? local.get() : null;
                    }
                }""".formatted(RegistryIdBridgeSynthetic.SHARED_CURRENT_KEY));
        // Sophisticated Core's shape: the item builds its own properties.
        sources.put("corelib/PickupItem.java", """
                package corelib;
                import net.minecraft.world.item.Item;
                public class PickupItem extends Item {
                    public static final Object ID_DURING_INIT = fixture.Hooks.currentId();
                    public PickupItem() { super(new Item.Properties()); }
                }""");
        // Sophisticated Backpacks' shape: its supplier builds the library's item.
        sources.put("addon/AddonItems.java", """
                package addon;
                import corelib.PickupItem;
                import net.minecraft.world.item.Item;
                import net.minecraftforge.registries.DeferredRegister;
                import net.minecraftforge.registries.RegistryObject;
                public class AddonItems {
                    public static RegistryObject<PickupItem> register(DeferredRegister<Item> items) {
                        return items.register("pickup", () -> {
                            PickupItem item = new PickupItem();
                            fixture.Hooks.inside();
                            return item;
                        });
                    }
                }""");
        return compile(sources);
    }

    private Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = dir.resolve("src");
        Path out = dir.resolve("classes");
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
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
