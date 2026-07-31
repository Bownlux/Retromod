/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft-fail bridge for Fabric networking V0 ({@code net/fabricmc/fabric/api/network/*}), the
 * Identifier-keyed packet-consumer API deprecated by V1 in 1.20.5 and removed in 2024. Mods like
 * Xaero's Minimap, REI, and Comforts still ship V0 jars.
 *
 * <p>Injects stand-in types under {@code com/retromod/generated/legacynetwork/*} and redirects the
 * old references to them. Send/register methods no-op and the getters return null, so V0 multiplayer
 * features stay dark but the mod loads.
 */
public class FabricNetworkingV0Bridge implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String OLD_PACKET_CONTEXT  = "net/fabricmc/fabric/api/network/PacketContext";
    private static final String OLD_PACKET_CONSUMER = "net/fabricmc/fabric/api/network/PacketConsumer";
    private static final String OLD_SERVER_REGISTRY = "net/fabricmc/fabric/api/network/ServerSidePacketRegistry";
    private static final String OLD_CLIENT_REGISTRY = "net/fabricmc/fabric/api/network/ClientSidePacketRegistry";

    private static final String NEW_PACKET_CONTEXT  = "com/retromod/generated/legacynetwork/PacketContext";
    private static final String NEW_PACKET_CONSUMER = "com/retromod/generated/legacynetwork/PacketConsumer";
    private static final String NEW_SERVER_REGISTRY = "com/retromod/generated/legacynetwork/ServerSidePacketRegistry";
    private static final String NEW_CLIENT_REGISTRY = "com/retromod/generated/legacynetwork/ClientSidePacketRegistry";
    private static final String IMPL_SERVER_REGISTRY = "com/retromod/generated/legacynetwork/ServerSidePacketRegistryImpl";
    private static final String IMPL_CLIENT_REGISTRY = "com/retromod/generated/legacynetwork/ClientSidePacketRegistryImpl";

    private static final String L_PACKET_CONTEXT   = "L" + NEW_PACKET_CONTEXT + ";";
    private static final String L_PACKET_CONSUMER  = "L" + NEW_PACKET_CONSUMER + ";";
    private static final String L_SERVER_REGISTRY  = "L" + NEW_SERVER_REGISTRY + ";";
    private static final String L_CLIENT_REGISTRY  = "L" + NEW_CLIENT_REGISTRY + ";";

    // MC types used only in descriptors, via intermediary aliases.
    private static final String L_IDENTIFIER   = "Lnet/minecraft/class_2960;";
    private static final String L_BYTE_BUF     = "Lnet/minecraft/class_2540;";
    private static final String L_PACKET       = "Lnet/minecraft/class_2596;";
    private static final String L_PLAYER       = "Lnet/minecraft/class_1657;";
    private static final String L_SERVER_PLAYER = "Lnet/minecraft/class_3222;";

    @Override public String getShimName() { return "Fabric Networking V0 Soft-Fail"; }
    @Override public String getSourceVersion() { return "0.50.0"; }
    @Override public String getTargetVersion() { return "0.100.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(NEW_PACKET_CONSUMER, generatePacketConsumerInterface());
        transformer.registerSyntheticClass(NEW_PACKET_CONTEXT,  generatePacketContextInterface());
        transformer.registerSyntheticClass(NEW_SERVER_REGISTRY, generateServerRegistryInterface());
        transformer.registerSyntheticClass(NEW_CLIENT_REGISTRY, generateClientRegistryInterface());
        transformer.registerSyntheticClass(IMPL_SERVER_REGISTRY, generateServerRegistryImpl());
        transformer.registerSyntheticClass(IMPL_CLIENT_REGISTRY, generateClientRegistryImpl());

        transformer.registerClassRedirect(OLD_PACKET_CONSUMER, NEW_PACKET_CONSUMER);
        transformer.registerClassRedirect(OLD_PACKET_CONTEXT,  NEW_PACKET_CONTEXT);
        transformer.registerClassRedirect(OLD_SERVER_REGISTRY, NEW_SERVER_REGISTRY);
        transformer.registerClassRedirect(OLD_CLIENT_REGISTRY, NEW_CLIENT_REGISTRY);

        LOGGER.warn("Loaded support for Fabric networking v0. Mods can start, but packets sent "
                + "through that removed API will be ignored.");
    }

    private static byte[] generatePacketConsumerInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_PACKET_CONSUMER, null, "java/lang/Object", null);
        abstractMethod(cw, "accept", "(" + L_PACKET_CONTEXT + L_BYTE_BUF + ")V");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generatePacketContextInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_PACKET_CONTEXT, null, "java/lang/Object", null);
        abstractMethod(cw, "getPlayer",        "()" + L_PLAYER);
        // PacketSender and ThreadExecutor stubbed as Object
        abstractMethod(cw, "getPacketSender",  "()Ljava/lang/Object;");
        abstractMethod(cw, "getTaskQueue",     "()Ljava/lang/Object;");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateServerRegistryInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_SERVER_REGISTRY, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "INSTANCE", L_SERVER_REGISTRY, null, null).visitEnd();

        abstractMethod(cw, "sendToPlayer", "(" + L_PLAYER + L_IDENTIFIER + L_BYTE_BUF + ")V");
        abstractMethod(cw, "sendToPlayer", "(" + L_PLAYER + L_PACKET + ")V");
        abstractMethod(cw, "sendToPlayer", "(" + L_SERVER_PLAYER + L_IDENTIFIER + L_BYTE_BUF + ")V");
        abstractMethod(cw, "sendToPlayer", "(" + L_SERVER_PLAYER + L_PACKET + ")V");
        abstractMethod(cw, "canPlayerReceive", "(" + L_PLAYER + L_IDENTIFIER + ")Z");
        abstractMethod(cw, "register",   "(" + L_IDENTIFIER + L_PACKET_CONSUMER + ")Ljava/util/concurrent/CompletableFuture;");
        abstractMethod(cw, "unregister", "(" + L_IDENTIFIER + ")Z");

        // INSTANCE = new ServerSidePacketRegistryImpl();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, IMPL_SERVER_REGISTRY);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL_SERVER_REGISTRY, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, NEW_SERVER_REGISTRY, "INSTANCE", L_SERVER_REGISTRY);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClientRegistryInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_CLIENT_REGISTRY, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "INSTANCE", L_CLIENT_REGISTRY, null, null).visitEnd();

        abstractMethod(cw, "sendToServer",     "(" + L_IDENTIFIER + L_BYTE_BUF + ")V");
        abstractMethod(cw, "sendToServer",     "(" + L_PACKET + ")V");
        abstractMethod(cw, "canServerReceive", "(" + L_IDENTIFIER + ")Z");
        abstractMethod(cw, "register",   "(" + L_IDENTIFIER + L_PACKET_CONSUMER + ")Ljava/util/concurrent/CompletableFuture;");
        abstractMethod(cw, "unregister", "(" + L_IDENTIFIER + ")Z");

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, IMPL_CLIENT_REGISTRY);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL_CLIENT_REGISTRY, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, NEW_CLIENT_REGISTRY, "INSTANCE", L_CLIENT_REGISTRY);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateServerRegistryImpl() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                IMPL_SERVER_REGISTRY, null, "java/lang/Object",
                new String[]{NEW_SERVER_REGISTRY});
        emitDefaultCtor(cw);
        noopVoid(cw, "sendToPlayer", "(" + L_PLAYER + L_IDENTIFIER + L_BYTE_BUF + ")V");
        noopVoid(cw, "sendToPlayer", "(" + L_PLAYER + L_PACKET + ")V");
        noopVoid(cw, "sendToPlayer", "(" + L_SERVER_PLAYER + L_IDENTIFIER + L_BYTE_BUF + ")V");
        noopVoid(cw, "sendToPlayer", "(" + L_SERVER_PLAYER + L_PACKET + ")V");
        returnFalse(cw, "canPlayerReceive", "(" + L_PLAYER + L_IDENTIFIER + ")Z");
        completedFuture(cw, "register", "(" + L_IDENTIFIER + L_PACKET_CONSUMER + ")Ljava/util/concurrent/CompletableFuture;");
        returnFalse(cw, "unregister", "(" + L_IDENTIFIER + ")Z");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClientRegistryImpl() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                IMPL_CLIENT_REGISTRY, null, "java/lang/Object",
                new String[]{NEW_CLIENT_REGISTRY});
        emitDefaultCtor(cw);
        noopVoid(cw, "sendToServer", "(" + L_IDENTIFIER + L_BYTE_BUF + ")V");
        noopVoid(cw, "sendToServer", "(" + L_PACKET + ")V");
        returnFalse(cw, "canServerReceive", "(" + L_IDENTIFIER + ")Z");
        completedFuture(cw, "register", "(" + L_IDENTIFIER + L_PACKET_CONSUMER + ")Ljava/util/concurrent/CompletableFuture;");
        returnFalse(cw, "unregister", "(" + L_IDENTIFIER + ")Z");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter newClassWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                try { return super.getCommonSuperClass(t1, t2); }
                catch (Throwable t) { return "java/lang/Object"; }
            }
        };
    }

    private static void abstractMethod(ClassWriter cw, String name, String desc) {
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null).visitEnd();
    }

    private static void emitDefaultCtor(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void noopVoid(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void returnFalse(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Emits {@code return CompletableFuture.completedFuture(null);}. */
    private static void completedFuture(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture",
                "completedFuture", "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
