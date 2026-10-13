/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyArmorLayers;
import com.retromod.shim.common.embedded.LegacyRendererTextures;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Old mob renderers on 26.1 and newer: the armor layer they add and the state methods they lack.
 *
 * <p>Up to 1.21.1 a mob renderer added armor with {@code new HumanoidArmorLayer(this, inner, outer)},
 * which 1.20 extended with the model manager. 26.1 takes an armor model set and the equipment
 * renderer, so the old constructor failed while the game built its entity renderers, which stops
 * the client from starting. The old call builds the current layer from the two models; see
 * {@link LegacyArmorLayers}. The renderer's own missing state methods are added by
 * {@link LegacyLivingRendererAdapter}, whose texture helper is registered here.
 */
public final class LegacyArmorLayerBridge {

    static final String CALLS = "com/retromod/generated/LegacyArmorLayerCalls";
    static final String HELPER = "com/retromod/shim/common/embedded/LegacyArmorLayers";
    private static final String LAYER = "net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer";
    private static final String PARENT = "Lnet/minecraft/client/renderer/entity/RenderLayerParent;";
    private static final String MODEL = "Lnet/minecraft/client/model/HumanoidModel;";
    private static final String MANAGER = "Lnet/minecraft/client/resources/model/ModelManager;";
    private static final String OBJECT = "Ljava/lang/Object;";

    private LegacyArmorLayerBridge() {}

    public static void register(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, LegacyLivingRendererAdapter.TEXTURES, LegacyRendererTextures.class);
        ClassNode layer = ClassResourceInspector.read(LAYER);
        if (layer == null || layer.methods.stream().anyMatch(m -> m.name.equals("<init>")
                && m.desc.equals("(" + PARENT + MODEL + MODEL + ")V"))) {
            return;
        }
        registerLayerRedirects(transformer);
    }

    /** Unconditional registration of the constructor redirects, for transform-shape tests. */
    static void registerLayerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyArmorLayers.class);
        transformer.registerSyntheticClass(CALLS, generateCalls());
        transformer.registerConstructorRedirect(LAYER, "(" + PARENT + MODEL + MODEL + ")V",
                CALLS, "armorLayer", "(" + PARENT + MODEL + MODEL + ")L" + LAYER + ";");
        transformer.registerConstructorRedirect(LAYER, "(" + PARENT + MODEL + MODEL + MANAGER + ")V",
                CALLS, "armorLayer", "(" + PARENT + MODEL + MODEL + MANAGER + ")L" + LAYER + ";");
    }

    /** Typed wrappers; the model manager of 1.20's form is no longer needed. */
    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        for (String extra : new String[] {"", MANAGER}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "armorLayer",
                    "(" + PARENT + MODEL + MODEL + extra + ")L" + LAYER + ";", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, HELPER, "create", "(" + OBJECT + OBJECT + OBJECT + ")" + OBJECT, false);
            mv.visitTypeInsn(CHECKCAST, LAYER);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
