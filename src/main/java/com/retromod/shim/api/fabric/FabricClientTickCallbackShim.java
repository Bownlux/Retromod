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
 * Bridges the removed Fabric v0 {@code ClientTickCallback} onto the modern
 * {@code ClientTickEvents.END_CLIENT_TICK} (#129, Chat Bubbles).
 *
 * <p>{@code net.fabricmc.fabric.api.event.client.ClientTickCallback} (SAM {@code tick(MinecraftClient)},
 * static {@code EVENT}) was deleted from Fabric API around 1.16, so a 1.15-era mod referencing it dies
 * with {@code NoClassDefFoundError} while its entrypoint class is even being loaded. We embed a
 * synthetic interface (kept under {@code com/retromod/...} so it can't split-package with the loader
 * module) that preserves the {@code tick} SAM and the {@code EVENT} field; {@code EVENT} is wired in
 * {@code <clinit>} to an array-backed event whose invoker fires once per client tick via
 * {@link com.retromod.shim.api.fabric.embedded.ClientTickCallbackBridge}.
 *
 * <p>Gated to 26.1+ ({@code isUnobfuscatedTarget}): the SAM descriptor uses the Mojang name
 * {@code net/minecraft/client/Minecraft} (intermediary {@code class_310}), which only matches the
 * remapped mod on an unobfuscated host. On pre-26.1 hosts {@code ClientTickCallback} falls under the
 * broader legacy-Fabric limitation (#55).
 */
public class FabricClientTickCallbackShim implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String OLD    = "net/fabricmc/fabric/api/event/client/ClientTickCallback";
    private static final String SYNTH  = "com/retromod/generated/legacytick/ClientTickCallback";
    private static final String BRIDGE = "com/retromod/shim/api/fabric/embedded/ClientTickCallbackBridge";

    private static final String EVENT   = "net/fabricmc/fabric/api/event/Event";
    private static final String L_EVENT = "L" + EVENT + ";";
    /** Remapped SAM: class_310 -> net/minecraft/client/Minecraft. */
    private static final String SAM_DESC = "(Lnet/minecraft/client/Minecraft;)V";

    @Override public String getShimName() { return "Fabric ClientTickCallback → ClientTickEvents bridge"; }
    @Override public String getSourceVersion() { return "0.3.0"; }
    @Override public String getTargetVersion() { return "0.145.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            LOGGER.debug("[Retromod] ClientTickCallback bridge skipped (host {} < 26.1 - old API pre-remap names)",
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
            return;
        }

        transformer.registerEmbeddedShim(BRIDGE.replace('/', '.'));
        transformer.registerSyntheticClass(SYNTH, generateInterface());
        transformer.registerClassRedirect(OLD, SYNTH);

        LOGGER.debug("Installed the Fabric client tick callback bridge. "
                + "An in-game verification run is still pending.");
    }

    static byte[] generateInterface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                SYNTH, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "EVENT", L_EVENT, null, null).visitEnd();

        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "tick", SAM_DESC, null, null)
                .visitEnd();

        // static { EVENT = (Event) ClientTickCallbackBridge.installEvent(ClientTickCallback.class); }
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(org.objectweb.asm.Type.getObjectType(SYNTH));
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "installEvent",
                "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        clinit.visitTypeInsn(Opcodes.CHECKCAST, EVENT);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, SYNTH, "EVENT", L_EVENT);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
