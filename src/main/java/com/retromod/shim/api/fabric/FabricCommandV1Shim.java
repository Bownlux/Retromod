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
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the removed Fabric command v1 {@code CommandRegistrationCallback} onto the
 * surviving {@code command/v2} callback.
 *
 * <p>A plain class redirect onto v2 would break the mod's lambda: v1's SAM is the 2-arg
 * {@code register(CommandDispatcher, boolean)}, v2's is 3-arg, and the lambda's
 * {@code invokedynamic} hard-codes the v1 descriptor. So we keep the v1 SAM alive with a
 * synthetic interface (2-arg {@code register} + static {@code EVENT}, whose {@code <clinit>}
 * fills {@code EVENT} from the bridge) and redirect the old class onto it. The bridge's
 * {@code installEvent} builds a Fabric {@code Event} and forwards each command build from
 * the live v2 {@code EVENT} onto the v1 handlers, mapping the v2 {@code CommandSelection}
 * down to the v1 {@code dedicated} boolean. The v1 lambda body only touches the brigadier
 * {@code CommandDispatcher} and a boolean, so nothing inside the handler needs adapting.
 *
 * <p>The synthetic's {@code register} declares the brigadier {@code CommandDispatcher},
 * which has the same name on intermediary (CLI/audit) and Mojang (runtime) paths, so one
 * descriptor serves both.
 *
 * <p>Authored against {@code fabric-api-0.145.4+26.1.2}; not yet runtime-verified.
 */
public class FabricCommandV1Shim implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String OLD_V1  = "net/fabricmc/fabric/api/command/v1/CommandRegistrationCallback";
    private static final String NEW_SAM = "com/retromod/generated/legacycmd/CommandRegistrationCallbackV1";
    private static final String BRIDGE  = "com/retromod/shim/api/fabric/embedded/CommandRegistrationV1Bridge";

    private static final String EVENT   = "net/fabricmc/fabric/api/event/Event";
    private static final String L_EVENT = "L" + EVENT + ";";
    private static final String SAM_DESC = "(Lcom/mojang/brigadier/CommandDispatcher;Z)V";

    @Override public String getShimName() { return "Fabric command v1 (CommandRegistrationCallback) bridge"; }
    @Override public String getSourceVersion() { return "0.25.0"; }
    @Override public String getTargetVersion() { return "0.100.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Command v1 is absent on every supported host, while the bridge only
        // depends on stable Brigadier and Fabric event types.

        // Embed first so the synthetic static initializer can resolve the bridge.
        transformer.registerEmbeddedShim(BRIDGE.replace('/', '.'));

        transformer.registerSyntheticClass(NEW_SAM, generateSamInterface());
        transformer.registerClassRedirect(OLD_V1, NEW_SAM);

        LOGGER.debug("Installed the Fabric command v1 callback bridge. "
                + "An in-game verification run is still pending.");
    }

    /** Synthetic v1 {@code CommandRegistrationCallback}: 2-arg {@code register} SAM, static {@code EVENT}, and a {@code <clinit>} that fills {@code EVENT} from the bridge. */
    static byte[] generateSamInterface() { // package-private for FabricCommandV1ShimTest
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_SAM, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "EVENT", L_EVENT, null, null).visitEnd();

        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "register", SAM_DESC, null, null)
                .visitEnd();

        // static { EVENT = (Event) CommandRegistrationV1Bridge.installEvent(NEW_SAM.class); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(NEW_SAM));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "installEvent",
                "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, EVENT);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, NEW_SAM, "EVENT", L_EVENT);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
