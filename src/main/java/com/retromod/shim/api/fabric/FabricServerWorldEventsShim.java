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
 * Bridges the removed Fabric {@code ServerWorldEvents} lifecycle API onto {@code ServerLevelEvents}.
 *
 * <p>26.1 renamed the holder and the SAM methods ({@code onWorldLoad}/{@code onWorldUnload} to
 * {@code onLevelLoad}/{@code onLevelUnload}). The SAM rename makes a plain class redirect emit a
 * lambda against the wrong method, so we keep the old SAM names on synthetic interfaces plus a
 * synthetic holder, wired to the live {@code ServerLevelEvents} fields by reflection
 * ({@link com.retromod.shim.api.fabric.embedded.ServerWorldEventsBridge}). The {@code ServerWorld}
 * to {@code ServerLevel} param change is handled by the harvest.</p>
 */
public class FabricServerWorldEventsShim implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String GEN = "com/retromod/generated/legacylifecycle/";
    private static final String HOLDER = GEN + "ServerWorldEvents";
    private static final String LOAD   = GEN + "ServerWorldLoad";
    private static final String UNLOAD = GEN + "ServerWorldUnload";
    private static final String BRIDGE = "com/retromod/shim/api/fabric/embedded/ServerWorldEventsBridge";

    private static final String OLD = "net/fabricmc/fabric/api/event/lifecycle/v1/ServerWorldEvents";

    private static final String EVENT   = "net/fabricmc/fabric/api/event/Event";
    private static final String L_EVENT = "L" + EVENT + ";";
    // ServerLevel is the harvested param.
    private static final String SAM_DESC =
            "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;)V";

    @Override public String getShimName() { return "Fabric ServerWorldEvents lifecycle bridge"; }
    @Override public String getSourceVersion() { return "0.40.0"; }
    @Override public String getTargetVersion() { return "0.145.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 26.1+ only (#9): pre-26.1 still ships ServerWorldEvents, and the synthetic
        // SAM's Mojang-named ServerLevel won't resolve on an intermediary runtime.
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            LOGGER.debug("[Retromod] ServerWorldEvents bridge skipped (host {} < 26.1 - old API still present)",
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
            return;
        }

        transformer.registerEmbeddedShim(BRIDGE.replace('/', '.'));

        transformer.registerSyntheticClass(HOLDER, generateHolder());
        transformer.registerSyntheticClass(LOAD, generateLoad());
        transformer.registerSyntheticClass(UNLOAD, generateUnload());

        transformer.registerClassRedirect(OLD, HOLDER);
        transformer.registerClassRedirect(OLD + "$Load", LOAD);
        transformer.registerClassRedirect(OLD + "$Unload", UNLOAD);

        LOGGER.debug("Installed the Fabric server world event bridge. "
                + "An in-game verification run is still pending.");
    }

    /** Holder with {@code LOAD}/{@code UNLOAD} fields wired in {@code <clinit>}. */
    static byte[] generateHolder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HOLDER, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "LOAD", L_EVENT, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "UNLOAD", L_EVENT, null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "installLoad", "()Ljava/lang/Object;", false);
        clinit.visitTypeInsn(Opcodes.CHECKCAST, EVENT);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, HOLDER, "LOAD", L_EVENT);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "installUnload", "()Ljava/lang/Object;", false);
        clinit.visitTypeInsn(Opcodes.CHECKCAST, EVENT);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, HOLDER, "UNLOAD", L_EVENT);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Synthetic {@code ServerWorldEvents$Load}: SAM {@code onWorldLoad(MinecraftServer, ServerLevel)}. */
    static byte[] generateLoad() {
        return samInterface(LOAD, "onWorldLoad");
    }

    /** Synthetic {@code ServerWorldEvents$Unload}: SAM {@code onWorldUnload(MinecraftServer, ServerLevel)}. */
    static byte[] generateUnload() {
        return samInterface(UNLOAD, "onWorldUnload");
    }

    private static byte[] samInterface(String internalName, String samName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, samName, SAM_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
