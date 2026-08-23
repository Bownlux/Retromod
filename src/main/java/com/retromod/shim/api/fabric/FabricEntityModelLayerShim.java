/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the removed Fabric {@code EntityModelLayerRegistry} onto the surviving
 * {@code ModelLayerRegistry}. The provider SAM was renamed ({@code createModelData} to
 * {@code createLayerDefinition}) but the return type is unchanged, so the harvest fixes
 * the lambda body. Synthetic {@code EntityModelLayerRegistry} holder and
 * {@code TexturedModelDataProvider} SAM keep the old names; the holder's
 * {@code registerModelLayer} forwards to {@code EntityModelLayerRegistryBridge}, which
 * wraps the old provider as a new one and registers it.
 *
 * <p>Authored against fabric-api-0.141.1 / 0.145.4+26.1.2; needs a 26.1 launch with a
 * custom-entity-model mod to runtime-verify.</p>
 */
public class FabricEntityModelLayerShim implements AuxiliaryVersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String GEN = "com/retromod/generated/legacymodellayer/";
    private static final String HOLDER   = GEN + "EntityModelLayerRegistry";
    private static final String PROVIDER = GEN + "TexturedModelDataProvider";
    private static final String BRIDGE = "com/retromod/shim/api/fabric/embedded/EntityModelLayerRegistryBridge";

    private static final String OLD = "net/fabricmc/fabric/api/client/rendering/v1/EntityModelLayerRegistry";

    private static final String MODEL_LAYER_LOCATION = "net/minecraft/client/model/geom/ModelLayerLocation";
    private static final String LAYER_DEFINITION = "net/minecraft/client/model/geom/builders/LayerDefinition";
    private static final String REGISTER_DESC = "(L" + MODEL_LAYER_LOCATION + ";L" + PROVIDER + ";)V";
    private static final String SAM_DESC = "()L" + LAYER_DEFINITION + ";";

    @Override public String getShimName() { return "Fabric EntityModelLayerRegistry bridge"; }
    @Override public String getSourceVersion() { return "0.40.0"; }
    @Override public String getTargetVersion() { return "0.145.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 26.1+ hosts only (#9): pre-26.1 the old API still exists and the synthetics name
        // Mojang types that don't resolve on an intermediary runtime.
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            LOGGER.debug("[Retromod] EntityModelLayerRegistry bridge skipped (host {} < 26.1 - old API still present)",
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
            return;
        }

        transformer.registerEmbeddedShim(BRIDGE.replace('/', '.'));

        transformer.registerSyntheticClass(HOLDER, generateHolder());
        transformer.registerSyntheticClass(PROVIDER, generateProvider());

        transformer.registerClassRedirect(OLD, HOLDER);
        transformer.registerClassRedirect(OLD + "$TexturedModelDataProvider", PROVIDER);

        LOGGER.debug("Installed the Fabric entity model layer bridge. "
                + "An in-game verification run is still pending.");
    }

    /** Holder whose static {@code registerModelLayer} forwards to the bridge. */
    static byte[] generateHolder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HOLDER, null, "java/lang/Object", null);

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "registerModelLayer", REGISTER_DESC, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "registerModelLayer",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Synthetic {@code TexturedModelDataProvider}: SAM {@code createModelData() : LayerDefinition}. */
    static byte[] generateProvider() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                PROVIDER, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "createModelData", SAM_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
