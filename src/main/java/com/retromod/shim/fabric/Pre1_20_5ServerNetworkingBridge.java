/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Links the channel-based server networking calls that Fabric API removed with Minecraft 1.20.5,
 * for intermediary-namespace Fabric mods on pre-26.1 hosts.
 *
 * <p>{@code FabricNetworkingPolyfill} already moves {@code ServerPlayNetworking.PlayChannelHandler}
 * to its own handler interface and sends {@code registerGlobalReceiver} and {@code send} to
 * {@code FabricNetworkingBridge}, but only for Mojang-named descriptors. On an intermediary host
 * the call kept its {@code class_2960} descriptor, matched nothing, and the first networking class
 * a mod initialized died with {@code NoSuchMethodError}. That ended the mod's whole initializer.
 *
 * <p>This registers the intermediary descriptors for the same bridge. It also retypes the mod's
 * handler lambdas to the bridge interface's {@code Object} parameters, because a lambda built
 * with the old parameter types does not implement that method. The bridge itself is unchanged:
 * where it cannot reach the typed payload API it logs the channel and leaves it inactive, so the
 * mod starts but its own packets are not delivered.
 */
public final class Pre1_20_5ServerNetworkingBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String SERVER_NETWORKING = "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking";
    static final String OLD_HANDLER = SERVER_NETWORKING + "$PlayChannelHandler";
    static final String HANDLER = "com/retromod/polyfill/fabric/embedded/ServerPlayChannelHandler";
    static final String BRIDGE = "com/retromod/polyfill/fabric/embedded/FabricNetworkingBridge";
    private static final String ID = "Lnet/minecraft/class_2960;";
    private static final String PLAYER = "Lnet/minecraft/class_3222;";
    private static final String BUF = "Lnet/minecraft/class_2540;";
    private static final String OBJECT = "Ljava/lang/Object;";

    static final String OLD_REGISTER = "(" + ID + "L" + OLD_HANDLER + ";)Z";
    static final String OLD_SEND = "(" + PLAYER + ID + BUF + ")V";
    static final String OLD_RECEIVE = "(Lnet/minecraft/server/MinecraftServer;" + PLAYER
            + "Lnet/minecraft/class_3244;" + BUF + "Lnet/fabricmc/fabric/api/networking/v1/PacketSender;)V";
    static final String ERASED_RECEIVE = "(" + OBJECT.repeat(5) + ")V";

    private Pre1_20_5ServerNetworkingBridge() {}

    /** Registers the redirects only when the installed Fabric API lacks the channel-based form. */
    public static void register(RetromodTransformer transformer) {
        ClassNode networking = ClassResourceInspector.read(SERVER_NETWORKING);
        if (networking == null || hasMethod(networking, "registerGlobalReceiver", OLD_REGISTER)) {
            LOGGER.debug("Fabric API still has channel-based server networking");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Linked channel-based server networking calls to the networking bridge");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        // The handler type is moved by a class redirect, which may or may not have been applied
        // to the call descriptor by the time the method redirect is looked up, so both are keyed.
        for (String handler : new String[] {HANDLER, OLD_HANDLER}) {
            transformer.registerMethodRedirect(SERVER_NETWORKING, "registerGlobalReceiver",
                    "(" + ID + "L" + handler + ";)Z",
                    BRIDGE, "registerServerGlobalReceiver", "(" + OBJECT + OBJECT + ")Z");
        }
        transformer.registerMethodRedirect(SERVER_NETWORKING, "send", OLD_SEND,
                BRIDGE, "serverSend", "(" + OBJECT.repeat(3) + ")V");
        transformer.registerLambdaSamRetype(HANDLER, "receive", OLD_RECEIVE, ERASED_RECEIVE);
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }
}
