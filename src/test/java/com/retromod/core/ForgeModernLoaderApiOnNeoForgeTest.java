/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Forge 1.21.1 mod's loader calls on NeoForge 26.3: its display test, its FML paths and mod info,
 * and its ChannelBuilder networking, translated by every path.
 */
class ForgeModernLoaderApiOnNeoForgeTest {

    private static final String SHIM = "com/retromod/shim/forge/embedded/NetworkShim";
    private static final String DISPLAY_TEST = "com/retromod/legacyapi/forge/fml/LegacyDisplayTest";
    /** Forge classes NeoForge never had: none may stay in a translated class. */
    private static final List<String> FORGE_ONLY = List.of("net/minecraftforge/network/",
            "net/minecraftforge/event/network/", "net/minecraftforge/fml/IExtensionPoint",
            "net/minecraftforge/fml/loading/FMLPaths", "net/minecraftforge/fml/loading/FMLEnvironment",
            "net/minecraftforge/forgespi/language/IModInfo", "net/minecraftforge/fml/javafmlmod/FMLModContainer");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"runtime", "transform", "aot", "batch"})
    @DisplayName("each path leaves no Forge-only loader class and binds every shim call to a member it has")
    void everyPathBridgesTheLoaderApi(String path) throws Exception {
        Path output = translate(path, "neoforge", "26.3");

        try (JarFile jar = new JarFile(output.toFile())) {
            Map<String, ClassNode> mod = new LinkedHashMap<>();
            for (String name : List.of("mod/ModEntry", "mod/ModNetwork", "mod/Ping", "mod/ModPayloads")) {
                mod.put(name, read(jar, name));
            }
            for (ClassNode node : mod.values()) {
                for (String reference : references(node)) {
                    for (String forge : FORGE_ONLY) {
                        assertFalse(reference.startsWith(forge), path + ": " + node.name + " still names " + reference);
                    }
                }
            }
            // The display test is the copy embedded in the mod, and its members are the ones called.
            String embedded = embeddedName(jar, DISPLAY_TEST);
            assertNotNull(embedded, path + ": the rebuilt display test is embedded");
            ClassNode copy = read(jar, embedded);
            for (ClassNode node : mod.values()) assertCallsResolve(node, embedded, copy, path);
            // Every display test call, including the one through FMLJavaModLoadingContext, goes to the copy.
            List<String> displayTests = calls(mod.get("mod/ModEntry")).stream()
                    .filter(call -> call.contains(".registerDisplayTest(")).toList();
            assertEquals(2, displayTests.size(), path + ": " + displayTests);
            assertTrue(displayTests.stream().allMatch(call -> call.startsWith(embedded + ".")), path + ": " + displayTests);
            // Forge 1.21's typed getContainer() is on the embedded loading context, and the cast type is NeoForge's.
            String context = embeddedName(jar, "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext");
            assertNotNull(context, path + ": the loading context stand-in is embedded");
            assertCallsResolve(mod.get("mod/ModEntry"), context, read(jar, context), path);
            assertTrue(calls(mod.get("mod/ModEntry")).contains(
                    context + ".getContainer()Lnet/neoforged/fml/javafmlmod/FMLModContainer;"), path);
            // Shim calls name members the runtime's NetworkShim has.
            for (ClassNode node : mod.values()) assertShimCallsResolve(node, path);
            List<String> network = calls(mod.get("mod/ModNetwork"));
            assertTrue(network.contains(SHIM + "$MessageBuilder.contextConsumer(Ljava/util/function/BiConsumer;)L"
                    + SHIM + "$MessageBuilder;"), path + ": a handler takes the context itself: " + network);
            assertTrue(network.contains(SHIM + "$MessageBuilder.added()L" + SHIM + "$SimpleChannelWrapper;"), path);
            assertTrue(calls(mod.get("mod/ModPayloads")).contains(SHIM + "$PayloadChannel.addMain(Ljava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/util/function/BiConsumer;)L" + SHIM + "$PayloadChannel;"), path);
            assertTrue(calls(mod.get("mod/ModEntry")).contains(
                    "net/neoforged/fml/loading/FMLPaths.getOrCreateGameRelativePath(Ljava/nio/file/Path;)Ljava/nio/file/Path;")
                    || references(mod.get("mod/ModEntry")).contains("net/neoforged/fml/loading/FMLPaths"),
                    path + ": FML paths are NeoForge's");
        }
    }

    @Test
    @DisplayName("on a Forge 26.2 host the same mod keeps Forge's own display test and channel classes")
    void forgeHostKeepsForgeClasses() throws Exception {
        Path output = translate("runtime", "forge", "26.2");
        try (JarFile jar = new JarFile(output.toFile())) {
            assertTrue(references(read(jar, "mod/ModNetwork")).contains("net/minecraftforge/network/ChannelBuilder"));
            assertTrue(references(read(jar, "mod/ModEntry")).contains("net/minecraftforge/fml/IExtensionPoint$DisplayTest"));
            assertNull(embeddedName(jar, DISPLAY_TEST), "Forge has the class, so nothing is embedded");
        }
    }

    @Test
    @DisplayName("a Forge 1.20.1 message handler, which takes a supplier of the context, keeps the shim's supplier form")
    void oldMessageBuilderKeepsTheSupplierForm() throws Exception {
        Map<String, byte[]> classes = compile(OLD_API_AND_MOD);
        register("neoforge", "26.3");
        transformer.setModSourceVersion("1.20.1");
        byte[] out = transformer.transformClass(classes.get("old/OldNetwork"), "old/OldNetwork");
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        assertTrue(calls(node).contains(SHIM + "$MessageBuilder.consumerMainThread(Ljava/util/function/BiConsumer;)L"
                + SHIM + "$MessageBuilder;"), calls(node).toString());
    }

    // ---- translation ----

    private Path translate(String path, String hostLoader, String host) throws Exception {
        McReflect.setForceForge(hostLoader.equals("forge"));
        McReflect.setForceNeoForge(hostLoader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(hostLoader);
        Path input = Files.createDirectories(dir.resolve("in")).resolve("modern.jar");
        Files.write(input, modJar());
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = out.resolve("modern.jar");
        switch (path) {
            case "runtime" -> {
                register(hostLoader, host);
                output = new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> CliHarness.transform(input, output, host);
            case "aot" -> CliHarness.aot(input, output, host);
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    output = jars.filter(jar -> jar.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
        }
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the loader's entry point registers on {@code host}. */
    private void register(String hostLoader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> types = hostLoader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        // RetromodNeoForge also registers the stand-ins for loader classes NeoForge deleted.
        if (hostLoader.equals("neoforge")) com.retromod.shim.forge.ForgeNeoForgeSynthetics.register(transformer);
    }

    private byte[] modJar() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[52,)\"\nlicense=\"MIT\"\n[[mods]]\n"
                + "modId=\"modern\"\nversion=\"1.0.0\"\n[[dependencies.modern]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.21.1,1.21.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        compile(FORGE_121_API_AND_MOD).forEach((name, bytes) -> {
            if (name.startsWith("mod/")) entries.put(name + ".class", bytes);
        });
        return LoaderApiFixtures.jar(entries);
    }

    private Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path work = Files.createTempDirectory(dir, "src");
        List<String> args = new ArrayList<>(List.of("-d", work.resolve("out").toString(), "--release", "17", "-proc:none"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = work.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the fixture must compile");
        Map<String, byte[]> classes = new HashMap<>();
        Path out = work.resolve("out");
        try (var files = Files.walk(out)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    // ---- reading the output ----

    private static ClassNode read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static String embeddedName(JarFile jar, String original) {
        for (JarEntry entry : java.util.Collections.list(jar.entries())) {
            String name = entry.getName();
            if (name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith(original + ".class")) {
                return name.substring(0, name.length() - ".class".length());
            }
        }
        return null;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    /** Every internal name a class mentions: owners of its calls and fields, and the types in their descriptors. */
    private static Set<String> references(ClassNode node) {
        Set<String> names = new HashSet<>();
        for (MethodNode method : node.methods) {
            addTypes(names, method.desc);
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    names.add(call.owner);
                    addTypes(names, call.desc);
                } else if (insn instanceof FieldInsnNode field) {
                    names.add(field.owner);
                    addTypes(names, field.desc);
                } else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof Type type
                        && type.getSort() == Type.OBJECT) {
                    names.add(type.getInternalName());
                } else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type) {
                    names.add(type.desc);
                } else if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
                    addTypes(names, indy.desc);
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Type t && t.getSort() == Type.METHOD) addTypes(names, t.getDescriptor());
                        if (arg instanceof org.objectweb.asm.Handle h) {
                            names.add(h.getOwner());
                            addTypes(names, h.getDesc());
                        }
                    }
                }
            }
        }
        return names;
    }

    private static void addTypes(Set<String> names, String desc) {
        Type type = Type.getType(desc);
        List<Type> types = new ArrayList<>();
        if (type.getSort() == Type.METHOD) {
            types.addAll(List.of(type.getArgumentTypes()));
            types.add(type.getReturnType());
        } else {
            types.add(type);
        }
        for (Type t : types) {
            if (t.getSort() == Type.ARRAY) t = t.getElementType();
            if (t.getSort() == Type.OBJECT) names.add(t.getInternalName());
        }
    }

    private static void assertCallsResolve(ClassNode node, String owner, ClassNode copy, String path) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) {
                    assertTrue(copy.methods.stream().anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc)),
                            path + ": " + node.name + " calls " + call.name + call.desc + ", which the copy lacks");
                }
            }
        }
    }

    /** Each call into NetworkShim names a method its class, a superclass or an interface declares. */
    private static void assertShimCallsResolve(ClassNode node, String path) throws Exception {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.startsWith(SHIM)) {
                    assertTrue(declares(call.owner, call.name, call.desc),
                            path + ": " + node.name + " calls " + call.owner + "." + call.name + call.desc + ", which does not exist");
                    // Forge's payload builders were interfaces; a call kept as an interface call on a shim class fails to link.
                    boolean shimInterface = (shimClass(call.owner).access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
                    assertEquals(shimInterface, call.itf, path + ": " + call.owner + "." + call.name + " is called as the wrong kind");
                } else if (insn instanceof FieldInsnNode field && field.owner.startsWith(SHIM)) {
                    ClassNode owner = shimClass(field.owner);
                    assertTrue(owner.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)),
                            path + ": " + field.owner + "." + field.name + " does not exist");
                }
            }
        }
    }

    private static boolean declares(String owner, String name, String desc) throws Exception {
        for (String type = owner; type != null; ) {
            ClassNode node = shimClass(type);
            if (node == null) return false;
            if (node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) return true;
            for (String iface : node.interfaces) {
                if (iface.startsWith(SHIM) && declares(iface, name, desc)) return true;
            }
            type = node.superName;
        }
        return false;
    }

    private static ClassNode shimClass(String name) throws Exception {
        try (InputStream in = ForgeModernLoaderApiOnNeoForgeTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }

    // ---- fixture sources: the Forge 1.21.1 API the mod compiled against, and the mod ----

    private static final Map<String, String> FORGE_121_API_AND_MOD = Map.ofEntries(
            Map.entry("net/minecraft/resources/ResourceLocation.java",
                    "package net.minecraft.resources; public class ResourceLocation {"
                            + " public static ResourceLocation fromNamespaceAndPath(String a, String b) { return null; } }"),
            Map.entry("net/minecraft/server/level/ServerPlayer.java", "package net.minecraft.server.level; public class ServerPlayer {}"),
            Map.entry("net/minecraft/network/Connection.java", "package net.minecraft.network; public class Connection {}"),
            Map.entry("net/minecraft/network/FriendlyByteBuf.java", "package net.minecraft.network; public class FriendlyByteBuf {"
                    + " public int readInt() { return 0; } public FriendlyByteBuf writeInt(int v) { return this; } }"),
            Map.entry("net/minecraftforge/network/Channel.java", """
                    package net.minecraftforge.network;
                    public abstract class Channel<MSG> {
                        public void send(MSG message, PacketDistributor.PacketTarget target) {}
                        public void send(MSG message, net.minecraft.network.Connection connection) {}
                        public boolean isRemotePresent(net.minecraft.network.Connection connection) { return true; }
                        public interface VersionTest {
                            static VersionTest exact(int version) { return null; }
                            boolean accepts(Status status, int version);
                            enum Status { PRESENT, MISSING, VANILLA }
                        }
                    }
                    """),
            Map.entry("net/minecraftforge/network/ChannelBuilder.java", """
                    package net.minecraftforge.network;
                    public class ChannelBuilder {
                        public static ChannelBuilder named(net.minecraft.resources.ResourceLocation name) { return null; }
                        public ChannelBuilder networkProtocolVersion(int version) { return this; }
                        public ChannelBuilder clientAcceptedVersions(Channel.VersionTest test) { return this; }
                        public ChannelBuilder serverAcceptedVersions(Channel.VersionTest test) { return this; }
                        public ChannelBuilder optional() { return this; }
                        public SimpleChannel simpleChannel() { return null; }
                        public net.minecraftforge.network.payload.PayloadConnection<net.minecraft.network.protocol.common.custom.CustomPacketPayload> payloadChannel() { return null; }
                    }
                    """),
            Map.entry("net/minecraftforge/network/SimpleChannel.java", """
                    package net.minecraftforge.network;
                    import java.util.function.BiConsumer;
                    import java.util.function.Function;
                    import net.minecraft.network.FriendlyByteBuf;
                    import net.minecraftforge.event.network.CustomPayloadEvent;
                    public class SimpleChannel extends Channel<Object> {
                        public <M> MessageBuilder<M, FriendlyByteBuf> messageBuilder(Class<M> type) { return null; }
                        public <M> MessageBuilder<M, FriendlyByteBuf> messageBuilder(Class<M> type, int id) { return null; }
                        public <M, B extends FriendlyByteBuf> MessageBuilder<M, B> messageBuilder(Class<M> type, int id, NetworkDirection<B> direction) { return null; }
                        public SimpleChannel build() { return this; }
                        public static class MessageBuilder<MSG, BUF extends FriendlyByteBuf> {
                            public MessageBuilder<MSG, BUF> encoder(BiConsumer<MSG, BUF> encoder) { return this; }
                            public MessageBuilder<MSG, BUF> decoder(Function<BUF, MSG> decoder) { return this; }
                            public MessageBuilder<MSG, BUF> consumerMainThread(BiConsumer<MSG, CustomPayloadEvent.Context> handler) { return this; }
                            public MessageBuilder<MSG, BUF> consumerNetworkThread(BiConsumer<MSG, CustomPayloadEvent.Context> handler) { return this; }
                            public SimpleChannel add() { return null; }
                        }
                    }
                    """),
            Map.entry("net/minecraft/network/protocol/common/custom/CustomPacketPayload.java", """
                    package net.minecraft.network.protocol.common.custom;
                    public interface CustomPacketPayload {
                        Type<? extends CustomPacketPayload> type();
                        record Type<T extends CustomPacketPayload>(net.minecraft.resources.ResourceLocation id) {}
                    }
                    """),
            Map.entry("net/minecraft/network/codec/StreamCodec.java",
                    "package net.minecraft.network.codec; public interface StreamCodec<B, V> { V decode(B buffer); void encode(B buffer, V value); }"),
            Map.entry("net/minecraftforge/network/payload/PayloadConnection.java",
                    "package net.minecraftforge.network.payload; public interface PayloadConnection<B> { PayloadProtocol<B> play(); }"),
            Map.entry("net/minecraftforge/network/payload/PayloadProtocol.java",
                    "package net.minecraftforge.network.payload; public interface PayloadProtocol<B> { <F> F bidirectional(); }"),
            Map.entry("net/minecraftforge/network/payload/PayloadFlow.java", """
                    package net.minecraftforge.network.payload;
                    import java.util.function.BiConsumer;
                    import net.minecraft.network.codec.StreamCodec;
                    import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
                    import net.minecraftforge.event.network.CustomPayloadEvent;
                    public interface PayloadFlow<B> {
                        <P extends CustomPacketPayload> PayloadFlow<B> addMain(CustomPacketPayload.Type<P> type,
                                StreamCodec<?, P> codec, BiConsumer<P, CustomPayloadEvent.Context> handler);
                        net.minecraftforge.network.Channel<B> build();
                    }
                    """),
            Map.entry("mod/ModPayloads.java", """
                    package mod;
                    import net.minecraft.network.codec.StreamCodec;
                    import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
                    import net.minecraft.resources.ResourceLocation;
                    import net.minecraftforge.network.Channel;
                    import net.minecraftforge.network.ChannelBuilder;
                    import net.minecraftforge.network.PacketDistributor;
                    import net.minecraftforge.network.payload.PayloadFlow;

                    /** A Forge 1.21 payload channel, as GeckoLib 4 builds its own. */
                    public final class ModPayloads {
                        public record Hello(int value) implements CustomPacketPayload {
                            public static final Type<Hello> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("modern", "hello"));
                            public static final StreamCodec<Object, Hello> CODEC = null;
                            public Type<Hello> type() { return TYPE; }
                        }

                        public static final Channel<CustomPacketPayload> CHANNEL = ((PayloadFlow<CustomPacketPayload>) ChannelBuilder
                                .named(ResourceLocation.fromNamespaceAndPath("modern", "payloads")).payloadChannel().play().bidirectional())
                                .addMain(Hello.TYPE, Hello.CODEC, (hello, context) -> context.setPacketHandled(true))
                                .build();

                        public static void hello() {
                            CHANNEL.send(new Hello(1), PacketDistributor.SERVER.noArg());
                        }
                    }
                    """),
            Map.entry("net/minecraftforge/network/NetworkDirection.java", """
                    package net.minecraftforge.network;
                    public final class NetworkDirection<B extends net.minecraft.network.FriendlyByteBuf> {
                        public static final NetworkDirection<net.minecraft.network.FriendlyByteBuf> PLAY_TO_SERVER = null;
                        public static final NetworkDirection<net.minecraft.network.FriendlyByteBuf> PLAY_TO_CLIENT = null;
                    }
                    """),
            Map.entry("net/minecraftforge/network/PacketDistributor.java", """
                    package net.minecraftforge.network;
                    public final class PacketDistributor<T> {
                        public static final PacketDistributor<net.minecraft.server.level.ServerPlayer> PLAYER = null;
                        public static final PacketDistributor<Void> SERVER = null;
                        public PacketTarget with(T argument) { return null; }
                        public PacketTarget noArg() { return null; }
                        public static final class PacketTarget {}
                    }
                    """),
            Map.entry("net/minecraftforge/event/network/CustomPayloadEvent.java", """
                    package net.minecraftforge.event.network;
                    public class CustomPayloadEvent {
                        public static class Context {
                            public net.minecraft.server.level.ServerPlayer getSender() { return null; }
                            public void setPacketHandled(boolean handled) {}
                            public java.util.concurrent.CompletableFuture<Void> enqueueWork(Runnable work) { return null; }
                            public boolean isClientSide() { return false; }
                            public net.minecraft.network.Connection getConnection() { return null; }
                        }
                    }
                    """),
            Map.entry("net/minecraftforge/fml/IExtensionPoint.java", """
                    package net.minecraftforge.fml;
                    import java.util.function.BiPredicate;
                    import java.util.function.Supplier;
                    public interface IExtensionPoint<T extends Record> {
                        record DisplayTest(Supplier<String> suppliedVersion, BiPredicate<String, Boolean> remoteVersionTest)
                                implements IExtensionPoint<DisplayTest> {
                            public static final String IGNORESERVERONLY = "OHNOES";
                            public static final Supplier<DisplayTest> IGNORE_ALL_VERSION = null;
                        }
                    }
                    """),
            Map.entry("net/minecraftforge/fml/ModLoadingContext.java", """
                    package net.minecraftforge.fml;
                    import java.util.function.Supplier;
                    public class ModLoadingContext {
                        public static ModLoadingContext get() { return null; }
                        public <T extends Record & IExtensionPoint<T>> void registerExtensionPoint(
                                Class<? extends IExtensionPoint<T>> point, Supplier<T> extension) {}
                        public void registerDisplayTest(Supplier<IExtensionPoint.DisplayTest> test) {}
                        public ModContainer getActiveContainer() { return null; }
                    }
                    """),
            Map.entry("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.java", """
                    package net.minecraftforge.fml.javafmlmod;
                    public class FMLJavaModLoadingContext extends net.minecraftforge.fml.ModLoadingContext {
                        public static FMLJavaModLoadingContext get() { return null; }
                        public FMLModContainer getContainer() { return null; }
                    }
                    """),
            Map.entry("net/minecraftforge/fml/javafmlmod/FMLModContainer.java", """
                    package net.minecraftforge.fml.javafmlmod;
                    public class FMLModContainer extends net.minecraftforge.fml.ModContainer {
                        public net.minecraftforge.eventbus.api.IEventBus getEventBus() { return null; }
                    }
                    """),
            Map.entry("net/minecraftforge/eventbus/api/IEventBus.java",
                    "package net.minecraftforge.eventbus.api; public interface IEventBus {}"),
            Map.entry("net/minecraftforge/fml/ModContainer.java", """
                    package net.minecraftforge.fml;
                    public abstract class ModContainer {
                        public net.minecraftforge.forgespi.language.IModInfo getModInfo() { return null; }
                    }
                    """),
            Map.entry("net/minecraftforge/forgespi/language/IModInfo.java",
                    "package net.minecraftforge.forgespi.language; public interface IModInfo { String getModId(); }"),
            Map.entry("net/minecraftforge/fml/loading/FMLPaths.java", """
                    package net.minecraftforge.fml.loading;
                    public enum FMLPaths {
                        CONFIGDIR;
                        public java.nio.file.Path get() { return null; }
                        public static java.nio.file.Path getOrCreateGameRelativePath(java.nio.file.Path path) { return path; }
                    }
                    """),
            Map.entry("net/minecraftforge/fml/loading/FMLEnvironment.java",
                    "package net.minecraftforge.fml.loading; public class FMLEnvironment { public static final boolean production = Boolean.getBoolean(\"production\"); }"),
            Map.entry("mod/Ping.java", """
                    package mod;
                    import net.minecraft.network.FriendlyByteBuf;
                    public record Ping(int value) {
                        public void write(FriendlyByteBuf buffer) { buffer.writeInt(value); }
                        public static Ping read(FriendlyByteBuf buffer) { return new Ping(buffer.readInt()); }
                    }
                    """),
            Map.entry("mod/ModNetwork.java", """
                    package mod;
                    import java.util.ArrayList;
                    import java.util.List;
                    import net.minecraft.resources.ResourceLocation;
                    import net.minecraft.server.level.ServerPlayer;
                    import net.minecraftforge.event.network.CustomPayloadEvent;
                    import net.minecraftforge.network.Channel;
                    import net.minecraftforge.network.ChannelBuilder;
                    import net.minecraftforge.network.NetworkDirection;
                    import net.minecraftforge.network.PacketDistributor;
                    import net.minecraftforge.network.SimpleChannel;

                    /** A Forge 1.21.1 channel, as most mods in the survey build theirs. */
                    public final class ModNetwork {
                        public static final List<Object> LOG = new ArrayList<>();
                        public static final SimpleChannel CHANNEL = ChannelBuilder
                                .named(ResourceLocation.fromNamespaceAndPath("modern", "main"))
                                .networkProtocolVersion(1)
                                .clientAcceptedVersions(Channel.VersionTest.exact(1))
                                .serverAcceptedVersions((status, version) -> true)
                                .optional()
                                .simpleChannel();

                        public static void register() {
                            CHANNEL.messageBuilder(Ping.class, 0, NetworkDirection.PLAY_TO_SERVER)
                                    .encoder(Ping::write).decoder(Ping::read)
                                    .consumerMainThread(ModNetwork::handle).add();
                            CHANNEL.messageBuilder(Ping.class)
                                    .encoder(Ping::write).decoder(Ping::read)
                                    .consumerNetworkThread((ping, context) -> LOG.add(context.isClientSide())).add();
                        }

                        static void handle(Ping ping, CustomPayloadEvent.Context context) {
                            context.enqueueWork(() -> LOG.add(context.getSender()));
                            context.setPacketHandled(true);
                        }

                        public static void sendTo(ServerPlayer player) {
                            CHANNEL.send(new Ping(1), PacketDistributor.PLAYER.with(player));
                            CHANNEL.send(new Ping(2), PacketDistributor.SERVER.noArg());
                            if (CHANNEL.isRemotePresent(null)) CHANNEL.send(new Ping(3), (net.minecraft.network.Connection) null);
                        }
                    }
                    """),
            Map.entry("mod/ModEntry.java", """
                    package mod;
                    import java.nio.file.Path;
                    import net.minecraftforge.fml.IExtensionPoint;
                    import net.minecraftforge.fml.ModLoadingContext;
                    import net.minecraftforge.fml.loading.FMLEnvironment;
                    import net.minecraftforge.fml.loading.FMLPaths;

                    /** A Forge 1.21.1 mod's construction: a display test, its config folder, its mod info. */
                    public final class ModEntry {
                        public static final java.util.List<Object> LOG = new java.util.ArrayList<>();
                        public static Object construct(ModLoadingContext context) {
                            context.registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                                    () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (a, b) -> true));
                            context.registerDisplayTest(IExtensionPoint.DisplayTest.IGNORE_ALL_VERSION);
                            net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get()
                                    .registerDisplayTest(IExtensionPoint.DisplayTest.IGNORE_ALL_VERSION);
                            LOG.add(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getContainer().getEventBus());
                            Path config = FMLPaths.getOrCreateGameRelativePath(FMLPaths.CONFIGDIR.get().resolve("modern"));
                            String id = context.getActiveContainer().getModInfo().getModId();
                            return FMLEnvironment.production ? config : id;
                        }
                    }
                    """));

    // A Forge 1.20.1 channel, whose message builder's handlers take a supplier of the context.
    private static final Map<String, String> OLD_API_AND_MOD = Map.of(
            "net/minecraftforge/network/simple/SimpleChannel.java", """
                    package net.minecraftforge.network.simple;
                    import java.util.function.BiConsumer;
                    import java.util.function.Supplier;
                    public class SimpleChannel {
                        public <M> MessageBuilder<M> messageBuilder(Class<M> type, int id) { return null; }
                        public static class MessageBuilder<MSG> {
                            public MessageBuilder<MSG> consumerMainThread(BiConsumer<MSG, Supplier<Object>> handler) { return this; }
                            public void add() {}
                        }
                    }
                    """,
            "old/OldNetwork.java", """
                    package old;
                    import net.minecraftforge.network.simple.SimpleChannel;
                    public final class OldNetwork {
                        public static void register(SimpleChannel channel) {
                            channel.messageBuilder(String.class, 0).consumerMainThread((message, context) -> context.get()).add();
                        }
                    }
                    """);
}
