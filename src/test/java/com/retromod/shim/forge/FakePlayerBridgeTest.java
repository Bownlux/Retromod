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
import com.retromod.shim.common.RebuiltApiClasses;
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 removed FakePlayer; old mods get Retromod's rebuilt one, and every mod's copy counts as fake to the others. */
class FakePlayerBridgeTest {

    private static final String FAKE_PLAYER = LegacyFakePlayerBridge.FAKE_PLAYER;
    private static final String FACTORY = LegacyFakePlayerBridge.FACTORY;
    private static final String REBUILT = LegacyFakePlayerBridge.REBUILT;
    private static final String REBUILT_FACTORY = LegacyFakePlayerBridge.REBUILT_FACTORY;
    private static final String CHECKS = LegacyFakePlayerBridge.CHECKS;
    private static final String CONNECTION = RebuiltApiClasses.ROOT + "forge/common/util/FakePlayerConnection";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String PROFILE = "com/mojang/authlib/GameProfile";
    private static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String OLD_LISTENER = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;L"
            + SERVER_PLAYER + ";)V";
    private static final String GET_DESC = "(L" + SERVER_LEVEL + ";L" + PROFILE + ";)L" + FAKE_PLAYER + ";";
    private static final UUID FORGE_UUID = UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77");

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
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Forge 26.2 and 26.3 get the rebuilt classes; Forge 1.21.11, NeoForge and Fabric keep their own")
    void bridgeFollowsTheHost() {
        for (String host : List.of("26.2", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = host;
            transformer.clearRedirectsForTesting();
            LegacyFakePlayerBridge.register(transformer);
            assertEquals(REBUILT, transformer.getClassRedirects().get(FAKE_PLAYER), host);
            assertEquals(REBUILT_FACTORY, transformer.getClassRedirects().get(FACTORY), host);
            for (String name : List.of(REBUILT, REBUILT_FACTORY, CHECKS, REBUILT_FACTORY + "$Key", CONNECTION)) {
                assertTrue(transformer.getSyntheticClasses().containsKey(name), name + " travels with the bridge on " + host);
            }
        }

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertNothingRegistered("Forge 1.21.11 still ships FakePlayer");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        assertNothingRegistered("NeoForge has its own FakePlayer");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        assertNothingRegistered("Fabric and Quilt are not Forge targets");
    }

    @Test
    @DisplayName("A plain instanceof check asks FakePlayers.isFake, while a check followed by a cast stays exact")
    void instanceofAsksTheHelper() throws Exception {
        LegacyFakePlayerBridge.register(transformer);
        ClassNode checks = node(transformer.transformClass(checksClass("mod/Checks"), "mod/Checks.class"));

        MethodNode plain = method(checks, "plain");
        assertEquals(List.of(CHECKS + ".isFake(Ljava/lang/Object;)Z"), calls(plain));
        assertEquals(List.of(), types(plain, INSTANCEOF), "no exact check is left");

        // instanceof FakePlayer p compiles to a check and a cast, and the cast would refuse another mod's copy.
        MethodNode pattern = method(checks, "pattern");
        assertEquals(List.of(REBUILT), types(pattern, INSTANCEOF));
        assertEquals(List.of(REBUILT), types(pattern, CHECKCAST));
        assertEquals(List.of(), calls(pattern));

        for (MethodNode method : checks.methods) new Analyzer<>(new BasicVerifier()).analyze(checks.name, method);
    }

    @Test
    @DisplayName("FakePlayerFactory keeps one player per level and profile, with Forge's old id for its own")
    void factoryKeepsOnePlayerPerLevelAndProfile() throws Exception {
        LegacyFakePlayerBridge.register(transformer);
        ClassLoader game = host().loader(dir, transformer, Map.of());
        Class<?> factory = game.loadClass(dotted(REBUILT_FACTORY));
        Object level = newLevel(game);
        Object profile = profile(game, new UUID(1, 2), "Deployer");

        Object first = get(factory, level, profile);
        assertSame(first, get(factory, level, profile(game, new UUID(1, 2), "Deployer")), "an equal profile reuses it");
        assertNotSame(first, get(factory, newLevel(game), profile), "another level gets its own");
        assertNotSame(first, get(factory, level, profile(game, new UUID(3, 4), "Harvester")));

        Object minecraft = factory.getMethod("getMinecraft", game.loadClass(dotted(SERVER_LEVEL))).invoke(null, level);
        Object minecraftProfile = call(minecraft, "getGameProfile");
        assertEquals(FORGE_UUID, call(minecraftProfile, "id"), "saved owner data keeps matching");
        assertEquals("[Minecraft]", call(minecraftProfile, "name"));

        // Nothing reaches a client, and nothing harms the player or is harmed by it.
        Object connection = first.getClass().getField("connection").get(first);
        assertEquals(dotted(CONNECTION), connection.getClass().getName());
        assertSame(profile, call(call(connection, "cookie"), "gameProfile"));
        connection.getClass().getMethod("send", game.loadClass("net.minecraft.network.protocol.Packet")).invoke(connection,
                (Object) null);
        assertEquals(List.of(), connection.getClass().getField("sent").get(connection), "send is dropped");
        assertEquals(true, call(first, "isInvulnerableTo", level, null));
        assertEquals(false, call(first, "canHarmPlayer", (Object) null));
    }

    @Test
    @DisplayName("A level unload drops that level's players, a server stop drops them all, and each listens once")
    void cleanupFollowsTheGame() throws Exception {
        LegacyFakePlayerBridge.register(transformer);
        ClassLoader game = host().loader(dir, transformer, Map.of());
        Class<?> factory = game.loadClass(dotted(REBUILT_FACTORY));
        Object nether = newLevel(game);
        Object end = newLevel(game);
        Object profile = profile(game, new UUID(5, 6), "Miner");
        Object inNether = get(factory, nether, profile);
        Object inEnd = get(factory, end, profile);

        Object unloads = bus(game, "net.minecraftforge.event.level.LevelEvent$Unload");
        Object stops = bus(game, "net.minecraftforge.event.server.ServerStoppedEvent");
        assertEquals(1, listeners(unloads), "every get after the first reuses the same listener");
        assertEquals(1, listeners(stops));

        Class<?> levelAccessor = game.loadClass("net.minecraft.world.level.LevelAccessor");
        post(unloads, game.loadClass("net.minecraftforge.event.level.LevelEvent$Unload")
                .getConstructor(levelAccessor).newInstance(nether));
        assertNotSame(inNether, get(factory, nether, profile), "the unloaded level's player is gone");
        assertSame(inEnd, get(factory, end, profile), "another level keeps its player");

        post(stops, game.loadClass("net.minecraftforge.event.server.ServerStoppedEvent").getConstructor().newInstance());
        assertNotSame(inEnd, get(factory, end, profile), "a stopped server keeps no player");
        assertEquals(1, listeners(unloads));
    }

    @Test
    @DisplayName("Two mods translated apart each embed a copy, and each copy is fake to the other mod's checks")
    void twoJarsSeeEachOthersFakePlayers() throws Exception {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        Map<String, byte[]> classes = new HashMap<>();
        classes.putAll(translate("moda", Map.of("moda/Fakes.class", fakesClass("moda/Fakes"),
                "moda/DeployerPlayer.class", deployerClass("moda/DeployerPlayer"),
                "moda/NetHandler.class", netHandlerClass("moda/NetHandler"))));
        classes.putAll(translate("modb", Map.of("modb/Fakes.class", fakesClass("modb/Fakes"))));
        ClassLoader game = host().loader(dir, transformer, classes);
        Class<?> a = game.loadClass("moda.Fakes");
        Class<?> b = game.loadClass("modb.Fakes");
        Object level = newLevel(game);
        Object profile = profile(game, new UUID(7, 8), "Conductor");

        Object fromA = invoke(a, "make", level, profile);
        Object fromB = invoke(b, "make", level, profile);
        Object deployer = invoke(a, "makeOwn", level, profile);
        assertTrue(fromA.getClass().getName().startsWith(dotted(SyntheticEmbedder.PREFIX)), fromA.getClass().getName());
        assertNotEquals(fromA.getClass(), fromB.getClass(), "each mod carries its own copy");

        for (Class<?> checker : List.of(a, b)) {
            for (Object fake : List.of(fromA, fromB, deployer)) {
                assertEquals(true, invoke(checker, "isFake", fake), checker.getName() + " on " + fake.getClass().getName());
            }
            Object real = game.loadClass(dotted(SERVER_PLAYER)).getConstructors()[0].newInstance(
                    call(level, "getServer"), level, profile, game.loadClass("net.minecraft.server.level.ClientInformation")
                            .getMethod("createDefault").invoke(null));
            assertEquals(false, invoke(checker, "isFake", real), "a real player is not fake");
            assertEquals(false, invoke(checker, "isFake", (Object) null));
        }

        // A mod's own connection subclass still calls the constructor 1.20.2 removed.
        Object player = invoke(a, "makeOwn", level, profile(game, new UUID(9, 10), "Rider"));
        Object handler = invoke(a, "handler", call(level, "getServer"), player);
        assertSame(call(player, "getGameProfile"), call(call(handler, "cookie"), "gameProfile"),
                "the cookie comes from the player the old constructor was given");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths send instanceof checks to the embedded helper, outer and nested")
    void everyPathAsksTheHelper(String host) throws Exception {
        Path input = dir.resolve("fakes.jar");
        Files.write(input, jar(modWithNestedLibrary()));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("fakes.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        String helper = "embedded " + CHECKS + ".isFake";
        Map<String, String> expected = new TreeMap<>(Map.of("mod/Checks.plain", helper,
                "nested:fixturelib/LibChecks.plain", helper));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    // ---- registration ----

    private void assertNothingRegistered(String why) {
        transformer.clearRedirectsForTesting();
        LegacyFakePlayerBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FAKE_PLAYER), why);
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT), why);
        ClassNode checks = node(transformer.transformClass(checksClass("mod/Checks"), "mod/Checks.class"));
        assertEquals(List.of(FAKE_PLAYER), types(method(checks, "plain"), INSTANCEOF), why);
    }

    /** What RetromodForge registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, String host) throws Exception {
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

    /** One Forge 1.20.1 mod jar translated for 26.2, as its class entries. */
    private Map<String, byte[]> translate(String modId, Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", toml(modId));
        entries.putAll(classes);
        Path input = dir.resolve(modId + ".jar");
        Files.write(input, jar(entries));
        Path out = new ForgeModTransformer("26.2").transformMod(input, Files.createDirectories(dir.resolve("out-" + modId)));
        assertNotNull(out, modId + " translated");
        Map<String, byte[]> translated = new HashMap<>();
        try (JarFile file = new JarFile(out.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                String name = entry.getName();
                translated.put(name.substring(0, name.length() - ".class".length()),
                        file.getInputStream(entry).readAllBytes());
            }
        }
        return translated;
    }

    // ---- what each path wrote ----

    /** Each plain check's call, which must reach a FakePlayers copy embedded in the same jar. */
    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/Checks", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibChecks", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String name, String prefix, Map<String, String> found) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = node(jar.getInputStream(entry).readAllBytes());
        for (AbstractInsnNode insn : method(node, "plain").instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
            String owner = call.owner.substring(Math.max(0, call.owner.indexOf(RebuiltApiClasses.ROOT)));
            found.put(prefix + name + ".plain", (embedded ? "embedded " : "") + owner + "." + call.name);
        }
    }

    private static Path onlyJar(Path folder) throws Exception {
        try (var jars = Files.list(folder)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    // ---- old mod classes ----

    /** A mod with a plain check, and a nested library jar with its own. */
    private static Map<String, byte[]> modWithNestedLibrary() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib"));
        lib.put("fixturelib/LibChecks.class", checksClass("fixturelib/LibChecks"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("fakemod"));
        mod.put("mod/Checks.class", checksClass("mod/Checks"));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return mod;
    }

    /** {@code plain(o)} is {@code o instanceof FakePlayer}; {@code pattern(o)} is {@code o instanceof FakePlayer p ? p : null}. */
    static byte[] checksClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor plain = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "plain", "(Ljava/lang/Object;)Z", null, null);
        plain.visitCode();
        plain.visitVarInsn(ALOAD, 0);
        plain.visitTypeInsn(INSTANCEOF, FAKE_PLAYER);
        plain.visitInsn(IRETURN);
        plain.visitMaxs(0, 0);
        plain.visitEnd();

        MethodVisitor pattern = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "pattern", "(Ljava/lang/Object;)Ljava/lang/Object;",
                null, null);
        pattern.visitCode();
        Label notFake = new Label();
        pattern.visitVarInsn(ALOAD, 0);
        pattern.visitTypeInsn(INSTANCEOF, FAKE_PLAYER);
        pattern.visitJumpInsn(IFEQ, notFake);
        pattern.visitVarInsn(ALOAD, 0);
        pattern.visitTypeInsn(CHECKCAST, FAKE_PLAYER);
        pattern.visitInsn(ARETURN);
        pattern.visitLabel(notFake);
        pattern.visitFrame(F_SAME, 0, null, 0, null);
        pattern.visitInsn(ACONST_NULL);
        pattern.visitInsn(ARETURN);
        pattern.visitMaxs(0, 0);
        pattern.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code make(level, profile)} asks the factory, {@code makeOwn} builds the mod's own subclass,
     * {@code isFake(o)} is {@code o instanceof FakePlayer}, and {@code handler} builds the mod's connection.
     */
    private static byte[] fakesClass(String name) {
        String pkg = name.substring(0, name.lastIndexOf('/') + 1);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        String levelAndProfile = "(L" + SERVER_LEVEL + ";L" + PROFILE + ";)Ljava/lang/Object;";
        MethodVisitor make = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", levelAndProfile, null, null);
        make.visitCode();
        make.visitVarInsn(ALOAD, 0);
        make.visitVarInsn(ALOAD, 1);
        make.visitMethodInsn(INVOKESTATIC, FACTORY, "get", GET_DESC, false);
        make.visitInsn(ARETURN);
        make.visitMaxs(0, 0);
        make.visitEnd();

        MethodVisitor makeOwn = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "makeOwn", levelAndProfile, null, null);
        makeOwn.visitCode();
        makeOwn.visitTypeInsn(NEW, pkg + "DeployerPlayer");
        makeOwn.visitInsn(DUP);
        makeOwn.visitVarInsn(ALOAD, 0);
        makeOwn.visitVarInsn(ALOAD, 1);
        makeOwn.visitMethodInsn(INVOKESPECIAL, pkg + "DeployerPlayer", "<init>",
                "(L" + SERVER_LEVEL + ";L" + PROFILE + ";)V", false);
        makeOwn.visitInsn(ARETURN);
        makeOwn.visitMaxs(0, 0);
        makeOwn.visitEnd();

        MethodVisitor isFake = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "isFake", "(Ljava/lang/Object;)Z", null, null);
        isFake.visitCode();
        isFake.visitVarInsn(ALOAD, 0);
        isFake.visitTypeInsn(INSTANCEOF, FAKE_PLAYER);
        isFake.visitInsn(IRETURN);
        isFake.visitMaxs(0, 0);
        isFake.visitEnd();

        // handler(server, player) is new NetHandler(server, new Connection(CLIENTBOUND), (ServerPlayer) player).
        MethodVisitor handler = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "handler",
                "(Lnet/minecraft/server/MinecraftServer;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        handler.visitCode();
        handler.visitTypeInsn(NEW, pkg + "NetHandler");
        handler.visitInsn(DUP);
        handler.visitVarInsn(ALOAD, 0);
        handler.visitTypeInsn(NEW, "net/minecraft/network/Connection");
        handler.visitInsn(DUP);
        handler.visitFieldInsn(GETSTATIC, "net/minecraft/network/protocol/PacketFlow", "CLIENTBOUND",
                "Lnet/minecraft/network/protocol/PacketFlow;");
        handler.visitMethodInsn(INVOKESPECIAL, "net/minecraft/network/Connection", "<init>",
                "(Lnet/minecraft/network/protocol/PacketFlow;)V", false);
        handler.visitVarInsn(ALOAD, 1);
        handler.visitTypeInsn(CHECKCAST, SERVER_PLAYER);
        handler.visitMethodInsn(INVOKESPECIAL, pkg + "NetHandler", "<init>", OLD_LISTENER, false);
        handler.visitInsn(ARETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod's own fake player, as Create's deployer and Sophisticated Core's are. */
    private static byte[] deployerClass(String name) {
        String ctor = "(L" + SERVER_LEVEL + ";L" + PROFILE + ";)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, FAKE_PLAYER, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", ctor, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, FAKE_PLAYER, "<init>", ctor, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod's own connection with the 1.20.1 constructor, as Collective's and Create's are. */
    private static byte[] netHandlerClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, LISTENER, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", OLD_LISTENER, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitVarInsn(ALOAD, 3);
        init.visitMethodInsn(INVOKESPECIAL, LISTENER, "<init>", OLD_LISTENER, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- host stand-ins ----

    /** Forge 26.2's player, connection and cleanup events, with a bus that records its listeners. */
    private static ForgeHostStandIns host() {
        return new ForgeHostStandIns()
                .add("com/mojang/authlib/GameProfile.java",
                        "package com.mojang.authlib; public record GameProfile(java.util.UUID id, String name) {}")
                .add("net/minecraft/server/MinecraftServer.java", "package net.minecraft.server; public class MinecraftServer {}")
                .add("net/minecraft/world/level/LevelAccessor.java",
                        "package net.minecraft.world.level; public interface LevelAccessor {}")
                .add("net/minecraft/world/level/Level.java",
                        "package net.minecraft.world.level; public class Level implements LevelAccessor {}")
                .add("net/minecraft/server/level/ServerLevel.java", """
                        package net.minecraft.server.level;
                        public class ServerLevel extends net.minecraft.world.level.Level {
                            private final net.minecraft.server.MinecraftServer server = new net.minecraft.server.MinecraftServer();
                            public net.minecraft.server.MinecraftServer getServer() { return server; }
                        }""")
                .add("net/minecraft/server/level/ClientInformation.java", """
                        package net.minecraft.server.level;
                        public record ClientInformation() {
                            public static ClientInformation createDefault() { return new ClientInformation(); }
                        }""")
                .add("net/minecraft/world/entity/player/Player.java", """
                        package net.minecraft.world.entity.player;
                        public abstract class Player {
                            private final com.mojang.authlib.GameProfile profile;
                            protected Player(com.mojang.authlib.GameProfile profile) { this.profile = profile; }
                            public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
                        }""")
                .add("net/minecraft/server/level/ServerPlayer.java", """
                        package net.minecraft.server.level;
                        public class ServerPlayer extends net.minecraft.world.entity.player.Player {
                            public net.minecraft.server.network.ServerGamePacketListenerImpl connection;
                            private final ServerLevel level;
                            public ServerPlayer(net.minecraft.server.MinecraftServer server, ServerLevel level,
                                    com.mojang.authlib.GameProfile profile, ClientInformation information) {
                                super(profile);
                                this.level = level;
                            }
                            public ServerLevel level() { return level; }
                        }""")
                .add("net/minecraft/network/protocol/PacketFlow.java",
                        "package net.minecraft.network.protocol; public enum PacketFlow { SERVERBOUND, CLIENTBOUND }")
                .add("net/minecraft/network/protocol/Packet.java", "package net.minecraft.network.protocol; public interface Packet<T> {}")
                .add("net/minecraft/network/Connection.java", """
                        package net.minecraft.network;
                        public class Connection { public Connection(net.minecraft.network.protocol.PacketFlow flow) {} }""")
                .add("net/minecraft/network/DisconnectionDetails.java",
                        "package net.minecraft.network; public record DisconnectionDetails() {}")
                .add("net/minecraft/network/chat/Component.java", "package net.minecraft.network.chat; public interface Component {}")
                .add("io/netty/channel/ChannelFutureListener.java", "package io.netty.channel; public interface ChannelFutureListener {}")
                .add("net/minecraft/server/network/CommonListenerCookie.java", """
                        package net.minecraft.server.network;
                        public record CommonListenerCookie(com.mojang.authlib.GameProfile gameProfile, boolean transferred) {
                            public static CommonListenerCookie createInitial(com.mojang.authlib.GameProfile profile, boolean transferred) {
                                return new CommonListenerCookie(profile, transferred);
                            }
                        }""")
                .add("net/minecraft/server/network/ServerCommonPacketListenerImpl.java", """
                        package net.minecraft.server.network;
                        import net.minecraft.network.protocol.Packet;
                        public abstract class ServerCommonPacketListenerImpl {
                            public final java.util.List<Object> sent = new java.util.ArrayList<>();
                            private final CommonListenerCookie cookie;
                            public ServerCommonPacketListenerImpl(net.minecraft.server.MinecraftServer server,
                                    net.minecraft.network.Connection connection, CommonListenerCookie cookie) {
                                this.cookie = cookie;
                            }
                            public CommonListenerCookie cookie() { return cookie; }
                            public void send(Packet<?> packet) { sent.add(packet); }
                            public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) { sent.add(packet); }
                            public void disconnect(net.minecraft.network.chat.Component reason) { sent.add(reason); }
                            public void disconnect(net.minecraft.network.DisconnectionDetails details) { sent.add(details); }
                        }""")
                .add("net/minecraft/server/network/ServerGamePacketListenerImpl.java", """
                        package net.minecraft.server.network;
                        import net.minecraft.server.level.ServerPlayer;
                        public class ServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl {
                            public ServerGamePacketListenerImpl(net.minecraft.server.MinecraftServer server,
                                    net.minecraft.network.Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
                                super(server, connection, cookie);
                                player.connection = this;
                            }
                        }""")
                .add("net/minecraft/stats/Stat.java", "package net.minecraft.stats; public class Stat<T> {}")
                .add("net/minecraft/world/damagesource/DamageSource.java",
                        "package net.minecraft.world.damagesource; public class DamageSource {}")
                .add("net/minecraftforge/eventbus/api/listener/EventListener.java",
                        "package net.minecraftforge.eventbus.api.listener; public interface EventListener {}")
                .add("net/minecraftforge/eventbus/api/bus/EventBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        public interface EventBus<T> {
                            net.minecraftforge.eventbus.api.listener.EventListener addListener(java.util.function.Consumer<T> listener);
                        }""")
                .add("net/minecraftforge/eventbus/api/bus/RecordingBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        public final class RecordingBus<T> implements EventBus<T> {
                            public final java.util.List<java.util.function.Consumer<T>> listeners = new java.util.ArrayList<>();
                            public net.minecraftforge.eventbus.api.listener.EventListener addListener(java.util.function.Consumer<T> listener) {
                                listeners.add(listener);
                                return new net.minecraftforge.eventbus.api.listener.EventListener() {};
                            }
                            public void post(T event) { listeners.forEach(listener -> listener.accept(event)); }
                        }""")
                .add("net/minecraftforge/event/level/LevelEvent.java", """
                        package net.minecraftforge.event.level;
                        import net.minecraft.world.level.LevelAccessor;
                        import net.minecraftforge.eventbus.api.bus.EventBus;
                        import net.minecraftforge.eventbus.api.bus.RecordingBus;
                        public interface LevelEvent {
                            LevelAccessor getLevel();
                            record Unload(LevelAccessor level) implements LevelEvent {
                                public static final EventBus<Unload> BUS = new RecordingBus<>();
                                public LevelAccessor getLevel() { return level; }
                            }
                        }""")
                .add("net/minecraftforge/event/server/ServerStoppedEvent.java", """
                        package net.minecraftforge.event.server;
                        import net.minecraftforge.eventbus.api.bus.EventBus;
                        import net.minecraftforge.eventbus.api.bus.RecordingBus;
                        public record ServerStoppedEvent() {
                            public static final EventBus<ServerStoppedEvent> BUS = new RecordingBus<>();
                        }""");
    }

    private static Object newLevel(ClassLoader game) throws Exception {
        return game.loadClass(dotted(SERVER_LEVEL)).getConstructor().newInstance();
    }

    private static Object profile(ClassLoader game, UUID id, String name) throws Exception {
        return game.loadClass(dotted(PROFILE)).getConstructor(UUID.class, String.class).newInstance(id, name);
    }

    private static Object get(Class<?> factory, Object level, Object profile) throws Exception {
        return factory.getMethod("get", level.getClass(), profile.getClass()).invoke(null, level, profile);
    }

    private static Object bus(ClassLoader game, String event) throws Exception {
        return game.loadClass(event).getField("BUS").get(null);
    }

    private static int listeners(Object bus) throws Exception {
        return ((List<?>) bus.getClass().getField("listeners").get(bus)).size();
    }

    private static void post(Object bus, Object event) throws Exception {
        bus.getClass().getMethod("post", Object.class).invoke(bus, event);
    }

    /** Calls the public method of that name and argument count, as the mod's own code would. */
    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new AssertionError(target.getClass().getName() + " has no " + name);
    }

    private static Object invoke(Class<?> owner, String name, Object... arguments) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(null, arguments);
            }
        }
        throw new AssertionError(owner.getName() + " has no " + name);
    }

    // ---- bytes ----

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static List<String> types(MethodNode method, int opcode) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) types.add(((TypeInsnNode) insn).desc);
        }
        return types;
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
