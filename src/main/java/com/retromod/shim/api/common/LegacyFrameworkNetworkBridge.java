/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.api.common.embedded.LegacyFrameworkLoginData;
import com.retromod.shim.api.common.embedded.LegacyFrameworkMessage;
import com.retromod.shim.api.common.embedded.LegacyFrameworkSupport;
import com.retromod.shim.api.common.embedded.LegacyMessageDirection;
import com.retromod.shim.api.common.embedded.LegacyPlayMessage;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * Bridges MrCrayfish's Framework 0.8 message API onto Framework 0.13, which a 1.20.1 mod meets on
 * a 1.21.1 host.
 *
 * <p>Framework 0.13 removed {@code PlayMessage}, {@code IMessage}, {@code MessageDirection}, and
 * login data. Messages became plain objects registered with a name, a {@code StreamCodec}, and a
 * handler, and every send method takes {@code Object}. The old types become small stand-ins, sends
 * keep their call with the new descriptor, and the old builder call is replaced by
 * {@link LegacyFrameworkSupport}, which builds the codec and handler from one message instance.
 * {@code MessageContext} kept its name but changed {@code getPlayer} and {@code execute}, which go
 * through generated helpers with the exact old return types.
 *
 * <p>Login data is accepted and not sent, because 0.13 has no login phase hook. A mod that syncs
 * settings to clients at login keeps its server behavior, and clients see their own defaults.
 * Handshake messages are not bridged.
 *
 * <p>The host's Framework decides whether this applies: it registers only when the 0.13 builder
 * exists and {@code PlayMessage} does not, so a mod already built for that Framework is left alone.
 */
public final class LegacyFrameworkNetworkBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Shim");

    private static final String FW = "com/mrcrayfish/framework/";
    static final String BUILDER = FW + "api/network/FrameworkNetworkBuilder";
    static final String NETWORK = FW + "api/network/FrameworkNetwork";
    static final String CONTEXT = FW + "api/network/MessageContext";
    static final String LEVEL_LOCATION = FW + "api/network/LevelLocation";
    static final String OLD_PLAY_MESSAGE = FW + "api/network/message/PlayMessage";
    static final String OLD_MESSAGE = FW + "network/message/IMessage";
    static final String OLD_DIRECTION = FW + "api/network/MessageDirection";
    static final String OLD_LOGIN_DATA = FW + "api/data/login/ILoginData";
    static final String OLD_SERIALIZER = FW + "api/sync/IDataSerializer";
    static final String SERIALIZER = FW + "api/sync/DataSerializer";
    static final String FRAMEWORK_API = FW + "api/FrameworkAPI";

    private static final String EMBEDDED = "com/retromod/shim/api/common/embedded/";
    static final String MESSAGE = EMBEDDED + "LegacyFrameworkMessage";
    static final String PLAY_MESSAGE = EMBEDDED + "LegacyPlayMessage";
    static final String DIRECTION = EMBEDDED + "LegacyMessageDirection";
    static final String LOGIN_DATA = EMBEDDED + "LegacyFrameworkLoginData";
    static final String SUPPORT = EMBEDDED + "LegacyFrameworkSupport";
    static final String CALLS = "com/retromod/generated/LegacyFrameworkCalls";

    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String MODERN_REGISTER_DESC = "(Ljava/lang/String;Ljava/lang/Class;"
            + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/BiConsumer;)L" + BUILDER + ";";

    /** Old send methods, all of which now take {@code Object}; the last pair was renamed. */
    private static final String[][] SENDS = {
        {"sendToPlayer", "Ljava/util/function/Supplier;", "sendToPlayer"},
        {"sendToTrackingEntity", "Ljava/util/function/Supplier;", "sendToTrackingEntity"},
        {"sendToTrackingBlockEntity", "Ljava/util/function/Supplier;", "sendToTrackingBlockEntity"},
        {"sendToTrackingLocation", "Ljava/util/function/Supplier;", "sendToTrackingLocation"},
        {"sendToTrackingChunk", "Ljava/util/function/Supplier;", "sendToTrackingChunk"},
        {"sendToNearbyPlayers", "Ljava/util/function/Supplier;", "sendToNearbyPlayers"},
        {"sendToServer", "", "sendToServer"},
        {"sendToAll", "", "sendToAll"},
        {"sendToTracking", "Ljava/util/function/Supplier;", "sendToTrackingEntity"},
    };

    private LegacyFrameworkNetworkBridge() {}

    public static void register(RetromodTransformer transformer) {
        registerIfHostIsModern(transformer, ClassResourceInspector::exists);
    }

    static boolean registerIfHostIsModern(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(OLD_PLAY_MESSAGE) || !hostHasClass.test(BUILDER)) return false;
        ClassNode builder = ClassResourceInspector.read(BUILDER);
        if (builder != null && !hasMethod(builder, "registerPlayMessage", MODERN_REGISTER_DESC)) {
            LOGGER.debug("Framework's builder is neither the 0.8 nor the 0.13 shape; not bridging");
            return false;
        }
        registerRedirects(transformer);
        LOGGER.info("Bridged the Framework 0.8 message API onto this host's Framework");
        return true;
    }

    static void registerRedirects(RetromodTransformer transformer) {
        for (Class<?> embedded : new Class<?>[] {LegacyFrameworkMessage.class, LegacyPlayMessage.class,
                LegacyMessageDirection.class, LegacyFrameworkLoginData.class, LegacyFrameworkSupport.class}) {
            SyntheticEmbedder.registerClassResource(transformer, embedded.getName().replace('.', '/'), embedded);
        }
        transformer.registerSyntheticClass(CALLS, generateCalls());

        transformer.registerClassRedirect(OLD_MESSAGE, MESSAGE);
        transformer.registerClassRedirect(OLD_PLAY_MESSAGE, PLAY_MESSAGE);
        transformer.registerClassRedirect(OLD_DIRECTION, DIRECTION);
        transformer.registerClassRedirect(OLD_LOGIN_DATA, LOGIN_DATA);
        // Same role, now a final class built from a stream codec; mods only pass the constants.
        transformer.registerClassRedirect(OLD_SERIALIZER, SERIALIZER);
        transformer.registerClassOwner(SERIALIZER);

        String oldMessage = "L" + OLD_MESSAGE + ";";
        for (String[] send : SENDS) {
            transformer.registerMethodRedirect(NETWORK, send[0], "(" + send[1] + oldMessage + ")V",
                    NETWORK, send[2], "(" + send[1] + "Ljava/lang/Object;)V");
        }
        transformer.registerMethodRedirect(CONTEXT, "reply", "(" + oldMessage + ")V",
                CONTEXT, "reply", "(Ljava/lang/Object;)V");

        String builder = "L" + BUILDER + ";";
        transformer.registerMethodRedirect(BUILDER, "registerPlayMessage", "(Ljava/lang/Class;)" + builder,
                CALLS, "registerPlayMessage", "(" + builder + "Ljava/lang/Class;)" + builder, true);
        transformer.registerMethodRedirect(BUILDER, "registerPlayMessage",
                "(Ljava/lang/Class;L" + OLD_DIRECTION + ";)" + builder,
                CALLS, "registerPlayMessage", "(" + builder + "Ljava/lang/Class;L" + DIRECTION + ";)" + builder, true);
        transformer.registerMethodRedirect(CONTEXT, "getPlayer", "()L" + SERVER_PLAYER + ";",
                CALLS, "getPlayer", "(L" + CONTEXT + ";)L" + SERVER_PLAYER + ";", true);
        transformer.registerMethodRedirect(CONTEXT, "execute",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
                CALLS, "execute", "(L" + CONTEXT + ";Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", true);
        transformer.registerMethodRedirect(LEVEL_LOCATION, "create", "(L" + LEVEL + ";DDDD)L" + LEVEL_LOCATION + ";",
                CALLS, "createLocation", "(L" + LEVEL + ";DDDD)L" + LEVEL_LOCATION + ";");
        transformer.registerMethodRedirect(FRAMEWORK_API, "registerLoginData",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/util/function/Supplier;)V",
                SUPPORT, "registerLoginData", "(Ljava/lang/Object;Ljava/util/function/Supplier;)V");
    }

    /** Helpers whose descriptors name host types, so they cannot be compiled into Retromod. */
    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, CALLS, null,
                "java/lang/Object", null);
        String builder = "L" + BUILDER + ";";

        MethodVisitor twoWay = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerPlayMessage",
                "(" + builder + "Ljava/lang/Class;)" + builder, null, null);
        twoWay.visitCode();
        twoWay.visitVarInsn(Opcodes.ALOAD, 0);
        twoWay.visitVarInsn(Opcodes.ALOAD, 1);
        twoWay.visitInsn(Opcodes.ACONST_NULL);
        emitRegister(twoWay);

        MethodVisitor directed = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerPlayMessage",
                "(" + builder + "Ljava/lang/Class;L" + DIRECTION + ";)" + builder, null, null);
        directed.visitCode();
        directed.visitVarInsn(Opcodes.ALOAD, 0);
        directed.visitVarInsn(Opcodes.ALOAD, 1);
        directed.visitVarInsn(Opcodes.ALOAD, 2);
        directed.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false);
        emitRegister(directed);

        // 0.8 returned the server player, or null on the client; 0.13 returns any player.
        MethodVisitor player = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getPlayer",
                "(L" + CONTEXT + ";)L" + SERVER_PLAYER + ";", null, null);
        player.visitCode();
        player.visitVarInsn(Opcodes.ALOAD, 0);
        player.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT, "getPlayer", "()Ljava/util/Optional;", false);
        player.visitInsn(Opcodes.ACONST_NULL);
        player.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        player.visitInsn(Opcodes.DUP);
        player.visitTypeInsn(Opcodes.INSTANCEOF, SERVER_PLAYER);
        Label notServer = new Label();
        player.visitJumpInsn(Opcodes.IFEQ, notServer);
        player.visitTypeInsn(Opcodes.CHECKCAST, SERVER_PLAYER);
        player.visitInsn(Opcodes.ARETURN);
        player.visitLabel(notServer);
        player.visitInsn(Opcodes.POP);
        player.visitInsn(Opcodes.ACONST_NULL);
        player.visitInsn(Opcodes.ARETURN);
        player.visitMaxs(0, 0);
        player.visitEnd();

        // 0.13 queues the task and returns nothing. Old handlers ignore the future.
        MethodVisitor execute = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "execute",
                "(L" + CONTEXT + ";Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", null, null);
        execute.visitCode();
        execute.visitVarInsn(Opcodes.ALOAD, 0);
        execute.visitVarInsn(Opcodes.ALOAD, 1);
        execute.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT, "execute", "(Ljava/lang/Runnable;)V", false);
        execute.visitInsn(Opcodes.ACONST_NULL);
        execute.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture", "completedFuture",
                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;", false);
        execute.visitInsn(Opcodes.ARETURN);
        execute.visitMaxs(0, 0);
        execute.visitEnd();

        // Locations are only ever built server-side to pick recipients, which 0.13 now types.
        MethodVisitor location = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createLocation",
                "(L" + LEVEL + ";DDDD)L" + LEVEL_LOCATION + ";", null, null);
        location.visitCode();
        location.visitVarInsn(Opcodes.ALOAD, 0);
        location.visitTypeInsn(Opcodes.CHECKCAST, SERVER_LEVEL);
        location.visitVarInsn(Opcodes.DLOAD, 1);
        location.visitVarInsn(Opcodes.DLOAD, 3);
        location.visitVarInsn(Opcodes.DLOAD, 5);
        location.visitVarInsn(Opcodes.DLOAD, 7);
        location.visitMethodInsn(Opcodes.INVOKESTATIC, LEVEL_LOCATION, "create",
                "(L" + SERVER_LEVEL + ";DDDD)L" + LEVEL_LOCATION + ";", false);
        location.visitInsn(Opcodes.ARETURN);
        location.visitMaxs(0, 0);
        location.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Stack: builder, class, direction name. Registers and returns the builder for chaining. */
    private static void emitRegister(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "registerPlayMessage",
                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
