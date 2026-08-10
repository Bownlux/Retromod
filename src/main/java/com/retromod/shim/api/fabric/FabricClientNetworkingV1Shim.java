/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the removed Fabric client networking v1 raw-channel API (the
 * {@code Identifier}-keyed, raw-{@code FriendlyByteBuf} form from before the 1.20.5
 * typed-payload rewrite) onto 26.1's {@code CustomPacketPayload} API.
 *
 * <p>Mods pass their handler as a lambda whose {@code invokedynamic} hard-references
 * {@code PlayChannelHandler}, and ASM can't rewrite the SAM name a lambda implements,
 * so we inject a synthetic {@code PlayChannelHandler} and redirect the old name onto it.
 * The removed static entrypoints ({@code registerGlobalReceiver}, {@code registerReceiver},
 * {@code send}, {@code canSend}) go to {@link com.retromod.shim.api.fabric.embedded.ClientPlayNetworkingV1Bridge},
 * which translates raw/typed reflectively at runtime. {@code getSender()} is untouched: it
 * still exists in 26.1.
 *
 * <p>The synthetic SAM's {@code receive} uses Mojang param types so it matches the lambda
 * after the intermediary to Mojang remap, which rewrites the invokedynamic descriptors in
 * the same pass. Redirect keys match the call-site descriptor after the outer ClassRemapper
 * runs, so the {@code Identifier}/{@code FriendlyByteBuf} params can be intermediary (CLI) or
 * Mojang (runtime); both variants are registered.
 *
 * <p>Contracts checked against {@code minecraft-26.1.2-client} + {@code fabric-api-0.145.4+26.1.2};
 * the bridge fails soft on any reflective miss. See {@code docs-dev/cpn-v1-bridge-design.md}.
 */
public class FabricClientNetworkingV1Shim implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    // Class still exists in 26.1; only its removed methods are redirected, not the class itself.
    private static final String OLD_CPN = "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking";
    private static final String OLD_SAM = OLD_CPN + "$PlayChannelHandler";

    private static final String NEW_SAM = "com/retromod/generated/legacynet/LegacyPlayChannelHandler";
    private static final String BRIDGE  = "com/retromod/shim/api/fabric/embedded/ClientPlayNetworkingV1Bridge";
    private static final String L_NEW_SAM = "L" + NEW_SAM + ";";

    // Redirect-key MC param types: intermediary (CLI) and Mojang (runtime).
    private static final String ID_INT  = "Lnet/minecraft/class_2960;";
    private static final String ID_MOJ  = "Lnet/minecraft/resources/Identifier;";
    private static final String BUF_INT = "Lnet/minecraft/class_2540;";
    private static final String BUF_MOJ = "Lnet/minecraft/network/FriendlyByteBuf;";

    // Synthetic SAM receive() param types follow the runtime namespace.
    private static final String MC_INT        = "Lnet/minecraft/class_310;";
    private static final String LISTENER_INT  = "Lnet/minecraft/class_634;";
    private static final String MC_MOJ       = "Lnet/minecraft/client/Minecraft;";
    private static final String LISTENER_MOJ = "Lnet/minecraft/client/multiplayer/ClientPacketListener;";
    private static final String SENDER       = "Lnet/fabricmc/fabric/api/networking/v1/PacketSender;";

    // Bridge descriptors take Object params so one descriptor serves every intermediary/Mojang variant.
    private static final String D_REGISTER = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String D_SEND     = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String D_CANSEND  = "(Ljava/lang/Object;)Z";

    @Override public String getShimName() { return "Fabric ClientPlayNetworking v1 (raw-channel) bridge"; }
    @Override public String getSourceVersion() { return "0.50.0"; }
    @Override public String getTargetVersion() { return "0.100.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        String host = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        if (com.retromod.core.RetromodVersion.compareMcVersions(host, "1.20.5") < 0) {
            LOGGER.debug("[Retromod] client-networking v1 bridge skipped (host {} < 1.20.5)", host);
            return;
        }
        boolean mojRuntime = com.retromod.core.RetromodVersion.isUnobfuscatedTarget(host);

        // Embed the bridge so the redirected static calls resolve.
        transformer.registerEmbeddedShim(BRIDGE.replace('/', '.'));

        transformer.registerSyntheticClass(NEW_SAM, generateSamInterface(mojRuntime));
        transformer.registerClassRedirect(OLD_SAM, NEW_SAM);

        // The PlayChannelHandler param is L_NEW_SAM on both paths (class redirect);
        // the Identifier/Buf params vary, so register every combination.
        for (String idT : new String[]{ID_INT, ID_MOJ}) {
            redirect(transformer, "registerGlobalReceiver",
                    "(" + idT + L_NEW_SAM + ")Z", "registerGlobalReceiver", D_REGISTER);
            redirect(transformer, "registerReceiver",
                    "(" + idT + L_NEW_SAM + ")Z", "registerReceiver", D_REGISTER);
            redirect(transformer, "canSend", "(" + idT + ")Z", "canSend", D_CANSEND);
            for (String bufT : new String[]{BUF_INT, BUF_MOJ}) {
                redirect(transformer, "send", "(" + idT + bufT + ")V", "send", D_SEND);
            }
        }

        LOGGER.debug("Installed the Fabric client networking v1 bridge. "
                + "An in-game verification run is still pending.");
    }

    private static void redirect(RetromodTransformer t, String oldName, String oldDesc,
                                 String bridgeName, String bridgeDesc) {
        // devirtualize=false: source is already INVOKESTATIC.
        t.registerMethodRedirect(OLD_CPN, oldName, oldDesc, BRIDGE, bridgeName, bridgeDesc, false);
    }

    /** Functional interface with the old 4-arg {@code receive} SAM. */
    private static byte[] generateSamInterface(boolean mojRuntime) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_SAM, null, "java/lang/Object", null);
        String receiveDesc = mojRuntime
                ? "(" + MC_MOJ + LISTENER_MOJ + BUF_MOJ + SENDER + ")V"
                : "(" + MC_INT + LISTENER_INT + BUF_INT + SENDER + ")V";
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "receive", receiveDesc, null, null)
                .visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
