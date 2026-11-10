/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.LegacyItemPropertiesBridge;
import com.retromod.shim.common.LegacyItemPropertiesFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A Forge 1.20.1 mod and the library it nests, each through the copy ForgeModTransformer embedded, register item properties,
 * and Retromod's own property type, added as the runtime adds it, reads them on stand-in 26.x host classes and real DFU.
 */
class LegacyItemPropertiesRuntimeTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    private static final Path DFU = LIBRARIES.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
    private static final Path FASTUTIL = LIBRARIES.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    private static final String RUNTIME_COPY = "com/retromod/legacyapi/minecraft/client/renderer/item/";
    private static final String SHARED_SLOTS = "com/retromod/legacyapi/forge/LegacySharedSlots";
    private static final String SLOT_PREFIX = "retromod.shared.minecraft.itemProperties.";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void clearSlots() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(SLOT_PREFIX));
    }

    @AfterEach
    void restore() {
        clearSlots();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The type reads each copy's functions: clamped, generic before the item's own, none as negative infinity")
    void propertyTypeReadsTheOldFunctions() throws Throwable {
        Game game = game();
        assertEquals(Boolean.TRUE, game.install(), "the runtime adds the type");
        assertEquals(Boolean.TRUE, game.install(), "a second install finds it there");
        Object gem = game.item("gem");
        Object stone = game.item("stone");
        game.register("oldmod/Props", gem, "gemmod:charge", "gemmod:clamped", "gemmod:generic");

        assertEquals(0.75F, game.value("gemmod:charge", gem, null), "the mod's function, through Retromod's type");
        assertEquals(1F, game.value("gemmod:clamped", gem, null), "a clamped function's 3 is kept at 1");
        assertEquals(-2F, game.value("gemmod:generic", stone, null), "a generic property answers for every item");
        assertEquals(2F, game.value("gemmod:generic", gem, game.newInstance("net/minecraft/world/entity/LivingEntity")),
                "the item owner reaches the old function as its living entity");
        assertEquals(Float.NEGATIVE_INFINITY, game.value("gemmod:charge", stone, null), "another item has no such property");
        assertEquals(Float.NEGATIVE_INFINITY, game.value("minecraft:cooking", gem, null), "nothing registered it");
    }

    @Test
    @DisplayName("getProperty answers a function another jar's copy registered, and the copy's own one as it was given")
    void getPropertyAcrossCopies() throws Throwable {
        Game game = game();
        Object gem = game.item("gem");
        game.register("oldmod/Props", gem, "gemmod:charge", "gemmod:clamped", "gemmod:generic");

        assertEquals(0.75F, game.read("oldmod/Props", gem, "gemmod:charge"), "the registering copy");
        assertEquals(0.75F, game.read("fixturelib/Props", gem, "gemmod:charge"), "the nested library's own copy");
        assertTrue(Float.isNaN(game.read("fixturelib/Props", gem, "gemmod:missing")), "null for a property nobody registered");
        assertNotEquals(game.copyOf("oldmod/Props"), game.copyOf("fixturelib/Props"), "the jars carry separate copies");
    }

    @Test
    @DisplayName("A function that throws while the game draws reads as no match, not a crash")
    void throwingFunctionReadsAsNoMatch() throws Throwable {
        Game game = game();
        game.install();
        Object gem = game.item("gem");
        String copy = game.copyOf("oldmod/Props");
        Class<?> function = game.type(copy + "ItemPropertyFunction");
        Object throwing = Proxy.newProxyInstance(game.loader, new Class<?>[]{function}, (proxy, method, args) -> {
            if (method.getName().equals("call")) throw new IllegalStateException("old code");
            return method.getName().equals("hashCode") ? 1 : method.getName().equals("equals") ? proxy == args[0] : "throwing";
        });
        Method register = game.type(copy + "ItemProperties").getMethod("register", game.type("net/minecraft/world/item/Item"),
                game.type("net/minecraft/resources/Identifier"), function);
        register.invoke(null, gem, game.id("gemmod:broken"), throwing);

        assertEquals(Float.NEGATIVE_INFINITY, game.value("gemmod:broken", gem, null));
        assertEquals(Float.NEGATIVE_INFINITY, game.value("gemmod:broken", gem, null), "and again, noted once");
    }

    // ---- the game ----

    private Game game() throws Exception {
        assumeTrue(Files.isRegularFile(DFU) && Files.isRegularFile(FASTUTIL),
                "no local DataFixerUpper 10 or fastutil jar; they are local test material");
        assumeTrue(Files.isDirectory(LEGACY_CLASSES.resolve(RUNTIME_COPY)), "the rebuilt classes are compiled");
        Map<String, byte[]> classes = new HashMap<>(compileHost());
        // Retromod's own unrelocated copy, which the runtime loads to add the property type.
        try (Stream<Path> files = Files.list(LEGACY_CLASSES.resolve(RUNTIME_COPY))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = LEGACY_CLASSES.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        classes.put(SHARED_SLOTS, Files.readAllBytes(LEGACY_CLASSES.resolve(SHARED_SLOTS + ".class")));
        // The mod and its nested library, as the Forge runtime translated them for 26.2.
        Path translated = translateForForge262();
        try (JarFile jar = new JarFile(translated.toFile())) {
            addClasses(jar, classes);
            Path nested = dir.resolve("nested.jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(LegacyItemPropertiesFixtures.nestedPath(
                    LegacyItemPropertiesFixtures.Loader.FORGE_1201))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                addClasses(lib, classes);
            }
        }
        return new Game(new HostLoader(classes, new URL[]{DFU.toUri().toURL(), FASTUTIL.toUri().toURL()}));
    }

    private Path translateForForge262() throws Exception {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, "26.2")) {
                ShimRegistration.register(shim, transformer);
            }
        }
        com.retromod.mapping.IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path input = dir.resolve("gems.jar");
        Files.write(input, LegacyItemPropertiesFixtures.modJar(LegacyItemPropertiesFixtures.Loader.FORGE_1201, dir));
        Path out = new ForgeModTransformer("26.2").transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the runtime wrote a jar");
        return out;
    }

    private static void addClasses(JarFile jar, Map<String, byte[]> classes) throws Exception {
        for (ZipEntry entry : Collections.list(jar.entries())) {
            String name = entry.getName();
            if (name.endsWith(".class")) classes.put(name.substring(0, name.length() - ".class".length()), jar.getInputStream(entry).readAllBytes());
        }
    }

    // Stand-ins for the 26.x host classes the old mod and the property type touch; the codecs are real DataFixerUpper ones.
    private Map<String, byte[]> compileHost() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/minecraft/world/item/Item.java", "package net.minecraft.world.item; public class Item {"
                + " private final String name; public Item(String name) { this.name = name; }"
                + " @Override public String toString() { return name; } }");
        sources.put("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {"
                + " private final Item item; public ItemStack(Item item) { this.item = item; } public Item getItem() { return item; } }");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel {}");
        sources.put("net/minecraft/world/entity/ItemOwner.java", "package net.minecraft.world.entity; public interface ItemOwner {"
                + " default LivingEntity asLivingEntity() { return this instanceof LivingEntity living ? living : null; } }");
        sources.put("net/minecraft/world/entity/LivingEntity.java",
                "package net.minecraft.world.entity; public class LivingEntity implements ItemOwner {}");
        sources.put("net/minecraft/resources/Identifier.java", "package net.minecraft.resources; import com.mojang.serialization.Codec;"
                + " public final class Identifier {"
                + " public static final Codec<Identifier> CODEC = Codec.STRING.xmap(Identifier::parse, Identifier::toString);"
                + " private final String namespace; private final String path;"
                + " private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }"
                + " public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }"
                + " public static Identifier parse(String id) { int colon = id.indexOf(':');"
                + "  return colon < 0 ? new Identifier(\"minecraft\", id) : new Identifier(id.substring(0, colon), id.substring(colon + 1)); }"
                + " @Override public String toString() { return namespace + \":\" + path; }"
                + " @Override public boolean equals(Object o) { return o instanceof Identifier i && i.toString().equals(toString()); }"
                + " @Override public int hashCode() { return toString().hashCode(); } }");
        sources.put("net/minecraft/util/ExtraCodecs.java", "package net.minecraft.util; import java.util.*; public class ExtraCodecs {"
                + " public static class LateBoundIdMapper<I, V> { public final Map<I, V> ids = new LinkedHashMap<>();"
                + "  public LateBoundIdMapper<I, V> put(I id, V value) {"
                + "   if (ids.putIfAbsent(id, value) != null) throw new IllegalArgumentException(\"Duplicate \" + id); return this; } } }");
        sources.put("net/minecraft/client/renderer/item/properties/numeric/RangeSelectItemModelProperty.java",
                "package net.minecraft.client.renderer.item.properties.numeric; import com.mojang.serialization.MapCodec;"
                        + " public interface RangeSelectItemModelProperty {"
                        + " float get(net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level,"
                        + "  net.minecraft.world.entity.ItemOwner owner, int seed);"
                        + " MapCodec<? extends RangeSelectItemModelProperty> type(); }");
        sources.put("net/minecraft/client/renderer/item/properties/numeric/RangeSelectItemModelProperties.java",
                "package net.minecraft.client.renderer.item.properties.numeric; import com.mojang.serialization.MapCodec;"
                        + " import net.minecraft.resources.Identifier; import net.minecraft.util.ExtraCodecs;"
                        + " public class RangeSelectItemModelProperties {"
                        + " private static final ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends RangeSelectItemModelProperty>>"
                        + "  ID_MAPPER = new ExtraCodecs.LateBoundIdMapper<>();"
                        + " public static MapCodec<?> type(Identifier id) { return ID_MAPPER.ids.get(id); } }");
        Path src = dir.resolve("host-src");
        Path out = dir.resolve("host-out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none",
                "-classpath", DFU.toString()));
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

    /** Reflection over the test game's classes. */
    private static final class Game {
        private final ClassLoader loader;

        Game(ClassLoader loader) {
            this.loader = loader;
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        Object install() throws Exception {
            return type(LegacyItemPropertiesBridge.INSTALLER).getMethod("install").invoke(null);
        }

        Object item(String name) throws Exception {
            return type("net/minecraft/world/item/Item").getConstructor(String.class).newInstance(name);
        }

        Object newInstance(String name) throws Exception {
            return type(name).getConstructor().newInstance();
        }

        Object id(String id) throws Exception {
            return type("net/minecraft/resources/Identifier").getMethod("parse", String.class).invoke(null, id);
        }

        Object stack(Object item) throws Exception {
            return type("net/minecraft/world/item/ItemStack").getConstructor(type("net/minecraft/world/item/Item")).newInstance(item);
        }

        void register(String props, Object item, String charge, String clamped, String generic) throws Throwable {
            Class<?> identifier = type("net/minecraft/resources/Identifier");
            invoke(type(props).getMethod("register", type("net/minecraft/world/item/Item"), identifier, identifier, identifier),
                    null, item, id(charge), id(clamped), id(generic));
        }

        float read(String props, Object item, String id) throws Throwable {
            return (Float) invoke(type(props).getMethod("read", type("net/minecraft/world/item/ItemStack"),
                    type("net/minecraft/resources/Identifier")), null, stack(item), id(id));
        }

        /** {@code {"id": <id>}} decoded by the type the install added, then asked for the stack's value. */
        float value(String id, Object item, Object owner) throws Throwable {
            Object type = type("net/minecraft/client/renderer/item/properties/numeric/RangeSelectItemModelProperties")
                    .getMethod("type", type("net/minecraft/resources/Identifier")).invoke(null, id(LegacyItemPropertiesBridge.TYPE_ID));
            assertNotNull(type, "the install added " + LegacyItemPropertiesBridge.TYPE_ID);
            Object codec = type("com/mojang/serialization/MapCodec").getMethod("codec").invoke(type);
            Object json = com.google.gson.JsonParser.parseString("{\"" + LegacyItemPropertiesBridge.ID_FIELD + "\":\"" + id + "\"}");
            Object ops = type("com/mojang/serialization/JsonOps").getField("INSTANCE").get(null);
            Class<?> codecType = type("com/mojang/serialization/Decoder");
            Object result = codecType.getMethod("parse", type("com/mojang/serialization/DynamicOps"), Object.class)
                    .invoke(codec, ops, json);
            Object property = ((java.util.Optional<?>) type("com/mojang/serialization/DataResult").getMethod("result")
                    .invoke(result)).orElseThrow();
            Method get = type("net/minecraft/client/renderer/item/properties/numeric/RangeSelectItemModelProperty").getMethod("get",
                    type("net/minecraft/world/item/ItemStack"), type("net/minecraft/client/multiplayer/ClientLevel"),
                    type("net/minecraft/world/entity/ItemOwner"), int.class);
            return (Float) invoke(get, property, stack(item), null, owner, 0);
        }

        /** The embedded package the props class's calls go to. */
        String copyOf(String props) throws Exception {
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            try (var in = loader.getResourceAsStream(props + ".class")) {
                new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(node, 0);
            }
            for (var method : node.methods) {
                for (var insn : method.instructions) {
                    if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.endsWith("/ItemProperties")) {
                        return call.owner.substring(0, call.owner.length() - "ItemProperties".length());
                    }
                }
            }
            throw new AssertionError(props + " calls no ItemProperties");
        }

        private static Object invoke(Method method, Object target, Object... args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    /** Loads the listed classes and DataFixerUpper itself before asking the test's own class path. */
    private static final class HostLoader extends URLClassLoader {
        private final Map<String, byte[]> classes;

        HostLoader(Map<String, byte[]> classes, URL[] libraries) {
            super(libraries, LegacyItemPropertiesRuntimeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes != null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } else if (name.startsWith("com.mojang.") || name.startsWith("it.unimi.")) {
                        loaded = findClass(name);
                    } else {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override
        public java.io.InputStream getResourceAsStream(String name) {
            String className = name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : null;
            if (className != null && classes.containsKey(className)) return new java.io.ByteArrayInputStream(classes.get(className));
            return super.getResourceAsStream(name);
        }
    }
}
