/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An old 1.20.1 mod's raw and typed channels, translated by the CLI, run on the fake host. */
class TranslatedModOnFakeHostTest {

    private static final String API = "net/fabricmc/fabric/api/";
    private static final String SPN = API + "networking/v1/ServerPlayNetworking";
    private static final String CPN = API + "client/networking/v1/ClientPlayNetworking";
    private static final String SENDER = API + "networking/v1/PacketSender";
    private static final String FABRIC_PACKET = API + "networking/v1/FabricPacket";
    private static final String PACKET_TYPE = API + "networking/v1/PacketType";
    private static final String ID = "Lnet/minecraft/class_2960;";
    private static final String BUF = "Lnet/minecraft/class_2540;";
    private static final String PLAYER = "Lnet/minecraft/class_3222;";
    private static final String LISTENER = "Lnet/minecraft/class_3244;";
    private static final String CHANNELS = "fixture/oldmod/Channels";
    private static final String PING = "fixture/oldmod/Ping";
    private static final String RECORDER = "fixture/Recorder";
    private static final String SUB_HANDLER = "fixture/oldmod/ServerHandler";
    private static final String BASE_HANDLER = "fixture/lib/BaseHandler";
    private static final String BOTH_HANDLER = "fixture/dep/BothHandler";
    private static final String DEP_MAIN = "fixture/dep/Main";
    private static final String DUAL_HANDLER = "fixture/dual/Both";
    private static final String DUAL_MAIN = "fixture/dual/Main";
    private static final String SERVER_SAM = "(Lnet/minecraft/server/MinecraftServer;" + PLAYER + LISTENER + BUF
            + "L" + SENDER + ";)V";

    private AutoCloseable runtime;
    private String savedHost;

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    // The mod uses a server lambda, a client override, a typed packet built from a constructor
    // reference, a response sender, an old send listener, and a lambda for its own handler subtype.
    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("A translated old mod exchanges raw and typed packets on a 26.x host")
    void oldModTalksOnTheHost(String host) throws Throwable {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
        Path input = dir.resolve("oldmod.jar");
        Files.write(input, oldModJar());
        Path output = dir.resolve("oldmod-" + host + ".jar");
        CliHarness.transform(input, output, host);

        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mod = fake.defineMod(classesOf(output));
            Class<?> channels = Class.forName(CHANNELS.replace('/', '.'), true, mod);
            channels.getField("ECHO").set(null, fake.id("oldmod:echo"));
            channels.getField("REPLY").set(null, fake.id("oldmod:reply"));
            channels.getField("TYPED").set(null, fake.id("oldmod:typed"));
            run(channels, "server");
            run(channels, "client");
            run(channels, "typed");
            channels.getField("SUB").set(null, fake.id("oldmod:sub"));
            run(channels, "subtype");
            assertEquals(List.of("server got 7", "client got 41", "client got 42", "listener ran",
                    "server typed 3", "client typed 4", "subtype got 5"), fake.events());
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("A handler whose library base class implements the old type too receives, with each jar translated apart")
    void handlerAcrossTwoJarsReceives(String host) throws Throwable {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
        Map<String, byte[]> library = new LinkedHashMap<>();
        library.put(BASE_HANDLER + ".class", baseHandler());
        Map<String, byte[]> dependent = new LinkedHashMap<>();
        dependent.put(BOTH_HANDLER + ".class", bothHandler());
        dependent.put(DEP_MAIN + ".class", depMain());
        Map<String, byte[]> classes = new HashMap<>(translate("twojar_lib", library, host));
        classes.putAll(translate("twojar_dep", dependent, host));

        try (FakeHost fake = FakeHost.start()) {
            Class<?> main = Class.forName(DEP_MAIN.replace('/', '.'), true, fake.defineMod(classes));
            main.getField("ID").set(null, fake.id("twojar:echo"));
            run(main, "register");
            run(main, "send");
            assertEquals(List.of("base got 6"), fake.events());
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("One class that implements the old server and client packet handlers receives on both sides")
    void classWithBothPacketHandlersReceives(String host) throws Throwable {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(DUAL_HANDLER + ".class", dualHandler());
        mod.put(DUAL_MAIN + ".class", dualMain());
        mod.put(PING + ".class", ping());
        Map<String, byte[]> classes = translate("dualmod", mod, host);

        try (FakeHost fake = FakeHost.start()) {
            Class<?> main = Class.forName(DUAL_MAIN.replace('/', '.'), true, fake.defineMod(classes));
            main.getField("ID").set(null, fake.id("dualmod:ping"));
            run(main, "run");
            assertEquals(List.of("server dual 3", "client dual 4"), fake.events());
        }
    }

    /** Translates one small 1.20.1 mod through the CLI path and returns its classes. */
    private Map<String, byte[]> translate(String id, Map<String, byte[]> classes, String host) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabric-api\":\"*\"}}").getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) entries.put(entry.getKey(), entry.getValue());
        Path input = dir.resolve(id + ".jar");
        Files.write(input, LoaderApiFixtures.jar(entries));
        Path output = dir.resolve(id + "-" + host + ".jar");
        CliHarness.transform(input, output, host);
        return classesOf(output);
    }

    private static void run(Class<?> owner, String method) throws Throwable {
        try {
            owner.getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Map<String, byte[]> classesOf(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (var entries = file.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                String name = entry.getName().substring(0, entry.getName().length() - ".class".length());
                classes.put(name, file.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    // ---- The old mod ------------------------------------------------------------------------

    static byte[] oldModJar() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"oldmod\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabric-api\":\"*\"}}")
                .getBytes(StandardCharsets.UTF_8));
        entries.put(CHANNELS + ".class", channels());
        entries.put("fixture/oldmod/ClientHandler.class", clientHandler());
        entries.put(PING + ".class", ping());
        entries.put(SUB_HANDLER + ".class", subHandler());
        return LoaderApiFixtures.jar(entries);
    }

    /** Registers and sends the way a 1.20.1 mod did. */
    private static byte[] channels() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CHANNELS, null, "java/lang/Object", null);
        for (String field : List.of("ECHO", "REPLY", "TYPED", "SUB")) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, ID, null, null).visitEnd();
        }
        String serverSam = "(Lnet/minecraft/server/MinecraftServer;" + PLAYER + LISTENER + BUF + "L" + SENDER + ";)V";

        // server(): a lambda receiver on ECHO.
        MethodVisitor mv = LoaderApiFixtures.method(cw, "server", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "ECHO", ID);
        indy(mv, SPN + "$PlayChannelHandler", "receive", serverSam, CHANNELS, "serverBody", serverSam);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPN, "registerGlobalReceiver",
                "(" + ID + "L" + SPN + "$PlayChannelHandler;)Z", false);
        mv.visitInsn(Opcodes.POP);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // The handler records the bytes, answers on REPLY, then answers again with a packet and listener.
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "serverBody",
                serverSam, null, null);
        mv.visitCode();
        mv.visitLdcInsn("server got");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;Lio/netty/buffer/ByteBuf;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "REPLY", ID);
        buf(mv, 41);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, SENDER, "sendPacket", "(" + ID + BUF + ")V", true);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "REPLY", ID);
        buf(mv, 42);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPN, "createS2CPacket", "(" + ID + BUF + ")Lnet/minecraft/class_2596;", false);
        indy(mv, "java/lang/Runnable", "run", "()V", CHANNELS, "listenerBody", "()V");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/class_7648", "method_45084",
                "(Ljava/lang/Runnable;)Lnet/minecraft/class_7648;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, SENDER, "sendPacket",
                "(Lnet/minecraft/class_2596;Lnet/minecraft/class_7648;)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "listenerBody",
                "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn("listener ran");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "note", "(Ljava/lang/String;)V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // client(): an override receiver on REPLY, then a send on ECHO.
        mv = LoaderApiFixtures.method(cw, "client", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "REPLY", ID);
        mv.visitTypeInsn(Opcodes.NEW, "fixture/oldmod/ClientHandler");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "fixture/oldmod/ClientHandler", "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CPN, "registerGlobalReceiver",
                "(" + ID + "L" + CPN + "$PlayChannelHandler;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "ECHO", ID);
        buf(mv, 7);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CPN, "send", "(" + ID + BUF + ")V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // typed(): Ping's type from a constructor reference, a receiver on each side, then a send.
        String serverTyped = "(L" + FABRIC_PACKET + ";" + PLAYER + "L" + SENDER + ";)V";
        String clientTyped = "(L" + FABRIC_PACKET + ";Lnet/minecraft/class_746;L" + SENDER + ";)V";
        mv = LoaderApiFixtures.method(cw, "typed", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "TYPED", ID);
        mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory(),
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_NEWINVOKESPECIAL, PING, "<init>", "(" + BUF + ")V", false),
                Type.getType("(" + BUF + ")L" + PING + ";"));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PACKET_TYPE, "create",
                "(" + ID + "Ljava/util/function/Function;)L" + PACKET_TYPE + ";", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, PING, "TYPE", "L" + PACKET_TYPE + ";");
        mv.visitFieldInsn(Opcodes.GETSTATIC, PING, "TYPE", "L" + PACKET_TYPE + ";");
        String serverBody = "(L" + PING + ";" + PLAYER + "L" + SENDER + ";)V";
        mv.visitInvokeDynamicInsn("receive", "()L" + SPN + "$PlayPacketHandler;", metafactory(),
                Type.getType(serverTyped), new Handle(Opcodes.H_INVOKESTATIC, CHANNELS, "serverTyped", serverBody, false),
                Type.getType(serverBody));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPN, "registerGlobalReceiver",
                "(L" + PACKET_TYPE + ";L" + SPN + "$PlayPacketHandler;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PING, "TYPE", "L" + PACKET_TYPE + ";");
        String clientBody = "(L" + PING + ";Lnet/minecraft/class_746;L" + SENDER + ";)V";
        mv.visitInvokeDynamicInsn("receive", "()L" + CPN + "$PlayPacketHandler;", metafactory(),
                Type.getType(clientTyped), new Handle(Opcodes.H_INVOKESTATIC, CHANNELS, "clientTyped", clientBody, false),
                Type.getType(clientBody));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CPN, "registerGlobalReceiver",
                "(L" + PACKET_TYPE + ";L" + CPN + "$PlayPacketHandler;)Z", false);
        mv.visitInsn(Opcodes.POP);
        newPing(mv, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CPN, "send", "(L" + FABRIC_PACKET + ";)V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // The server notes the typed packet and answers with another through its response sender.
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "serverTyped",
                serverBody, null, null);
        mv.visitCode();
        notePing(mv, "server typed");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        newPing(mv, 4);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, SENDER, "sendPacket", "(L" + FABRIC_PACKET + ";)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "clientTyped",
                clientBody, null, null);
        mv.visitCode();
        notePing(mv, "client typed");
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // subtype(): a lambda for the mod's own interface that extends the old server handler.
        mv = LoaderApiFixtures.method(cw, "subtype", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "SUB", ID);
        String subBody = serverSam;
        mv.visitInvokeDynamicInsn("receive", "()L" + SUB_HANDLER + ";", metafactory(), Type.getType(serverSam),
                new Handle(Opcodes.H_INVOKESTATIC, CHANNELS, "subtypeBody", subBody, false), Type.getType(subBody));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPN, "registerGlobalReceiver",
                "(" + ID + "L" + SPN + "$PlayChannelHandler;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, CHANNELS, "SUB", ID);
        buf(mv, 5);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CPN, "send", "(" + ID + BUF + ")V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "subtypeBody",
                subBody, null, null);
        mv.visitCode();
        mv.visitLdcInsn("subtype got");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;Lio/netty/buffer/ByteBuf;)V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The mod's own handler interface, a subtype of the old server handler. */
    private static byte[] subHandler() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, SUB_HANDLER, null,
                "java/lang/Object", new String[]{SPN + "$PlayChannelHandler"});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that overrides the old client handler. */
    private static byte[] clientHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "fixture/oldmod/ClientHandler", null,
                "java/lang/Object", new String[]{CPN + "$PlayChannelHandler"});
        constructor(cw, "()V");
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "receive",
                "(Lnet/minecraft/class_310;Lnet/minecraft/class_634;" + BUF + "L" + SENDER + ";)V", null, null);
        mv.visitCode();
        mv.visitLdcInsn("client got");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;Lio/netty/buffer/ByteBuf;)V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An old typed packet carrying one int. */
    private static byte[] ping() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PING, null, "java/lang/Object",
                new String[]{FABRIC_PACKET});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "TYPE", "L" + PACKET_TYPE + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "value", "I", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, PING, "value", "I");
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + BUF + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2540", "readInt", "()I", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PING, "<init>", "(I)V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "write", "(" + BUF + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, PING, "value", "I");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2540", "writeInt", "(I)Lio/netty/buffer/ByteBuf;", false);
        mv.visitInsn(Opcodes.POP);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getType", "()L" + PACKET_TYPE + ";", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, PING, "TYPE", "L" + PACKET_TYPE + ";");
        LoaderApiFixtures.end(mv, Opcodes.ARETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- Two jars: a library base class and a dependent class that both name the old type ----

    /** A library's base handler that implements the old server channel handler. */
    private static byte[] baseHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, BASE_HANDLER, null, "java/lang/Object",
                new String[]{SPN + "$PlayChannelHandler"});
        constructor(cw, "()V");
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "receive", SERVER_SAM, null, null);
        mv.visitCode();
        mv.visitLdcInsn("base got");
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;Lio/netty/buffer/ByteBuf;)V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A dependent's handler that extends the library base and names the old type again. */
    private static byte[] bothHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, BOTH_HANDLER, null, BASE_HANDLER,
                new String[]{SPN + "$PlayChannelHandler"});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, BASE_HANDLER, "<init>", "()V", false);
        LoaderApiFixtures.end(init, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Registers the dependent's handler on ID, and sends on ID from the client. */
    private static byte[] depMain() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, DEP_MAIN, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ID", ID, null, null).visitEnd();
        MethodVisitor mv = LoaderApiFixtures.method(cw, "register", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, DEP_MAIN, "ID", ID);
        mv.visitTypeInsn(Opcodes.NEW, BOTH_HANDLER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BOTH_HANDLER, "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPN, "registerGlobalReceiver",
                "(" + ID + "L" + SPN + "$PlayChannelHandler;)Z", false);
        mv.visitInsn(Opcodes.POP);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        mv = LoaderApiFixtures.method(cw, "send", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, DEP_MAIN, "ID", ID);
        buf(mv, 6);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CPN, "send", "(" + ID + BUF + ")V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- One class that implements both old typed packet handlers -----------------------------

    /** Notes each packet it gets; the server side answers with the next number. */
    private static byte[] dualHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, DUAL_HANDLER, null, "java/lang/Object",
                new String[]{SPN + "$PlayPacketHandler", CPN + "$PlayPacketHandler"});
        constructor(cw, "()V");
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "receive",
                "(L" + FABRIC_PACKET + ";" + PLAYER + "L" + SENDER + ";)V", null, null);
        mv.visitCode();
        notePacket(mv, "server dual");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitTypeInsn(Opcodes.NEW, PING);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PING);
        mv.visitFieldInsn(Opcodes.GETFIELD, PING, "value", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PING, "<init>", "(I)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, SENDER, "sendPacket", "(L" + FABRIC_PACKET + ";)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "receive",
                "(L" + FABRIC_PACKET + ";Lnet/minecraft/class_746;L" + SENDER + ";)V", null, null);
        mv.visitCode();
        notePacket(mv, "client dual");
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Builds Ping's type, registers one handler object on both sides, and sends from the client. */
    private static byte[] dualMain() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, DUAL_MAIN, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ID", ID, null, null).visitEnd();
        MethodVisitor mv = LoaderApiFixtures.method(cw, "run", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, DUAL_MAIN, "ID", ID);
        mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory(),
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_NEWINVOKESPECIAL, PING, "<init>", "(" + BUF + ")V", false),
                Type.getType("(" + BUF + ")L" + PING + ";"));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PACKET_TYPE, "create",
                "(" + ID + "Ljava/util/function/Function;)L" + PACKET_TYPE + ";", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, PING, "TYPE", "L" + PACKET_TYPE + ";");
        for (String networking : List.of(SPN, CPN)) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, PING, "TYPE", "L" + PACKET_TYPE + ";");
            mv.visitTypeInsn(Opcodes.NEW, DUAL_HANDLER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, DUAL_HANDLER, "<init>", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, networking, "registerGlobalReceiver",
                    "(L" + PACKET_TYPE + ";L" + networking + "$PlayPacketHandler;)Z", false);
            mv.visitInsn(Opcodes.POP);
        }
        newPing(mv, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CPN, "send", "(L" + FABRIC_PACKET + ";)V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Notes {@code label} and the value of the Ping in local 1. */
    private static void notePacket(MethodVisitor mv, String label) {
        mv.visitLdcInsn(label);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PING);
        mv.visitFieldInsn(Opcodes.GETFIELD, PING, "value", "I");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "noteInt", "(Ljava/lang/String;I)V", false);
    }

    // ---- Bytecode helpers --------------------------------------------------------------------

    private static Handle metafactory() {
        return new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    private static void indy(MethodVisitor mv, String iface, String sam, String samDesc, String owner,
            String body, String bodyDesc) {
        mv.visitInvokeDynamicInsn(sam, "()L" + iface + ";", metafactory(), Type.getType(samDesc),
                new Handle(Opcodes.H_INVOKESTATIC, owner, body, bodyDesc, false), Type.getType(bodyDesc));
    }

    private static void buf(MethodVisitor mv, int value) {
        mv.visitIntInsn(Opcodes.BIPUSH, value);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "buf", "(I)" + BUF, false);
    }

    private static void newPing(MethodVisitor mv, int value) {
        mv.visitTypeInsn(Opcodes.NEW, PING);
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH, value);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PING, "<init>", "(I)V", false);
    }

    private static void notePing(MethodVisitor mv, String label) {
        mv.visitLdcInsn(label);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, PING, "value", "I");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "noteInt", "(Ljava/lang/String;I)V", false);
    }

    private static void constructor(ClassWriter cw, String desc) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        LoaderApiFixtures.end(init, Opcodes.RETURN);
    }
}
