/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.api.common.embedded.LegacyFrameworkSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Framework 0.8 messages, as a 1.20.1 mod such as Scorched Guns registers and sends them, on a
 * host that ships Framework 0.13.
 */
class LegacyFrameworkNetworkBridgeTest {

    private static final String NETWORK = LegacyFrameworkNetworkBridge.NETWORK;
    private static final String CONTEXT = LegacyFrameworkNetworkBridge.CONTEXT;
    private static final String BUILDER = LegacyFrameworkNetworkBridge.BUILDER;

    private RetromodTransformer transformer;

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
    @DisplayName("A host that still ships Framework 0.8 keeps the old message API")
    void oldFrameworkHostIsLeftAlone() {
        assertFalse(LegacyFrameworkNetworkBridge.registerIfHostIsModern(transformer,
                Set.of(BUILDER, LegacyFrameworkNetworkBridge.OLD_PLAY_MESSAGE)::contains));
        assertFalse(LegacyFrameworkNetworkBridge.registerIfHostIsModern(transformer, name -> false),
                "without Framework installed there is nothing to bridge to");
        assertTrue(transformer.getClassRedirects().isEmpty());
    }

    @Test
    @DisplayName("A message, its registration, and its sends move onto the 0.13 API")
    void legacyMessageUsesTheModernApi() {
        assertTrue(LegacyFrameworkNetworkBridge.registerIfHostIsModern(transformer, Set.of(BUILDER)::contains));

        ClassWriter message = new ClassWriter(0);
        message.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/C2SMessageAim",
                "Lcom/mrcrayfish/framework/api/network/message/PlayMessage<Ltest/C2SMessageAim;>;",
                LegacyFrameworkNetworkBridge.OLD_PLAY_MESSAGE, null);
        message.visitEnd();
        ClassNode messageOut = transform(message.toByteArray(), "test/C2SMessageAim");
        assertEquals(LegacyFrameworkNetworkBridge.PLAY_MESSAGE, messageOut.superName,
                "PlayMessage is gone, so messages extend the stand-in");

        String oldMessage = "L" + LegacyFrameworkNetworkBridge.OLD_MESSAGE + ";";
        String oldDirection = "L" + LegacyFrameworkNetworkBridge.OLD_DIRECTION + ";";
        byte[] handler = staticMethod("test/PacketHandler", "run",
                "(L" + BUILDER + ";L" + NETWORK + ";L" + CONTEXT + ";Ljava/util/function/Supplier;" + oldMessage + ")V",
                mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("test/C2SMessageAim"));
                    mv.visitFieldInsn(GETSTATIC, LegacyFrameworkNetworkBridge.OLD_DIRECTION, "PLAY_SERVER_BOUND", oldDirection);
                    mv.visitMethodInsn(INVOKEINTERFACE, BUILDER, "registerPlayMessage",
                            "(Ljava/lang/Class;" + oldDirection + ")L" + BUILDER + ";", true);
                    mv.visitInsn(POP);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    mv.visitMethodInsn(INVOKEINTERFACE, NETWORK, "sendToTracking",
                            "(Ljava/util/function/Supplier;" + oldMessage + ")V", true);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitMethodInsn(INVOKEVIRTUAL, CONTEXT, "getPlayer",
                            "()Lnet/minecraft/server/level/ServerPlayer;", false);
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                });
        ClassNode out = transform(handler, "test/PacketHandler");

        List<MethodInsnNode> calls = calls(out);
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(LegacyFrameworkNetworkBridge.CALLS)
                        && c.name.equals("registerPlayMessage") && c.getOpcode() == INVOKESTATIC),
                "the old builder call must build a codec through the helper: " + describe(calls));
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(NETWORK) && c.name.equals("sendToTrackingEntity")
                        && c.desc.equals("(Ljava/util/function/Supplier;Ljava/lang/Object;)V")),
                "sendToTracking was renamed and every send now takes Object: " + describe(calls));
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(LegacyFrameworkNetworkBridge.CALLS)
                        && c.name.equals("getPlayer")),
                "getPlayer now returns an Optional, so the helper unwraps it: " + describe(calls));
        assertTrue(instructions(out).stream().anyMatch(insn -> insn instanceof FieldInsnNode field
                        && field.owner.equals(LegacyFrameworkNetworkBridge.DIRECTION)),
                "MessageDirection constants must come from the stand-in enum");
    }

    @Test
    @DisplayName("The generated helpers are well formed")
    void generatedHelpersAreWellFormed() {
        byte[] calls = LegacyFrameworkNetworkBridge.generateCalls();
        assertDoesNotThrow(() -> new ClassReader(calls).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
    }

    @Test
    @DisplayName("A registered message encodes, decodes, and handles through its old methods")
    void registeredMessageRoundTrips() throws Exception {
        FakeHost host = new FakeHost(getClass().getClassLoader());
        Class<?> messageType = host.loadClass(PingMessage.class.getName());
        Class<?> builderType = host.loadClass(BUILDER.replace('/', '.'));
        List<Object> captured = new ArrayList<>();
        Object builder = Proxy.newProxyInstance(host, new Class<?>[] {builderType}, (proxy, method, args) -> {
            captured.addAll(List.of(args));
            return proxy;
        });

        LegacyFrameworkSupport.registerPlayMessage(builder, messageType, null);

        assertEquals(4, captured.size(), "a two-way message uses the overload without a PacketFlow");
        assertEquals(PingMessage.class.getName().toLowerCase().replace('$', '_'), captured.get(0),
                "the name must be a valid resource path that client and server derive the same way");
        assertSame(messageType, captured.get(1));

        Object codec = captured.get(2);
        Class<?> codecType = host.loadClass("net.minecraft.network.codec.StreamCodec");
        List<Object> buffer = new ArrayList<>();
        Object sent = messageType.getConstructor(int.class).newInstance(42);
        codecType.getMethod("encode", Object.class, Object.class).invoke(codec, buffer, sent);
        assertEquals(List.of(42), buffer, "StreamCodec.encode(buffer, value) calls the old encode(value, buffer)");
        Object received = codecType.getMethod("decode", Object.class).invoke(codec, buffer);
        assertEquals(42, messageType.getField("value").get(received));

        @SuppressWarnings("unchecked")
        BiConsumer<Object, Object> handler = (BiConsumer<Object, Object>) captured.get(3);
        List<Object> context = new ArrayList<>();
        handler.accept(received, context);
        assertEquals(List.of(42), context, "the handler must reach the old handle(message, context)");
    }

    /** Shaped like a Framework 0.8 message after javac erased its generic methods. */
    public static final class PingMessage {
        public int value;

        public PingMessage() {}

        public PingMessage(int value) {
            this.value = value;
        }

        @SuppressWarnings("unchecked")
        public void encode(Object message, Object buffer) {
            ((List<Object>) buffer).add(((PingMessage) message).value);
        }

        public Object decode(Object buffer) {
            return new PingMessage((Integer) ((List<?>) buffer).remove(0));
        }

        @SuppressWarnings("unchecked")
        public void handle(Object message, Object context) {
            ((List<Object>) context).add(((PingMessage) message).value);
        }
    }

    /** Defines the two host interfaces the support class looks up, and the message beside them. */
    private static final class FakeHost extends ClassLoader {
        private final Map<String, byte[]> classes;

        FakeHost(ClassLoader parent) throws Exception {
            super(parent);
            String codec = "net/minecraft/network/codec/StreamCodec";
            classes = Map.of(
                    "net.minecraft.network.codec.StreamCodec", hostInterface(codec, Map.of(
                            "decode", "(Ljava/lang/Object;)Ljava/lang/Object;",
                            "encode", "(Ljava/lang/Object;Ljava/lang/Object;)V")),
                    BUILDER.replace('/', '.'), hostInterface(BUILDER, Map.of("registerPlayMessage",
                            "(Ljava/lang/String;Ljava/lang/Class;L" + codec + ";Ljava/util/function/BiConsumer;)L"
                                    + BUILDER + ";")),
                    PingMessage.class.getName(), resourceBytes(PingMessage.class));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                byte[] bytes = classes.get(name);
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }

        private static byte[] hostInterface(String name, Map<String, String> methods) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", null);
            methods.forEach((method, desc) -> cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, method, desc, null, null).visitEnd());
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] resourceBytes(Class<?> type) throws Exception {
            try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
                return in.readAllBytes();
            }
        }
    }

    private ClassNode transform(byte[] input, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(input, name)).accept(node, 0);
        return node;
    }

    private static byte[] staticMethod(String owner, String name, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, owner, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<AbstractInsnNode> instructions(ClassNode node) {
        List<AbstractInsnNode> all = new ArrayList<>();
        for (MethodNode method : node.methods) method.instructions.forEach(all::add);
        return all;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        return instructions(node).stream()
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

    private static String describe(List<MethodInsnNode> calls) {
        return calls.stream().map(c -> c.owner + "." + c.name + c.desc).toList().toString();
    }
}
