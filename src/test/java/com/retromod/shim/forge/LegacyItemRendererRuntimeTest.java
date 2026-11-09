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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Two old mods' item renderers, each through its own embedded copy, drawn by Minecraft's item model flow on stand-in client classes. */
class LegacyItemRendererRuntimeTest {

    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    // The real codec and math libraries Forge 26.2 and 26.3 ship, which the type's codec and the item transform use.
    private static final List<Path> HOST_LIBRARIES = List.of(
            LIBRARIES.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"),
            LIBRARIES.resolve("com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"),
            LIBRARIES.resolve("com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar"),
            LIBRARIES.resolve("com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar"),
            LIBRARIES.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar"),
            LIBRARIES.resolve("org/joml/joml/1.10.8/joml-1.10.8.jar"));
    private static final String SECOND = "com/retromod/embedded/second/";
    // What the embedder relocates for a jar: Retromod's rebuilt classes and its generated stand-ins and log.
    private static final List<String> RELOCATED = List.of("com/retromod/legacyapi/", "com/retromod/shim/common/embedded/");
    private static final int LIGHT = 15728880;
    private static final int OVERLAY = 655360;

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
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Each mod's type decodes and bakes, and the item draws through its renderer with the GUI context and its own pose")
    void itemModelRunsTheOldRenderer() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        game.call(SECOND, "construct", "modb");
        assertEquals("[minecraft:model, moda:retromod_legacy_item, modb:retromod_legacy_item]", game.host("types"),
                "each mod's constructor added its own type before the first reload");

        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/retromod/item_renderer/gem\","
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"moda:item/retromod/builtin_entity/gem\"}}");
        assertEquals("[moda:item/retromod/item_renderer/gem, moda:item/retromod/builtin_entity/gem]", game.host("dependencies"),
                "the transforms model and the fallback's model are baked with the rest");
        assertEquals("layer: transforms of moda:item/retromod/item_renderer/gem at GUI, animated, special;"
                + " geometry gem_cutout: (1.0,0.0,0.0) ffff0000 uv 0.5,0.25 overlay 655360 light 15728880 normal 0.0,0.0,1.0;"
                + " host pose depth 1", game.call("", "draw", gem, "GEM", "GUI"),
                "the vertex carries the renderer's own translation, and the host's pose stack is left as it was");
        assertEquals("[moda/GemRenderer GUI gem light 15728880]", game.host("seen"), "renderByItem ran with ItemDisplayContext.GUI");

        Object orb = game.call(SECOND, "bake", "{\"type\":\"modb:retromod_legacy_item\",\"model\":\"modb:item/retromod/item_renderer/orb\"}");
        assertEquals("layer: transforms of modb:item/retromod/item_renderer/orb at FIRST_PERSON_RIGHT_HAND, animated, special;"
                + " geometry orb_translucent: (0.0,2.0,0.0) ffffffff uv 0.0,0.0 overlay 655360 light 15728880 normal 0.0,1.0,0.0;"
                + " host pose depth 1", game.call(SECOND, "draw", orb, "ORB", "FIRST_PERSON_RIGHT_HAND"),
                "the second jar's copy draws its renderer; what the renderer leaves unset keeps the draw's own values");
    }

    @Test
    @DisplayName("An item with no renderer draws its fallback, and one whose renderer throws draws its fallback from then on")
    void missingAndBrokenRenderersDrawTheFallback() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object plain = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/plain\","
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"moda:item/plain_icon\"}}");
        assertEquals("fallback moda:item/plain_icon", game.call("", "draw", plain, "PLAIN", "GUI"), "IClientItemExtensions.DEFAULT");

        Object broken = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/broken\","
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"moda:item/broken_icon\"}}");
        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\","
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"moda:item/gem_icon\"}}");
        assertEquals("layer: transforms of moda:item/broken at GROUND, animated, special; host pose depth 1",
                game.call("", "draw", broken, "BROKEN", "GROUND"), "the throw is caught, its half-written geometry dropped");
        assertEquals("fallback moda:item/broken_icon", game.call("", "draw", broken, "BROKEN", "GROUND"), "and the icon draws after");
        assertEquals("fallback moda:item/gem_icon", game.call("", "draw", gem, "GEM", "GUI"),
                "every item of the renderer that threw draws its icon, so one renderer fails once, not once per item");
        assertEquals("[moda/GemRenderer GROUND broken light 15728880]", game.host("seen"));

        Object rebaked = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        assertTrue(game.call("", "draw", rebaked, "GEM", "GUI").toString().startsWith("layer: "),
                "a resource reload gives the renderer another try");
    }

    @Test
    @DisplayName("The vertex buffers come back after the frame draws them, and the next draw reuses them")
    void vertexBuffersAreReused() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        game.call("", "draw", gem, "GEM", "GUI");
        Object first = game.host("lastGeometry");
        game.call("", "draw", gem, "GEM", "GUI");
        assertEquals(first, game.host("lastGeometry"), "the second draw records into the first draw's buffers");
    }

    @Test
    @DisplayName("On a 26.3 host, whose glint formats read UV3, every replayed vertex gets one")
    void replayWritesUv3On263() throws Throwable {
        Game game = game(true, true);
        game.call("", "construct", "moda");
        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        assertTrue(game.call("", "draw", gem, "GEM", "GUI").toString().contains("normal 0.0,0.0,1.0 uv3 0.0,0.0;"));
    }

    @Test
    @DisplayName("Two items submitted before the frame renders keep their own buffers")
    void itemsSubmittedBeforeTheFrameKeepTheirBuffers() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        Object orb = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/orb\"}");
        game.call("", "frame", gem, "GEM", orb, "ORB", false);
        assertEquals("[gem_cutout: (1.0,0.0,0.0)][orb_translucent: (0.0,2.0,0.0)]", game.call("", "frame", gem, "GEM", orb, "ORB", false));
    }

    @Test
    @DisplayName("A draw started inside another jar's renderer keeps both draws whole")
    void nestedForeignDrawKeepsBothDraws() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object orb = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/orb\"}");
        assertEquals("[orb_translucent: (0.0,2.0,0.0)][orb_translucent: (0.0,2.0,0.0) (0.0,3.0,0.0)]",
                game.call("", "frame", orb, "ORB", orb, "ORB", true));
    }

    @Test
    @DisplayName("A renderer that keeps its buffer source or a buffer past its draw writes into nothing, not the next item")
    void staleWritersNeverReachAnotherDraw() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        assertEquals("[gem_cutout: (1.0,0.0,0.0)]", game.call("", "staleWrites", gem),
                "the writes through the earlier draw's buffer and buffer source are dropped");
    }

    @Test
    @DisplayName("A kept buffer written after the next draw took the reused buffer stays out of it")
    void staleWriterAfterReuseStaysOut() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        assertEquals("[gem_cutout: (1.0,0.0,0.0)]", game.call("", "staleWritesLate", gem));
    }

    @Test
    @DisplayName("Another jar's renderer that asks its kept buffer source for a buffer after its draw gets one that keeps nothing")
    void staleForeignBufferSourceDoesNotThrow() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object orb = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/orb\"}");
        assertEquals("a buffer that keeps nothing", game.call("", "staleForeign", orb));
        assertEquals("[orb_translucent: (0.0,2.0,0.0)]", game.call("", "once", orb, "ORB"), "and the next draw is whole");
    }

    @Test
    @DisplayName("A pooled buffer that grew for one large model goes back to its starting size")
    void largeBuffersShrinkInThePool() throws Throwable {
        Game game = game(true);
        game.call("", "construct", "moda");
        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        game.call("", "extraVertices", 5_000);
        assertTrue(game.call("", "once", gem, "GEM").toString().length() > 5_000 * 6, "the large model was drawn whole");
        game.call("", "extraVertices", 0);
        assertEquals(9 * 24, game.call("", "largestPooled"), "the 5001-vertex buffer came back at its starting size");
    }

    @Test
    @DisplayName("Wrappers for another jar's renderers leave the cache once nothing keeps them")
    void foreignWrappersAreCollected() throws Throwable {
        Game game = game(true);
        // Like Cataclysm's, ORB's properties build a new renderer on every call, and Iceberg asks on every draw.
        assertEquals(10_000, game.call("", "askForeign", "ORB", 10_000));
        int left = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 50 && left > 100; attempt++) {
            System.gc();
            Thread.sleep(20);
            left = (Integer) game.call("", "foreignCached");
        }
        assertTrue(left <= 100, "the wrappers hold their renderers, yet " + left + " of 10000 stayed after collection");
    }

    @Test
    @DisplayName("getCustomRenderer answers the mod's own renderer, wraps another jar's, and gives the old default for the rest")
    void getCustomRendererAcrossCopies() throws Throwable {
        Game game = game(true);
        assertEquals("moda/GemRenderer, the same object", game.call("", "getter", "GEM"));
        assertEquals("this copy's base, drawing: (0.0,2.0,0.0)", game.call("", "getter", "ORB"),
                "the other jar's renderer, wrapped so the caller's cast holds, still draws into the caller's buffers");
        assertEquals("[modb/OrbRenderer FIRST_PERSON_RIGHT_HAND orb light 15728880]", game.host("seen"));
        assertEquals("this copy's base, drawing nothing", game.call("", "getter", "PLAIN"), "IClientItemExtensions.DEFAULT");
        assertEquals("this copy's base, drawing nothing", game.call("", "superGetter", "GEM"), "an override's super call");
    }

    @Test
    @DisplayName("On a dedicated server the install returns before loading any client class")
    void dedicatedServerInstallLoadsNoClientClass() throws Throwable {
        Game game = game(false);
        Class<?> context = Class.forName("net.minecraftforge.fml.ModLoadingContext", true, game.loader());
        context.getField("active").set(null, "moda");
        // Called as the constructor calls it: reflection over every method would load each parameter type.
        MethodHandles.publicLookup().findStatic(Class.forName(LegacyForgeItemRendererBridge.INSTALLER.replace('/', '.'), true,
                game.loader()), "install", MethodType.methodType(void.class)).invoke();
        context.getField("active").set(null, null);

        List<String> client = game.requested().stream().filter(name -> name.startsWith("net.minecraft.client.")).distinct().toList();
        assertEquals(List.of("net.minecraft.client.Minecraft"), client, "only the client probe, which finds nothing");
        assertTrue(game.requested().stream().noneMatch(name -> name.endsWith("LegacyItemRendererTypes")), "the client part never loads");
        assertEquals("[minecraft:model]", game.host("types"), "a server adds no type");
    }

    @Test
    @DisplayName("On NeoForge each mod registers its type through the item model event, and the item draws through its renderer")
    void neoForgeRegistersThroughTheEvent() throws Throwable {
        neoForgeHost();
        Game game = game(true, false, true);
        game.call("", "construct", "moda");
        game.call("", "construct", "moda");
        game.call(SECOND, "construct", "modb");
        assertEquals("[minecraft:model]", game.host("types"), "nothing is added before NeoForge posts its event");
        Class.forName("testhost.NeoBuses", true, game.loader()).getMethod("postItemModels").invoke(null);
        assertEquals("[minecraft:model, moda:retromod_legacy_item, modb:retromod_legacy_item]", game.host("types"),
                "each mod's listener registered its own type once, though moda's constructor ran the install twice");

        Object gem = game.call("", "bake", "{\"type\":\"moda:retromod_legacy_item\",\"model\":\"moda:item/gem\"}");
        assertEquals("layer: transforms of moda:item/gem at GUI, animated, special;"
                + " geometry gem_cutout: (1.0,0.0,0.0) ffff0000 uv 0.5,0.25 overlay 655360 light 15728880 normal 0.0,0.0,1.0;"
                + " host pose depth 1", game.call("", "draw", gem, "GEM", "GUI"),
                "NeoForge's IClientItemExtensions.of found the item's renderer");
        assertEquals("moda/GemRenderer, the same object", game.call("", "getter", "GEM"));
    }

    @Test
    @DisplayName("On a NeoForge dedicated server the install returns before loading any client class")
    void neoForgeServerInstallLoadsNoClientClass() throws Throwable {
        neoForgeHost();
        Game game = game(false, false, true);
        Class<?> context = Class.forName("net.neoforged.fml.ModLoadingContext", true, game.loader());
        context.getField("active").set(null, "moda");
        MethodHandles.publicLookup().findStatic(Class.forName(
                com.retromod.shim.neoforge.LegacyNeoForgeItemRendererBridge.INSTALLER.replace('/', '.'), true, game.loader()),
                "install", MethodType.methodType(void.class)).invoke();
        context.getField("active").set(null, null);

        List<String> client = game.requested().stream()
                .filter(name -> name.startsWith("net.minecraft.client.") || name.startsWith("net.neoforged.neoforge.client."))
                .distinct().toList();
        assertEquals(List.of("net.minecraft.client.Minecraft"), client, "only the client probe, which finds nothing");
        assertTrue(game.requested().stream().noneMatch(name -> name.endsWith("LegacyItemModelTypes")), "the client part never loads");
    }

    // What a NeoForge 26.2 host registers in place of the Forge bridge.
    private void neoForgeHost() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
        // The 26.2 shim's buffer source stand-in, which old renderers take there, then the bridge.
        new com.retromod.shim.neoforge.NeoForge_26_1_to_26_2().registerRedirects(transformer);
        com.retromod.shim.neoforge.LegacyNeoForgeItemRendererBridge.register(transformer);
    }

    // ---- the game ----

    private Game game(boolean client) throws Exception {
        return game(client, false);
    }

    private Game game(boolean client, boolean host263) throws Exception {
        return game(client, host263, false);
    }

    private Game game(boolean client, boolean host263, boolean neoForge) throws Exception {
        for (Path library : HOST_LIBRARIES) Assumptions.assumeTrue(Files.isRegularFile(library), "no " + library);
        Map<String, String> sources = hostSources();
        if (neoForge) sources.putAll(neoForgeSources());
        if (!client) sources.remove("net/minecraft/client/Minecraft.java");
        // Minecraft 26.3 made setUv3 an abstract member of VertexConsumer.
        if (host263) {
            sources.computeIfPresent("com/mojang/blaze3d/vertex/VertexConsumer.java", (name, source) -> source.replace(
                    "VertexConsumer setLineWidth(float width);", "VertexConsumer setLineWidth(float width);\n"
                            + "    VertexConsumer setUv3(float u, float v);"));
        }
        Path[] libraries = HOST_LIBRARIES.toArray(new Path[0]);
        Map<String, byte[]> hostClasses = compile(dir.resolve("host"), sources, libraries);
        List<Path> modPath = new ArrayList<>(HOST_LIBRARIES);
        modPath.add(0, dir.resolve("host").resolve("out"));
        Map<String, byte[]> mods = compile(dir.resolve("mods"), neoForge ? onNeoForge(modSources()) : modSources(),
                modPath.toArray(new Path[0]));
        // Retromod's classes, then the stand-ins, then the mods; each jar's copy is relocated as the embedder does.
        Map<String, byte[]> classes = new HashMap<>();
        for (Map<String, byte[]> layer : List.of(transformer.getSyntheticClasses(), hostClasses, mods)) {
            for (Map.Entry<String, byte[]> entry : layer.entrySet()) {
                String name = entry.getKey();
                classes.put(name, entry.getValue());
                if (relocated(name) || name.startsWith("testhost/Probe") || name.startsWith("modb/")) {
                    classes.put(relocation().map(name), remap(entry.getValue(), relocation()));
                }
            }
        }
        // The library jars sit below, as the game layer sits below the mods.
        URLClassLoader host = new URLClassLoader(urls(libraries), ClassLoader.getPlatformClassLoader());
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        return new Game(loader(classes, host, requested), requested);
    }

    private static boolean relocated(String name) {
        return RELOCATED.stream().anyMatch(name::startsWith);
    }

    private static Remapper relocation() {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                if (relocated(internalName)) return SECOND + internalName;
                return internalName.startsWith("testhost/Probe") ? SECOND + internalName : internalName;
            }
        };
    }

    /** Calls into one test game's probes, which drive the rebuilt classes through Minecraft's own interfaces. */
    private record Game(ClassLoader loader, List<String> requested) {

        Object call(String copy, String name, Object... args) throws Throwable {
            Class<?> probe = Class.forName((copy + "testhost/Probe").replace('/', '.'), true, loader);
            for (Method method : probe.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                try {
                    return method.invoke(null, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
            throw new AssertionError("no probe " + name);
        }

        String host(String name) throws Throwable {
            return String.valueOf(Class.forName("testhost.Notes", true, loader).getMethod(name).invoke(null));
        }
    }

    private static ClassLoader loader(Map<String, byte[]> classes, ClassLoader parent, List<String> requested) {
        return new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                requested.add(name);
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

    private static URL[] urls(Path[] jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return urls;
    }

    private static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static Map<String, byte[]> compile(Path work, Map<String, String> sources, Path... classpath) throws Exception {
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

    // ---- stand-in host classes ----

    /** Client, item and FML classes with the 26.2 members the rebuilt classes call, behaving as far as the item flow needs. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/minecraft/client/Minecraft.java", "package net.minecraft.client; public class Minecraft {}");
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    public static final com.mojang.serialization.Codec<Identifier> CODEC =
                            com.mojang.serialization.Codec.STRING.xmap(Identifier::new, Identifier::toString);
                    private final String id;
                    private Identifier(String id) { this.id = id; }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace + ":" + path); }
                    @Override public String toString() { return id; }
                    @Override public boolean equals(Object other) { return other instanceof Identifier that && that.id.equals(id); }
                    @Override public int hashCode() { return id.hashCode(); }
                }""");
        sources.put("net/minecraft/util/ExtraCodecs.java", """
                package net.minecraft.util;
                public class ExtraCodecs {
                    public static class LateBoundIdMapper<I, V> {
                        public final java.util.Map<I, V> ids = new java.util.LinkedHashMap<>();
                        public LateBoundIdMapper<I, V> put(I id, V value) { ids.put(id, value); return this; }
                    }
                }""");
        sources.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                public class Item {
                    private final String name;
                    private final Object renderProperties;
                    public Item(String name, Object renderProperties) { this.name = name; this.renderProperties = renderProperties; }
                    public Object getRenderPropertiesInternal() { return renderProperties; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack {
                    private final Item item;
                    public ItemStack(Item item) { this.item = item; }
                    public Item getItem() { return item; }
                    public ItemStack copy() { return new ItemStack(item); }
                }""");
        sources.put("net/minecraft/world/item/ItemDisplayContext.java", """
                package net.minecraft.world.item;
                public enum ItemDisplayContext {
                    NONE, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, HEAD, GUI,
                    GROUND, FIXED, ON_SHELF;
                    public boolean leftHand() { return this == THIRD_PERSON_LEFT_HAND || this == FIRST_PERSON_LEFT_HAND; }
                }""");
        sources.put("net/minecraftforge/client/extensions/common/IClientItemExtensions.java", """
                package net.minecraftforge.client.extensions.common;
                public interface IClientItemExtensions {
                    IClientItemExtensions DEFAULT = new IClientItemExtensions() {};
                    static IClientItemExtensions of(net.minecraft.world.item.ItemStack stack) {
                        return stack.getItem().getRenderPropertiesInternal() instanceof IClientItemExtensions extensions ? extensions : DEFAULT;
                    }
                }""");
        // A pose is a translation here, which is all the renderers below use.
        sources.put("com/mojang/blaze3d/vertex/PoseStack.java", """
                package com.mojang.blaze3d.vertex;
                public class PoseStack {
                    private final java.util.ArrayList<Pose> poses = new java.util.ArrayList<>(java.util.List.of(new Pose()));
                    public Pose last() { return poses.get(poses.size() - 1); }
                    public void pushPose() { Pose next = new Pose(); next.set(last()); poses.add(next); }
                    public void popPose() { poses.remove(poses.size() - 1); }
                    public void translate(float x, float y, float z) { last().x += x; last().y += y; last().z += z; }
                    public int depth() { return poses.size(); }
                    public boolean isEmpty() { return poses.size() == 1; }
                    public static final class Pose {
                        public float x, y, z;
                        public void set(Pose pose) { x = pose.x; y = pose.y; z = pose.z; }
                    }
                }""");
        sources.put("com/mojang/blaze3d/vertex/VertexConsumer.java", """
                package com.mojang.blaze3d.vertex;
                public interface VertexConsumer {
                    VertexConsumer addVertex(float x, float y, float z);
                    VertexConsumer setColor(int red, int green, int blue, int alpha);
                    VertexConsumer setColor(int argb);
                    VertexConsumer setUv(float u, float v);
                    VertexConsumer setUv1(int u, int v);
                    VertexConsumer setUv2(int u, int v);
                    VertexConsumer setNormal(float x, float y, float z);
                    VertexConsumer setLineWidth(float width);
                    default VertexConsumer setLight(int light) { return setUv2(light & 0xFFFF, light >> 16 & 0xFFFF); }
                    default VertexConsumer setOverlay(int overlay) { return setUv1(overlay & 0xFFFF, overlay >> 16 & 0xFFFF); }
                    default VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
                        return addVertex(pose.x + x, pose.y + y, pose.z + z);
                    }
                }""");
        // A proxy for another jar's MultiBufferSource reads every member of the stand-in, whose immediate() takes this.
        sources.put("com/mojang/blaze3d/vertex/ByteBufferBuilder.java", "package com.mojang.blaze3d.vertex; public class ByteBufferBuilder {}");
        sources.put("net/minecraft/client/renderer/rendertype/RenderType.java", """
                package net.minecraft.client.renderer.rendertype;
                public class RenderType {
                    private final String name;
                    public RenderType(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/renderer/OrderedSubmitNodeCollector.java", """
                package net.minecraft.client.renderer;
                public interface OrderedSubmitNodeCollector {
                    void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack poseStack,
                            net.minecraft.client.renderer.rendertype.RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer);
                }""");
        sources.put("net/minecraft/client/renderer/SubmitNodeCollector.java", """
                package net.minecraft.client.renderer;
                public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {
                    interface CustomGeometryRenderer {
                        void render(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer);
                    }
                }""");
        sources.put("net/minecraft/client/renderer/special/SpecialModelRenderer.java", """
                package net.minecraft.client.renderer.special;
                public interface SpecialModelRenderer<T> {
                    void submit(T argument, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor);
                    void getExtents(java.util.function.Consumer<org.joml.Vector3fc> output);
                    T extractArgument(net.minecraft.world.item.ItemStack stack);
                }""");
        sources.put("net/minecraft/client/renderer/item/ItemStackRenderState.java", """
                package net.minecraft.client.renderer.item;
                public class ItemStackRenderState {
                    public final java.util.List<LayerRenderState> layers = new java.util.ArrayList<>();
                    public final java.util.List<Object> identity = new java.util.ArrayList<>();
                    public boolean animated;
                    public LayerRenderState newLayer() { LayerRenderState layer = new LayerRenderState(); layers.add(layer); return layer; }
                    public void appendModelIdentityElement(Object element) { identity.add(element); }
                    public void setAnimated() { animated = true; }
                    public class LayerRenderState {
                        public String applied;
                        public net.minecraft.client.renderer.special.SpecialModelRenderer<Object> special;
                        public Object argument;
                        public String drawnModel;
                        public void setLocalTransform(org.joml.Matrix4fc transform) {}
                        public void setExtents(java.util.function.Supplier<org.joml.Vector3fc[]> extents) {
                            if (extents.get().length != 8) throw new IllegalStateException("not the unit cube");
                        }
                        @SuppressWarnings("unchecked")
                        public <T> void setupSpecialModel(net.minecraft.client.renderer.special.SpecialModelRenderer<T> renderer, T argument) {
                            special = (net.minecraft.client.renderer.special.SpecialModelRenderer<Object>) renderer;
                            this.argument = argument;
                        }
                    }
                }""");
        sources.put("net/minecraft/client/renderer/item/ItemModelResolver.java",
                "package net.minecraft.client.renderer.item; public class ItemModelResolver {}");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel {}");
        sources.put("net/minecraft/world/entity/ItemOwner.java", "package net.minecraft.world.entity; public interface ItemOwner {}");
        sources.put("net/minecraft/client/resources/model/ResolvableModel.java", """
                package net.minecraft.client.resources.model;
                public interface ResolvableModel {
                    void resolveDependencies(Resolver resolver);
                    interface Resolver { void markDependency(net.minecraft.resources.Identifier id); }
                }""");
        sources.put("net/minecraft/client/resources/model/sprite/TextureSlots.java",
                "package net.minecraft.client.resources.model.sprite; public class TextureSlots {}");
        sources.put("net/minecraft/client/resources/model/ResolvedModel.java", """
                package net.minecraft.client.resources.model;
                public interface ResolvedModel {
                    default net.minecraft.client.resources.model.sprite.TextureSlots getTopTextureSlots() {
                        return new net.minecraft.client.resources.model.sprite.TextureSlots();
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelBaker.java", """
                package net.minecraft.client.resources.model;
                public interface ModelBaker { ResolvedModel getModel(net.minecraft.resources.Identifier id); }""");
        sources.put("net/minecraft/client/renderer/item/ModelRenderProperties.java", """
                package net.minecraft.client.renderer.item;
                public final class ModelRenderProperties {
                    private final String model;
                    private ModelRenderProperties(String model) { this.model = model; }
                    public static ModelRenderProperties fromResolvedModel(net.minecraft.client.resources.model.ModelBaker baker,
                            net.minecraft.client.resources.model.ResolvedModel model, net.minecraft.client.resources.model.sprite.TextureSlots slots) {
                        return new ModelRenderProperties(model.toString());
                    }
                    public void applyToLayer(ItemStackRenderState.LayerRenderState layer, net.minecraft.world.item.ItemDisplayContext context) {
                        layer.applied = "transforms of " + model + " at " + context;
                    }
                }""");
        sources.put("net/minecraft/client/renderer/item/ItemModel.java", """
                package net.minecraft.client.renderer.item;
                public interface ItemModel {
                    void update(ItemStackRenderState renderState, net.minecraft.world.item.ItemStack stack, ItemModelResolver resolver,
                            net.minecraft.world.item.ItemDisplayContext displayContext, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.world.entity.ItemOwner owner, int seed);
                    interface Unbaked extends net.minecraft.client.resources.model.ResolvableModel {
                        com.mojang.serialization.MapCodec<? extends Unbaked> type();
                        ItemModel bake(BakingContext context, org.joml.Matrix4fc transformation);
                    }
                    record BakingContext(net.minecraft.client.resources.model.ModelBaker blockModelBaker) {}
                }""");
        // Minecraft's own type ids, with minecraft:model standing in for the flat icon models.
        sources.put("net/minecraft/client/renderer/item/ItemModels.java", """
                package net.minecraft.client.renderer.item;
                import com.mojang.serialization.MapCodec;
                import net.minecraft.resources.Identifier;
                public class ItemModels {
                    private static final net.minecraft.util.ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemModel.Unbaked>>
                            ID_MAPPER = new net.minecraft.util.ExtraCodecs.LateBoundIdMapper<>();
                    public static final com.mojang.serialization.Codec<ItemModel.Unbaked> CODEC =
                            Identifier.CODEC.<ItemModel.Unbaked>dispatch(ItemModels::idOf, id -> ID_MAPPER.ids.get(id));
                    static { ID_MAPPER.put(Identifier.fromNamespaceAndPath("minecraft", "model"), testhost.PlainModel.CODEC); }
                    private static Identifier idOf(ItemModel.Unbaked unbaked) {
                        for (java.util.Map.Entry<Identifier, MapCodec<? extends ItemModel.Unbaked>> entry : ID_MAPPER.ids.entrySet()) {
                            if (entry.getValue() == unbaked.type()) return entry.getKey();
                        }
                        return null;
                    }
                    public static java.util.Set<Identifier> types() { return ID_MAPPER.ids.keySet(); }
                    public static void add(Identifier id, MapCodec<? extends ItemModel.Unbaked> codec) {
                        if (ID_MAPPER.ids.containsKey(id)) throw new IllegalStateException("registered twice: " + id);
                        ID_MAPPER.put(id, codec);
                    }
                }""");
        sources.put("testhost/PlainModel.java", """
                package testhost;
                import net.minecraft.client.renderer.item.ItemModel;
                public record PlainModel(net.minecraft.resources.Identifier model) implements ItemModel.Unbaked {
                    public static final com.mojang.serialization.MapCodec<PlainModel> CODEC =
                            net.minecraft.resources.Identifier.CODEC.fieldOf("model").xmap(PlainModel::new, PlainModel::model);
                    public com.mojang.serialization.MapCodec<PlainModel> type() { return CODEC; }
                    public void resolveDependencies(Resolver resolver) { resolver.markDependency(model); }
                    public ItemModel bake(ItemModel.BakingContext context, org.joml.Matrix4fc transformation) {
                        return (state, stack, resolver, displayContext, level, owner, seed) -> state.newLayer().drawnModel = model.toString();
                    }
                }""");
        sources.put("net/minecraft/server/packs/resources/ResourceManager.java",
                "package net.minecraft.server.packs.resources; public interface ResourceManager {}");
        sources.put("net/minecraft/server/packs/resources/ResourceManagerReloadListener.java", """
                package net.minecraft.server.packs.resources;
                public interface ResourceManagerReloadListener { void onResourceManagerReload(ResourceManager manager); }""");
        sources.put("net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher.java",
                "package net.minecraft.client.renderer.blockentity; public class BlockEntityRenderDispatcher {}");
        sources.put("net/minecraft/client/model/geom/EntityModelSet.java", "package net.minecraft.client.model.geom; public class EntityModelSet {}");
        sources.put("net/minecraftforge/eventbus/api/bus/BusGroup.java", "package net.minecraftforge.eventbus.api.bus; public interface BusGroup {}");
        sources.put("net/minecraftforge/fml/ModContainer.java", """
                package net.minecraftforge.fml;
                public class ModContainer {
                    public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return null; }
                }""");
        sources.put("net/minecraftforge/fml/ModLoadingContext.java", """
                package net.minecraftforge.fml;
                public class ModLoadingContext {
                    public static String active;
                    public static ModLoadingContext get() { return new ModLoadingContext(); }
                    public String getActiveNamespace() { return active; }
                    public ModContainer getContainer() { return active == null ? null : new ModContainer(); }
                }""");
        sources.put("testhost/Notes.java", """
                package testhost;
                public final class Notes {
                    public static final java.util.List<String> SEEN = new java.util.ArrayList<>();
                    public static final java.util.List<String> DEPENDENCIES = new java.util.ArrayList<>();
                    public static final net.minecraft.client.renderer.rendertype.RenderType CUTOUT =
                            new net.minecraft.client.renderer.rendertype.RenderType("gem_cutout");
                    public static final net.minecraft.client.renderer.rendertype.RenderType TRANSLUCENT =
                            new net.minecraft.client.renderer.rendertype.RenderType("orb_translucent");
                    public static Object LAST_GEOMETRY;
                    public static Runnable NESTED;
                    // Run by the gem renderer before it draws, to write through what an earlier draw kept.
                    public static Runnable STALE;
                    public static Runnable LATE;
                    // Extra vertices the gem renderer writes, for a large model.
                    public static int EXTRA;
                    public static String seen() { return SEEN.toString(); }
                    public static Object lastGeometry() { return LAST_GEOMETRY; }
                    public static String dependencies() { return DEPENDENCIES.toString(); }
                    public static String types() { return new java.util.TreeSet<>(
                            net.minecraft.client.renderer.item.ItemModels.types().stream().map(Object::toString).toList()).toString(); }
                }""");
        return sources;
    }

    /** NeoForge's FML, event bus, item model event and client extensions, as far as the install and the model need them. */
    private static Map<String, String> neoForgeSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/neoforged/bus/api/Event.java", "package net.neoforged.bus.api; public abstract class Event {}");
        sources.put("net/neoforged/bus/api/IEventBus.java", """
                package net.neoforged.bus.api;
                public interface IEventBus {
                    <T extends Event> void addListener(Class<T> eventType, java.util.function.Consumer<T> consumer);
                }""");
        sources.put("net/neoforged/fml/ModContainer.java", """
                package net.neoforged.fml;
                public class ModContainer {
                    private final String namespace;
                    public ModContainer(String namespace) { this.namespace = namespace; }
                    public final String getNamespace() { return namespace; }
                    public net.neoforged.bus.api.IEventBus getEventBus() { return testhost.NeoBuses.of(namespace); }
                }""");
        sources.put("net/neoforged/fml/ModLoadingContext.java", """
                package net.neoforged.fml;
                public class ModLoadingContext {
                    public static String active;
                    public static ModLoadingContext get() { return new ModLoadingContext(); }
                    public ModContainer getActiveContainer() { return active == null ? null : new ModContainer(active); }
                }""");
        sources.put("net/neoforged/neoforge/client/event/RegisterItemModelsEvent.java", """
                package net.neoforged.neoforge.client.event;
                public class RegisterItemModelsEvent extends net.neoforged.bus.api.Event {
                    public void register(net.minecraft.resources.Identifier id,
                            com.mojang.serialization.MapCodec<? extends net.minecraft.client.renderer.item.ItemModel.Unbaked> codec) {
                        net.minecraft.client.renderer.item.ItemModels.add(id, codec);
                    }
                }""");
        sources.put("net/neoforged/neoforge/client/extensions/common/IClientItemExtensions.java", """
                package net.neoforged.neoforge.client.extensions.common;
                public interface IClientItemExtensions {
                    IClientItemExtensions DEFAULT = new IClientItemExtensions() {};
                    static IClientItemExtensions of(net.minecraft.world.item.ItemStack stack) {
                        return stack.getItem().getRenderPropertiesInternal() instanceof IClientItemExtensions extensions ? extensions : DEFAULT;
                    }
                }""");
        // Each mod's bus, which NeoForge posts its mod-bus events to.
        sources.put("testhost/NeoBuses.java", """
                package testhost;
                import net.neoforged.bus.api.Event;
                public final class NeoBuses {
                    private static final java.util.Map<String, Bus> BUSES = new java.util.LinkedHashMap<>();
                    public static Bus of(String namespace) { return BUSES.computeIfAbsent(namespace, any -> new Bus()); }
                    public static void postItemModels() {
                        for (Bus bus : BUSES.values()) bus.post(new net.neoforged.neoforge.client.event.RegisterItemModelsEvent());
                    }
                    public static final class Bus implements net.neoforged.bus.api.IEventBus {
                        private final java.util.List<Object[]> listeners = new java.util.ArrayList<>();
                        public <T extends Event> void addListener(Class<T> type, java.util.function.Consumer<T> consumer) {
                            listeners.add(new Object[]{type, consumer});
                        }
                        @SuppressWarnings("unchecked")
                        void post(Event event) {
                            for (Object[] listener : listeners) {
                                if (((Class<?>) listener[0]).isInstance(event)) ((java.util.function.Consumer<Event>) listener[1]).accept(event);
                            }
                        }
                    }
                }""");
        return sources;
    }

    /** The same mods built for NeoForge 1.21.1: NeoForge's client extensions, and NeoForge's install in each constructor. */
    private static Map<String, String> onNeoForge(Map<String, String> sources) {
        Map<String, String> neo = new LinkedHashMap<>();
        sources.forEach((name, source) -> neo.put(name, source
                .replace("net.minecraftforge.client.extensions.common.IClientItemExtensions",
                        "net.neoforged.neoforge.client.extensions.common.IClientItemExtensions")
                .replace("com.retromod.legacyapi.forge.client.renderer.LegacyItemRenderers",
                        "com.retromod.legacyapi.neoforge.client.renderer.LegacyItemRenderers")
                .replace("net.minecraftforge.fml.ModLoadingContext.active", "net.neoforged.fml.ModLoadingContext.active")));
        return neo;
    }

    /** Two old mods' renderers and render properties as Retromod translates them, the items, and a probe per copy. */
    private static Map<String, String> modSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        String imports = """
                import com.mojang.blaze3d.vertex.PoseStack;
                import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRenderer;
                import com.retromod.shim.common.embedded.MultiBufferSource;
                import net.minecraft.world.item.ItemDisplayContext;
                import net.minecraft.world.item.ItemStack;
                """;
        sources.put("moda/GemRenderer.java", "package moda;\n" + imports + """
                public class GemRenderer extends LegacyItemRenderer {
                    public static final GemRenderer INSTANCE = new GemRenderer();
                    // What the last draw handed it, which a careless renderer keeps.
                    public static MultiBufferSource KEPT_SOURCE;
                    public static com.mojang.blaze3d.vertex.VertexConsumer KEPT_BUFFER;
                    public GemRenderer() { super(null, null); }
                    @Override
                    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers,
                            int light, int overlay) {
                        Runnable stale = testhost.Notes.STALE;
                        if (stale != null) { testhost.Notes.STALE = null; stale.run(); }
                        KEPT_SOURCE = buffers;
                        com.mojang.blaze3d.vertex.VertexConsumer fresh = buffers.getBuffer(testhost.Notes.CUTOUT);
                        Runnable late = testhost.Notes.LATE;
                        if (late != null) { testhost.Notes.LATE = null; late.run(); }
                        KEPT_BUFFER = fresh;
                        for (int i = 0; i < testhost.Notes.EXTRA; i++) KEPT_BUFFER.addVertex(0, 0, 0);
                        testhost.Notes.SEEN.add("moda/GemRenderer " + context + " " + stack.getItem() + " light " + light);
                        pose.pushPose();
                        pose.translate(1, 0, 0);
                        buffers.getBuffer(testhost.Notes.CUTOUT).addVertex(pose.last(), 0, 0, 0).setColor(255, 0, 0, 255).setUv(0.5f, 0.25f)
                                .setOverlay(overlay).setLight(light).setNormal(0, 0, 1);
                        // A renderer that reaches a removed vanilla API fails partway, as one calling ItemRenderer would.
                        if (stack.getItem().toString().equals("broken")) throw new NoSuchMethodError("ItemRenderer.renderStatic");
                        pose.popPose();
                    }
                }""");
        sources.put("moda/GemProperties.java", """
                package moda;
                public class GemProperties implements net.minecraftforge.client.extensions.common.IClientItemExtensions {
                    public com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRenderer getCustomRenderer() { return GemRenderer.INSTANCE; }
                }""");
        sources.put("modb/OrbRenderer.java", "package modb;\n" + imports + """
                public class OrbRenderer extends LegacyItemRenderer {
                    // The buffer source the last draw handed it, which a careless renderer keeps.
                    public static Object KEPT;
                    public OrbRenderer() { super(null, null); }
                    @Override
                    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers,
                            int light, int overlay) {
                        KEPT = buffers;
                        testhost.Notes.SEEN.add("modb/OrbRenderer " + context + " " + stack.getItem() + " light " + light);
                        buffers.getBuffer(testhost.Notes.TRANSLUCENT).addVertex(pose.last(), 0, 2, 0);
                        Runnable nested = testhost.Notes.NESTED;
                        if (nested != null) { testhost.Notes.NESTED = null; nested.run();
                            buffers.getBuffer(testhost.Notes.TRANSLUCENT).addVertex(pose.last(), 0, 3, 0); }
                    }
                }""");
        // Like Cataclysm's, its getter builds a new renderer on every call.
        sources.put("modb/OrbProperties.java", """
                package modb;
                class OrbProperties implements net.minecraftforge.client.extensions.common.IClientItemExtensions {
                    public com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRenderer getCustomRenderer() { return new OrbRenderer(); }
                }""");
        sources.put("testhost/Items.java", """
                package testhost;
                import net.minecraft.world.item.Item;
                public final class Items {
                    public static final Item GEM = new Item("gem", new moda.GemProperties());
                    public static final Item BROKEN = new Item("broken", new moda.GemProperties());
                    public static final Item ORB = new Item("orb", modb.OrbFactory.properties());
                    public static final Item PLAIN = new Item("plain", null);
                    public static Item named(String name) throws ReflectiveOperationException { return (Item) Items.class.getField(name).get(null); }
                }""");
        sources.put("modb/OrbFactory.java", """
                package modb;
                public final class OrbFactory {
                    public static Object properties() { return new OrbProperties(); }
                }""");
        sources.put("testhost/Probe.java", """
                package testhost;
                import com.mojang.blaze3d.vertex.PoseStack;
                import com.mojang.blaze3d.vertex.VertexConsumer;
                import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRenderer;
                import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRendererCalls;
                import com.retromod.legacyapi.forge.client.renderer.LegacyItemRenderers;
                import net.minecraft.client.renderer.SubmitNodeCollector;
                import net.minecraft.client.renderer.item.ItemModel;
                import net.minecraft.client.renderer.item.ItemModels;
                import net.minecraft.client.renderer.item.ItemStackRenderState;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.world.item.ItemDisplayContext;
                import net.minecraft.world.item.ItemStack;
                import net.minecraftforge.client.extensions.common.IClientItemExtensions;

                /** Drives this copy of the rebuilt classes through Minecraft's own interfaces, as the game would. */
                public final class Probe {
                    /** One mod constructing: FML names it, then the install Retromod put at the head of its constructor. */
                    public static void construct(String modId) {
                        net.minecraftforge.fml.ModLoadingContext.active = modId;
                        LegacyItemRenderers.install();
                        net.minecraftforge.fml.ModLoadingContext.active = null;
                    }

                    /** An item definition's model, read by Minecraft's codec and baked. */
                    public static Object bake(String json) {
                        ItemModel.Unbaked unbaked = ItemModels.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                                com.google.gson.JsonParser.parseString(json)).result().orElseThrow();
                        Notes.DEPENDENCIES.clear();
                        unbaked.resolveDependencies(id -> Notes.DEPENDENCIES.add(id.toString()));
                        return unbaked.bake(new ItemModel.BakingContext(id -> new net.minecraft.client.resources.model.ResolvedModel() {
                            @Override public String toString() { return id.toString(); }
                        }), new org.joml.Matrix4f());
                    }

                    /** One stack drawn: the model updates its render state, then the frame submits each layer and draws what it got. */
                    public static String draw(Object model, String item, String context) throws ReflectiveOperationException {
                        ItemStackRenderState state = new ItemStackRenderState();
                        ((ItemModel) model).update(state, new ItemStack(Items.named(item)), null, ItemDisplayContext.valueOf(context), null, null, 0);
                        StringBuilder out = new StringBuilder();
                        PoseStack host = new PoseStack();
                        for (ItemStackRenderState.LayerRenderState layer : state.layers) {
                            if (layer.drawnModel != null) {
                                out.append("fallback ").append(layer.drawnModel);
                                continue;
                            }
                            out.append("layer: ").append(layer.applied).append(state.animated ? ", animated" : "")
                                    .append(layer.special != null && state.identity.contains(model) ? ", special" : "");
                            layer.special.submit(layer.argument, host, (pose, type, geometry) -> {
                                Notes.LAST_GEOMETRY = geometry;
                                Out drawn = new Out();
                                geometry.render(pose.last(), drawn);
                                out.append("; geometry ").append(type).append(": ").append(drawn.text.toString().trim());
                            }, 15728880, 655360, false, 0);
                            out.append("; host pose depth ").append(host.depth());
                        }
                        return out.toString();
                    }

                    /** getCustomRenderer on an item's properties, and what the renderer draws into this mod's own buffers. */
                    public static String getter(String item) throws ReflectiveOperationException {
                        return describe(LegacyItemRendererCalls.getCustomRenderer(
                                IClientItemExtensions.of(new ItemStack(Items.named(item)))), item);
                    }

                    public static String superGetter(String item) throws ReflectiveOperationException {
                        return describe(LegacyItemRendererCalls.superGetCustomRenderer(
                                IClientItemExtensions.of(new ItemStack(Items.named(item)))), item);
                    }

                    private static String describe(LegacyItemRenderer renderer, String item) throws ReflectiveOperationException {
                        if (renderer.getClass().getName().startsWith("moda.")) {
                            return renderer.getClass().getName().replace('.', '/') + (renderer == getterAgain(item) ? ", the same object" : "");
                        }
                        Out drawn = new Out();
                        renderer.renderByItem(new ItemStack(Items.named(item)), ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, new PoseStack(),
                                type -> drawn, 15728880, 655360);
                        String text = drawn.text.toString().trim();
                        return "this copy's base, drawing" + (text.isEmpty() ? " nothing" : ": " + text);
                    }

                    private static Object getterAgain(String item) throws ReflectiveOperationException {
                        return LegacyItemRendererCalls.getCustomRenderer(IClientItemExtensions.of(new ItemStack(Items.named(item))));
                    }

                    /** A frame buffer that writes each vertex it gets as text. */
                    static final class Out implements VertexConsumer {
                        final StringBuilder text = new StringBuilder();
                        public VertexConsumer addVertex(float x, float y, float z) { text.append(" (").append(x).append(',').append(y).append(',').append(z).append(')'); return this; }
                        public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(a << 24 | r << 16 | g << 8 | b); }
                        public VertexConsumer setColor(int argb) { text.append(' ').append(Integer.toHexString(argb)); return this; }
                        public VertexConsumer setUv(float u, float v) { text.append(" uv ").append(u).append(',').append(v); return this; }
                        public VertexConsumer setUv1(int u, int v) { text.append(" overlay ").append(u | v << 16); return this; }
                        public VertexConsumer setUv2(int u, int v) { text.append(" light ").append(u | v << 16); return this; }
                        public VertexConsumer setNormal(float x, float y, float z) { text.append(" normal ").append(x).append(',').append(y).append(',').append(z); return this; }
                        public VertexConsumer setLineWidth(float width) { return this; }
                        public VertexConsumer setUv3(float u, float v) { text.append(" uv3 ").append(u).append(',').append(v); return this; }
                    }

                    /** Submits both items into one deferred collector, then renders every submit, as the host's buildGroup does. */
                    public static String frame(Object modelA, String itemA, Object modelB, String itemB, boolean nest) throws ReflectiveOperationException {
                        java.util.List<Object[]> submitted = new java.util.ArrayList<>();
                        SubmitNodeCollector collector = (pose, type, geometry) -> submitted.add(new Object[]{type, geometry, pose.last()});
                        if (nest) Notes.NESTED = () -> { try { submitAll(modelB, itemB, collector); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); } };
                        submitAll(modelA, itemA, collector);
                        if (!nest) submitAll(modelB, itemB, collector);
                        Notes.NESTED = null;
                        StringBuilder out = new StringBuilder();
                        for (Object[] s : submitted) {
                            Positions drawn = new Positions();
                            ((SubmitNodeCollector.CustomGeometryRenderer) s[1]).render((PoseStack.Pose) s[2], drawn);
                            out.append('[').append(s[0]).append(':').append(drawn.text).append(']');
                        }
                        return out.toString();
                    }

                    private static void submitAll(Object model, String item, SubmitNodeCollector collector) throws ReflectiveOperationException {
                        ItemStackRenderState state = new ItemStackRenderState();
                        ((ItemModel) model).update(state, new ItemStack(Items.named(item)), null, ItemDisplayContext.GUI, null, null, 0);
                        for (ItemStackRenderState.LayerRenderState layer : state.layers) {
                            if (layer.special != null) layer.special.submit(layer.argument, new PoseStack(), collector, 15728880, 655360, false, 0);
                        }
                    }

                    static final class Positions implements VertexConsumer {
                        final StringBuilder text = new StringBuilder();
                        public VertexConsumer addVertex(float x, float y, float z) { text.append(" (").append(x).append(',').append(y).append(',').append(z).append(')'); return this; }
                        public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
                        public VertexConsumer setColor(int argb) { return this; }
                        public VertexConsumer setUv(float u, float v) { return this; }
                        public VertexConsumer setUv1(int u, int v) { return this; }
                        public VertexConsumer setUv2(int u, int v) { return this; }
                        public VertexConsumer setNormal(float x, float y, float z) { return this; }
                        public VertexConsumer setLineWidth(float width) { return this; }
                        public VertexConsumer setUv3(float u, float v) { return this; }
                    }

                    /** getCustomRenderer asked over and over for an item whose properties build a new renderer each time. */
                    public static int askForeign(String item, int times) throws ReflectiveOperationException {
                        int wrapped = 0;
                        for (int i = 0; i < times; i++) {
                            LegacyItemRenderer renderer = LegacyItemRendererCalls.getCustomRenderer(
                                    IClientItemExtensions.of(new ItemStack(Items.named(item))));
                            if (!renderer.getClass().getName().startsWith("modb.")) wrapped++;
                        }
                        return wrapped;
                    }

                    /** How many other-jar renderers this copy's wrapper cache still holds. */
                    /** One stack drawn and its frame rendered, as positions per render type. */
                    public static String once(Object model, String item) throws ReflectiveOperationException {
                        java.util.List<Object[]> submitted = new java.util.ArrayList<>();
                        submitAll(model, item, (pose, type, geometry) -> submitted.add(new Object[]{type, geometry, pose.last()}));
                        StringBuilder out = new StringBuilder();
                        for (Object[] s : submitted) {
                            Positions drawn = new Positions();
                            ((SubmitNodeCollector.CustomGeometryRenderer) s[1]).render((PoseStack.Pose) s[2], drawn);
                            out.append('[').append(s[0]).append(':').append(drawn.text).append(']');
                        }
                        return out.toString();
                    }

                    /** The gem drawn again; the last draw's kept buffer is written after this draw took its own. */
                    public static String staleWritesLate(Object model) throws ReflectiveOperationException {
                        once(model, "GEM");
                        com.mojang.blaze3d.vertex.VertexConsumer oldBuffer = moda.GemRenderer.KEPT_BUFFER;
                        Notes.LATE = () -> oldBuffer.addVertex(9, 9, 9);
                        return once(model, "GEM");
                    }

                    /** The gem drawn again while the buffer source and buffer its last draw kept are written to. */
                    public static String staleWrites(Object model) throws ReflectiveOperationException {
                        once(model, "GEM");
                        Notes.STALE = () -> {
                            moda.GemRenderer.KEPT_BUFFER.addVertex(9, 9, 9);
                            moda.GemRenderer.KEPT_SOURCE.getBuffer(Notes.CUTOUT).addVertex(8, 8, 8);
                            moda.GemRenderer.KEPT_SOURCE.getBuffer(Notes.TRANSLUCENT).addVertex(7, 7, 7);
                        };
                        return once(model, "GEM");
                    }

                    /** Another jar's renderer asking the buffer source it kept for a buffer after its draw. */
                    public static String staleForeign(Object model) throws ReflectiveOperationException {
                        once(model, "ORB");
                        Object kept = Class.forName("modb.OrbRenderer").getField("KEPT").get(null);
                        Object buffer = kept.getClass().getMethod("getBuffer", RenderType.class).invoke(kept, Notes.TRANSLUCENT);
                        ((VertexConsumer) buffer).addVertex(5, 5, 5);
                        return buffer == null ? "no buffer" : "a buffer that keeps nothing";
                    }

                    /** The largest vertex array a pooled buffer keeps, in floats. */
                    public static int largestPooled() throws ReflectiveOperationException {
                        Class<?> geometry = Class.forName("com.retromod.legacyapi.minecraft.client.renderer.LegacyItemGeometry");
                        java.lang.reflect.Field pool = geometry.getDeclaredField("POOL");
                        pool.setAccessible(true);
                        int largest = 0;
                        for (Object vertices : (java.util.Collection<?>) pool.get(null)) {
                            java.lang.reflect.Field floats = vertices.getClass().getDeclaredField("floats");
                            floats.setAccessible(true);
                            largest = Math.max(largest, ((float[]) floats.get(vertices)).length);
                        }
                        return largest;
                    }

                    public static void extraVertices(int count) {
                        Notes.EXTRA = count;
                    }

                    public static int foreignCached() throws ReflectiveOperationException {
                        java.lang.reflect.Field field = LegacyItemRendererCalls.class.getDeclaredField("FOREIGN");
                        field.setAccessible(true);
                        java.util.Map<?, ?> cache = (java.util.Map<?, ?>) field.get(null);
                        synchronized (cache) {
                            return cache.size();
                        }
                    }
                }""");
        return sources;
    }
}
