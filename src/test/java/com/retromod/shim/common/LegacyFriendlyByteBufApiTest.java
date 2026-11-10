/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.3 removed {@code FriendlyByteBuf.writeCollection}, {@code readMap}, {@code writeBlockHitResult} and
 * the other helpers below. Cobblemon, Quark, Puzzles Lib, and Jade 1.21.1 still call them, so their
 * packets failed with {@code NoSuchMethodError} on 26.3.
 */
class LegacyFriendlyByteBufApiTest {

    private static final String PACKETS = "test.mod.Packets";

    private RetromodTransformer transformer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old mod's packet writes and reads back on a 26.3 buffer")
    void oldPacketRoundTripsOn263() throws Exception {
        LegacyFriendlyByteBufApi.registerMissing(transformer, member -> true);

        assertEquals("[a, b] {x=3, y=4} [7, 9] 1234 42 DecoderException", roundTrip(true));
    }

    @Test
    @DisplayName("without the bridge the same packet fails on 26.3")
    void withoutTheBridgeThePacketFails() {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> roundTrip(false));
        assertInstanceOf(NoSuchMethodError.class, failure.getCause());
    }

    @Test
    @DisplayName("a 1.20.1 call with the old Writer and Reader views reaches the rebuilt helper")
    void preStreamCodecCallsReachTheHelper() {
        // 1.20.5 swapped FriendlyByteBuf.Writer and Reader for StreamEncoder and StreamDecoder.
        for (String[] rename : Legacy1205MemberBridge.CLASS_RENAMES) transformer.registerClassRedirect(rename[0], rename[1]);
        LegacyFriendlyByteBufApi.registerMissing(transformer, member -> true);

        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/OldPacket", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write",
                "(L" + LegacyFriendlyByteBufApi.BUF + ";Ljava/util/Collection;Lnet/minecraft/network/FriendlyByteBuf$Writer;)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyFriendlyByteBufApi.BUF, "writeCollection",
                "(Ljava/util/Collection;Lnet/minecraft/network/FriendlyByteBuf$Writer;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(writer.toByteArray(), "test/mod/OldPacket")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
            }
        }
        assertEquals(List.of(Opcodes.INVOKESTATIC + " " + LegacyFriendlyByteBufApi.REBUILT + ".writeCollection(L"
                + LegacyFriendlyByteBufApi.BUF + ";Ljava/util/Collection;Lnet/minecraft/network/codec/StreamEncoder;)V"), calls);
    }

    @Test
    @DisplayName("a host that still has the members keeps the mod's calls")
    void hostWithTheMembersGetsNoRedirects() {
        ClassNode buf262 = buffer(LegacyFriendlyByteBufApi.INSTANCE_MEMBERS);
        buf262.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                LegacyFriendlyByteBufApi.LIMIT_VALUE.name(), LegacyFriendlyByteBufApi.LIMIT_VALUE.desc(), null, null));

        int synthetics = transformer.getSyntheticClasses().size();
        int redirects = transformer.getMethodRedirects().size();

        LegacyFriendlyByteBufApi.registerMissing(transformer,
                member -> !LegacyFriendlyByteBufApi.hasMethod(buf262, member));

        assertEquals(synthetics, transformer.getSyntheticClasses().size(), "26.2 still has every member");
        assertEquals(redirects, transformer.getMethodRedirects().size(), "26.2 still has every member");
    }

    @Test
    @DisplayName("NeoForge's own readMap and writeMap do not count as the vanilla forms")
    void neoForgeExtensionShapesAreNotTheVanillaMembers() {
        // NeoForge 26.3 adds readMap(StreamDecoder, BiFunction) and writeMap(Map, StreamEncoder, TriConsumer).
        ClassNode neo263 = buffer(List.of(
                new LegacyFriendlyByteBufApi.Member("readMap",
                        "(Lnet/minecraft/network/codec/StreamDecoder;Ljava/util/function/BiFunction;)Ljava/util/Map;"),
                new LegacyFriendlyByteBufApi.Member("writeMap",
                        "(Ljava/util/Map;Lnet/minecraft/network/codec/StreamEncoder;"
                                + "Lorg/apache/commons/lang3/function/TriConsumer;)V")));

        for (LegacyFriendlyByteBufApi.Member member : LegacyFriendlyByteBufApi.INSTANCE_MEMBERS) {
            assertFalse(LegacyFriendlyByteBufApi.hasMethod(neo263, member), member + " is still missing on NeoForge 26.3");
        }
    }

    private static ClassNode buffer(List<LegacyFriendlyByteBufApi.Member> members) {
        ClassNode node = new ClassNode();
        node.name = LegacyFriendlyByteBufApi.BUF;
        for (LegacyFriendlyByteBufApi.Member member : members) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, member.name(), member.desc(), null, null));
        }
        return node;
    }

    /** Compiles the mod against 26.2, translates it if asked, and runs its packet on 26.3. */
    private String roundTrip(boolean translate) throws Exception {
        Map<String, byte[]> oldHostAndMod = compile("old", OLD_HOST, MOD);
        byte[] packets = oldHostAndMod.get(PACKETS);
        if (translate) packets = transformer.transformClass(packets, PACKETS.replace('.', '/'));

        Map<String, byte[]> classes = new HashMap<>(compile("new", NEW_HOST, Map.of()));
        classes.put(PACKETS, packets);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        ClassLoader host = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> registryBuf = host.loadClass("net.minecraft.network.RegistryFriendlyByteBuf");
        Class<?> hitResult = host.loadClass("net.minecraft.world.phys.BlockHitResult");
        Object buf = registryBuf.getConstructor().newInstance();
        Object hit = hitResult.getConstructor(int.class).newInstance(42);
        return (String) host.loadClass(PACKETS).getMethod("roundTrip", registryBuf, hitResult).invoke(null, buf, hit);
    }

    // Shared by both hosts: the fastutil list and the codec interfaces did not change.
    private static final Map<String, String> SHARED = Map.of(
            "it/unimi/dsi/fastutil/ints/IntList.java", """
                    package it.unimi.dsi.fastutil.ints;
                    public interface IntList extends java.util.List<Integer> {
                        boolean add(int value);
                        int getInt(int index);
                    }""",
            "it/unimi/dsi/fastutil/ints/IntArrayList.java", """
                    package it.unimi.dsi.fastutil.ints;
                    public class IntArrayList extends java.util.AbstractList<Integer> implements IntList {
                        private final java.util.List<Integer> values = new java.util.ArrayList<>();
                        public IntArrayList() {}
                        public boolean add(int value) { return values.add(value); }
                        public int getInt(int index) { return values.get(index); }
                        public Integer get(int index) { return values.get(index); }
                        public int size() { return values.size(); }
                    }""",
            "net/minecraft/network/codec/StreamEncoder.java",
            "package net.minecraft.network.codec; public interface StreamEncoder<O, T> { void encode(O out, T value); }",
            "net/minecraft/network/codec/StreamDecoder.java",
            "package net.minecraft.network.codec; public interface StreamDecoder<I, T> { T decode(I in); }",
            "net/minecraft/network/RegistryFriendlyByteBuf.java", """
                    package net.minecraft.network;
                    public class RegistryFriendlyByteBuf extends FriendlyByteBuf { public RegistryFriendlyByteBuf() {} }""");

    /** The 26.2 buffer, checked against the 26.2 client jar with javap. Only the mod is compiled against it. */
    private static final Map<String, String> OLD_HOST = with(SHARED, Map.of(
            "net/minecraft/world/phys/BlockHitResult.java", """
                    package net.minecraft.world.phys;
                    public class BlockHitResult { public final int pos; public BlockHitResult(int pos) { this.pos = pos; } }""",
            "net/minecraft/network/FriendlyByteBuf.java", """
                    package net.minecraft.network;
                    import it.unimi.dsi.fastutil.ints.IntList;
                    import java.security.PublicKey;
                    import java.time.Instant;
                    import java.util.Collection;
                    import java.util.List;
                    import java.util.Map;
                    import java.util.function.IntFunction;
                    import net.minecraft.network.codec.StreamDecoder;
                    import net.minecraft.network.codec.StreamEncoder;
                    import net.minecraft.world.phys.BlockHitResult;
                    public class FriendlyByteBuf {
                        public static <T> IntFunction<T> limitValue(IntFunction<T> f, int limit) { throw new AssertionError(); }
                        public <T> void writeCollection(Collection<T> c, StreamEncoder<? super FriendlyByteBuf, T> e) { throw new AssertionError(); }
                        public <T, C extends Collection<T>> C readCollection(IntFunction<C> f, StreamDecoder<? super FriendlyByteBuf, T> d) { throw new AssertionError(); }
                        public <T> List<T> readList(StreamDecoder<? super FriendlyByteBuf, T> d) { throw new AssertionError(); }
                        public <K, V> void writeMap(Map<K, V> m, StreamEncoder<? super FriendlyByteBuf, K> k, StreamEncoder<? super FriendlyByteBuf, V> v) { throw new AssertionError(); }
                        public <K, V> Map<K, V> readMap(StreamDecoder<? super FriendlyByteBuf, K> k, StreamDecoder<? super FriendlyByteBuf, V> v) { throw new AssertionError(); }
                        public void writeIntIdList(IntList ids) { throw new AssertionError(); }
                        public IntList readIntIdList() { throw new AssertionError(); }
                        public void writeInstant(Instant i) { throw new AssertionError(); }
                        public Instant readInstant() { throw new AssertionError(); }
                        public FriendlyByteBuf writePublicKey(PublicKey k) { throw new AssertionError(); }
                        public PublicKey readPublicKey() { throw new AssertionError(); }
                        public void writeBlockHitResult(BlockHitResult h) { throw new AssertionError(); }
                        public BlockHitResult readBlockHitResult() { throw new AssertionError(); }
                        public FriendlyByteBuf writeUtf(String s) { throw new AssertionError(); }
                        public String readUtf() { throw new AssertionError(); }
                        public FriendlyByteBuf writeVarInt(int i) { throw new AssertionError(); }
                        public int readVarInt() { throw new AssertionError(); }
                    }"""));

    /** The 26.3 shape: the helpers are gone and the hit result carries its own codec. Values queue in order. */
    private static final Map<String, String> NEW_HOST = with(SHARED, Map.of(
            "net/minecraft/network/codec/StreamCodec.java", """
                    package net.minecraft.network.codec;
                    public interface StreamCodec<B, V> extends StreamEncoder<B, V>, StreamDecoder<B, V> {}""",
            "net/minecraft/world/phys/BlockHitResult.java", """
                    package net.minecraft.world.phys;
                    import net.minecraft.network.FriendlyByteBuf;
                    import net.minecraft.network.codec.StreamCodec;
                    public class BlockHitResult {
                        public static final StreamCodec<FriendlyByteBuf, BlockHitResult> STREAM_CODEC = new StreamCodec<>() {
                            public void encode(FriendlyByteBuf buf, BlockHitResult hit) { buf.writeVarInt(hit.pos); }
                            public BlockHitResult decode(FriendlyByteBuf buf) { return new BlockHitResult(buf.readVarInt()); }
                        };
                        public final int pos;
                        public BlockHitResult(int pos) { this.pos = pos; }
                    }""",
            "net/minecraft/network/FriendlyByteBuf.java", """
                    package net.minecraft.network;
                    public class FriendlyByteBuf {
                        private final java.util.ArrayDeque<Object> values = new java.util.ArrayDeque<>();
                        public FriendlyByteBuf writeVarInt(int v) { values.add(v); return this; }
                        public int readVarInt() { return (Integer) values.poll(); }
                        public FriendlyByteBuf writeLong(long v) { values.add(v); return this; }
                        public long readLong() { return (Long) values.poll(); }
                        public FriendlyByteBuf writeUtf(String s) { values.add(s); return this; }
                        public String readUtf() { return (String) values.poll(); }
                        public FriendlyByteBuf writeByteArray(byte[] b) { values.add(b); return this; }
                        public byte[] readByteArray(int max) { return (byte[]) values.poll(); }
                    }""",
            "net/minecraft/util/Crypt.java", """
                    package net.minecraft.util;
                    public class Crypt {
                        public static java.security.PublicKey byteToPublicKey(byte[] b) throws CryptException { throw new AssertionError(); }
                    }""",
            "net/minecraft/util/CryptException.java",
            "package net.minecraft.util; public class CryptException extends Exception {}",
            "io/netty/handler/codec/DecoderException.java", """
                    package io.netty.handler.codec;
                    public class DecoderException extends RuntimeException {
                        public DecoderException(String m) { super(m); }
                        public DecoderException(String m, Throwable c) { super(m, c); }
                    }"""));

    /**
     * A 1.21.1 mod's packet, written the way Cobblemon and Jade write theirs: direct calls on the
     * registry buffer, and method references to the hit result helpers.
     */
    private static final Map<String, String> MOD = Map.of("test/mod/Packets.java", """
            package test.mod;
            import it.unimi.dsi.fastutil.ints.IntArrayList;
            import it.unimi.dsi.fastutil.ints.IntList;
            import java.time.Instant;
            import java.util.*;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.codec.StreamDecoder;
            import net.minecraft.network.codec.StreamEncoder;
            import net.minecraft.world.phys.BlockHitResult;
            public class Packets {
                static final StreamEncoder<FriendlyByteBuf, String> UTF = FriendlyByteBuf::writeUtf;
                static final StreamDecoder<FriendlyByteBuf, String> READ_UTF = FriendlyByteBuf::readUtf;
                static final StreamEncoder<FriendlyByteBuf, Integer> VAR = FriendlyByteBuf::writeVarInt;
                static final StreamDecoder<FriendlyByteBuf, Integer> READ_VAR = FriendlyByteBuf::readVarInt;
                public static String roundTrip(RegistryFriendlyByteBuf buf, BlockHitResult hit) {
                    buf.writeCollection(List.of("a", "b"), UTF);
                    Map<String, Integer> counts = new TreeMap<>();
                    counts.put("x", 3);
                    counts.put("y", 4);
                    buf.writeMap(counts, UTF, VAR);
                    IntList ids = new IntArrayList();
                    ids.add(7);
                    ids.add(9);
                    buf.writeIntIdList(ids);
                    buf.writeInstant(Instant.ofEpochMilli(1234L));
                    StreamEncoder<RegistryFriendlyByteBuf, BlockHitResult> hitWriter = FriendlyByteBuf::writeBlockHitResult;
                    hitWriter.encode(buf, hit);
                    buf.writeCollection(List.of("p", "q", "r"), UTF);

                    List<String> names = buf.readList(READ_UTF);
                    Map<String, Integer> read = buf.readMap(READ_UTF, READ_VAR);
                    IntList readIds = buf.readIntIdList();
                    Instant at = buf.readInstant();
                    StreamDecoder<RegistryFriendlyByteBuf, BlockHitResult> hitReader = FriendlyByteBuf::readBlockHitResult;
                    BlockHitResult readHit = hitReader.decode(buf);
                    String limited;
                    try {
                        buf.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, 2), READ_UTF);
                        limited = "read";
                    } catch (RuntimeException e) {
                        limited = e.getClass().getSimpleName();
                    }
                    return names + " " + new TreeMap<>(read) + " " + readIds + " " + at.toEpochMilli() + " " + readHit.pos + " " + limited;
                }
            }""");

    private static Map<String, String> with(Map<String, String> base, Map<String, String> more) {
        Map<String, String> all = new HashMap<>(base);
        all.putAll(more);
        return all;
    }

    @SafeVarargs
    private Map<String, byte[]> compile(String label, Map<String, String>... sourceSets) throws Exception {
        Path src = tempDir.resolve(label + "-src");
        Path out = tempDir.resolve(label + "-out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map<String, String> sources : sourceSets) {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = src.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK to compile the host stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), label + " sources must compile");

        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
