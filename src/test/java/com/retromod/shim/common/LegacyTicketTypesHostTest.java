/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_4_to_1_21_5;
import com.retromod.shim.neoforge.NeoForge_1_21_4_to_1_21_5;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.retromod.shim.common.LegacyChunkTicketBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The rebuilt TicketType.create run on each ticket type shape from 1.21.5 on, and on the real 26.3 ticket storage. */
class LegacyTicketTypesHostTest {

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

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void the1215ShapeGetsATicketThatLoadsAndSimulates() throws Exception {
        Map<String, byte[]> classes = new HashMap<>(Map.of(TYPES, helper("26.3", new NeoForge_1_21_4_to_1_21_5(), TYPES)));
        classes.putAll(renamed(Map.of(Shape1215.class, TICKET_TYPE, Use1215.class, TICKET_TYPE + "$TicketUse")));
        ClassLoader loader = new DefiningLoader(ClassLoader.getPlatformClassLoader(), classes);

        Object timed = create(loader.loadClass(dotted(TYPES)), "lootr_entity_tick_ticket", 300);
        assertEquals(300L, timed.getClass().getField("timeout").get(timed));
        assertEquals(false, timed.getClass().getField("persist").get(timed), "an old ticket was never saved");
        assertEquals("LOADING_AND_SIMULATION", timed.getClass().getField("use").get(timed).toString());
    }

    @Test
    void anIntermediaryHostGetsTheSameFlags() throws Exception {
        Map<String, byte[]> classes = new HashMap<>(Map.of(INTERMEDIARY_TYPES,
                helper("1.21.11", new Fabric_1_21_4_to_1_21_5(), INTERMEDIARY_TYPES)));
        classes.putAll(renamed(Map.of(Shape1219.class, INTERMEDIARY_TICKET_TYPE)));
        Class<?> helper = new DefiningLoader(ClassLoader.getPlatformClassLoader(), classes).loadClass(dotted(INTERMEDIARY_TYPES));

        Object untimed = create(helper, "chunky", null);
        assertEquals(0L, untimed.getClass().getField("timeout").get(untimed));
        assertEquals(2 | 4, untimed.getClass().getField("flags").get(untimed), "loading and simulation");
        Object timed = create(helper, "chunky_ticking", 300);
        assertEquals(2 | 4 | 16, timed.getClass().getField("flags").get(timed), "a timed ticket also lapses while its chunk is unloaded");
    }

    @Test
    void oldTicketsWorkInThe263TicketStorageAndStayOutOfItsSave() throws Exception {
        List<URL> host = new ArrayList<>();
        for (String jar : HOST) {
            Path file = LIBRARIES.resolve(jar);
            assumeTrue(Files.isRegularFile(file), "no local " + file.getFileName() + "; the 26.3 game jars are local test material");
            host.add(file.toUri().toURL());
        }
        byte[] helperBytes = helper("26.3", new Fabric_1_21_4_to_1_21_5(), TYPES);
        try (URLClassLoader game = new URLClassLoader(host.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            bootstrap(game);
            Class<?> helper = new DefiningLoader(game, Map.of(TYPES, helperBytes)).loadClass(dotted(TYPES));

            Object chunky = create(helper, "chunky", null);
            assertEquals(6, call(chunky, "flags"), "loads and simulates, like DRAGON");
            assertEquals(false, call(chunky, "persist"));
            assertEquals(false, call(chunky, "shouldKeepDimensionActive"), "only forced chunks kept a dimension running before");
            assertEquals(false, call(chunky, "hasTimeout"));
            Object lootr = create(helper, "lootr_entity_tick_ticket", 300);
            assertEquals(300L, call(lootr, "timeout"));
            assertEquals(true, call(lootr, "canExpireIfUnloaded"));

            // Two mods' types are equal records, yet each keeps its own tickets, because tickets match by identity.
            Object convert = create(helper, "lootr_convert", null);
            assertEquals(chunky, convert);
            assertNotSame(chunky, convert);
            Class<?> storageType = game.loadClass("net.minecraft.world.level.TicketStorage");
            Class<?> ticketType = game.loadClass("net.minecraft.server.level.TicketType");
            Class<?> chunkPos = game.loadClass("net.minecraft.world.level.ChunkPos");
            Object storage = storageType.getConstructor().newInstance();
            Object pos = chunkPos.getConstructor(int.class, int.class).newInstance(3, -2);
            Method add = storageType.getMethod("addTicketWithRadius", ticketType, chunkPos, int.class);
            Method remove = storageType.getMethod("removeTicketWithRadius", ticketType, chunkPos, int.class);
            add.invoke(storage, chunky, pos, 0);
            add.invoke(storage, convert, pos, 0);
            remove.invoke(storage, chunky, pos, 0);
            List<?> left = (List<?>) storageType.getMethod("getTickets", long.class).invoke(storage, call(pos, "pack"));
            assertEquals(1, left.size(), "removing one mod's ticket leaves the other's");
            assertSame(convert, call(left.get(0), "getType"));
            assertEquals(false, call(storage, "shouldKeepDimensionActive"));

            // The save holds only persistent tickets, so the unregistered type never reaches the registry codec.
            Object codec = storageType.getField("CODEC").get(null);
            Class<?> ops = game.loadClass("com.mojang.serialization.DynamicOps");
            Object nbt = game.loadClass("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
            Object result = game.loadClass("com.mojang.serialization.Encoder").getMethod("encodeStart", ops, Object.class)
                    .invoke(codec, nbt, storage);
            Optional<?> saved = (Optional<?>) game.loadClass("com.mojang.serialization.DataResult").getMethod("result").invoke(result);
            assertTrue(saved.isPresent(), "the ticket storage saves: " + result);
        }
    }

    /** The helper bytes a shim adds to a mod for the given host. */
    private static byte[] helper(String target, VersionShim shim, String name) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        shim.registerRedirects(transformer);
        byte[] bytes = transformer.getSyntheticClasses().get(name);
        assertNotNull(bytes, shim.getShimName() + " adds " + name);
        return bytes;
    }

    private static Object create(Class<?> helper, String name, Integer lifespan) throws Exception {
        if (lifespan == null) return helper.getMethod("create", String.class, Comparator.class).invoke(null, name, null);
        return helper.getMethod("create", String.class, Comparator.class, int.class).invoke(null, name, null, lifespan);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static void bootstrap(ClassLoader game) throws Exception {
        // Bootstrap routes the standard streams into the game's logger, which this test JVM keeps using.
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            Class.forName("net.minecraft.SharedConstants", true, game).getMethod("tryDetectVersion").invoke(null);
            Class.forName("net.minecraft.server.Bootstrap", true, game).getMethod("bootStrap").invoke(null);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    /** Test classes renamed onto host names, without the nesting attributes that tie them to this class. */
    private static Map<String, byte[]> renamed(Map<Class<?>, String> classes) throws Exception {
        Map<String, String> names = new HashMap<>();
        classes.forEach((type, name) -> names.put(type.getName().replace('.', '/'), name));
        Map<String, byte[]> out = new HashMap<>();
        for (Map.Entry<Class<?>, String> entry : classes.entrySet()) {
            String resource = entry.getKey().getName().replace('.', '/') + ".class";
            byte[] bytes;
            try (InputStream in = LegacyTicketTypesHostTest.class.getClassLoader().getResourceAsStream(resource)) {
                bytes = in.readAllBytes();
            }
            ClassWriter writer = new ClassWriter(0);
            ClassVisitor flat = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public void visitInnerClass(String name, String outerName, String innerName, int access) {}
                @Override public void visitNestHost(String nestHost) {}
                @Override public void visitOuterClass(String owner, String name, String descriptor) {}
            };
            new ClassReader(bytes).accept(new ClassRemapper(flat, new SimpleRemapper(names)), 0);
            out.put(entry.getValue(), writer.toByteArray());
        }
        return out;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /** TicketType as 1.21.5 to 1.21.8 declare it. */
    public static final class Shape1215 {
        public final long timeout;
        public final boolean persist;
        public final Use1215 use;

        public Shape1215(long timeout, boolean persist, Use1215 use) {
            this.timeout = timeout;
            this.persist = persist;
            this.use = use;
        }
    }

    /** TicketType.TicketUse as 1.21.5 to 1.21.8 declare it. */
    public enum Use1215 { LOADING, SIMULATION, LOADING_AND_SIMULATION }

    /** TicketType as 1.21.9 and newer declare it. */
    public static final class Shape1219 {
        public final long timeout;
        public final int flags;

        public Shape1219(long timeout, int flags) {
            this.timeout = timeout;
            this.flags = flags;
        }
    }

    private static final class DefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        DefiningLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
