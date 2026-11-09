/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.polyfill.minecraft.RetroRenderTargets;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Bridges 1.21.1 render calls that 1.21.5 to 1.21.9 removed, each from the shim of the version that removed it. */
public final class LegacyRenderCallBridge {

    private LegacyRenderCallBridge() {}

    static final String RENDER_TARGET = "com/mojang/blaze3d/pipeline/RenderTarget";
    static final String DYNAMIC_TEXTURE = "net/minecraft/client/renderer/texture/DynamicTexture";
    static final String NATIVE_IMAGE_DESC = "Lcom/mojang/blaze3d/platform/NativeImage;";
    static final String SUPPLIER_DESC = "Ljava/util/function/Supplier;";
    static final String FONT = "net/minecraft/client/gui/Font";
    static final String TARGETS = "com/retromod/polyfill/minecraft/RetroRenderTargets";
    static final String TEXTURES = "com/retromod/generated/LegacyDynamicTexture";
    static final String CAMERA = "com/retromod/generated/LegacyCameraOrientation";
    static final String TEXTURE_LABEL = "Retromod legacy dynamic texture";

    /** 1.21.5: RenderTarget.bindWrite(boolean), kept for the main target, and unbindWrite(). */
    public static void registerMainTargetBind(RetromodTransformer t) {
        // Fabric before 26.1 calls RenderTarget by an intermediary name, so its shim registers this from 26.1.
        SyntheticEmbedder.registerClassResource(t, TARGETS, RetroRenderTargets.class);
        t.registerMethodRedirect(RENDER_TARGET, "bindWrite", "(Z)V",
                TARGETS, "bindWrite", "(Ljava/lang/Object;Z)V", true);
        // Unbinding restored the default target, which is where draws go anyway since 1.21.5.
        t.registerRemovedMethodNeutralize(RENDER_TARGET, "unbindWrite", "()V");
    }

    /** 1.21.5: the DynamicTexture constructors that take no debug label. */
    public static void registerDynamicTextureLabels(RetromodTransformer t) {
        t.registerSyntheticClass(TEXTURES, generateTextureLabels());
        // new DynamicTexture(image) and a subclass's super(image) get a label pushed under the image.
        t.registerSuperConstructorAdapter(DYNAMIC_TEXTURE, "(" + NATIVE_IMAGE_DESC + ")V",
                "(" + SUPPLIER_DESC + NATIVE_IMAGE_DESC + ")V", mv -> {
                    mv.visitMethodInsn(INVOKESTATIC, TEXTURES, "label", "()" + SUPPLIER_DESC, false);
                    mv.visitInsn(SWAP);
                });
        // No stack swap reaches under the three arguments of the blank form, so a factory builds it.
        // A subclass calling super(width, height, clear) is not covered.
        t.registerConstructorRedirect(DYNAMIC_TEXTURE, "(IIZ)V",
                TEXTURES, "blank", "(IIZ)L" + DYNAMIC_TEXTURE + ";");
    }

    /** 1.21.6: Font.drawInBatch returns nothing, bridged on hosts up to 1.21.11. */
    public static void registerVoidDrawInBatch(RetromodTransformer t) {
        // 26.1 also widened the matrix to Matrix4fc and 26.2 removed drawInBatch, which needs a port.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0) return;
        String tail = "FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;L"
                + FONT + "$DisplayMode;II)";
        for (String text : new String[]{"Ljava/lang/String;", "Lnet/minecraft/network/chat/Component;",
                "Lnet/minecraft/util/FormattedCharSequence;"}) {
            // The old result was the end x. Almost every caller drops it, so 0 stands in.
            t.registerConvertingRedirect(FONT, "drawInBatch", "(" + text + tail + "I",
                    FONT, "drawInBatch", "(" + text + tail + "V", 0, ICONST_0);
        }
    }

    /** 1.21.9: EntityRenderDispatcher.cameraOrientation(), bridged on hosts up to 1.21.11. */
    public static void registerCameraOrientation(RetromodTransformer t) {
        // 26.1 and newer get the same redirect from IsOverloadBridgeSynthetic, so the two never meet.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0) return;
        // Its own class, because the 26.x helper class also carries methods that name 26.1-only members.
        t.registerSyntheticClass(CAMERA, generateCameraOrientation());
        IsOverloadBridgeSynthetic.registerCameraOrientation(t, CAMERA);
    }

    /** The label supplier and the blank-texture factory for {@link #registerDynamicTextureLabels}. */
    static byte[] generateTextureLabels() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, TEXTURES, null, "java/lang/Object",
                new String[]{"java/util/function/Supplier"});

        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // get() is the label a GPU debugger shows; old mods never named their textures.
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitLdcInsn(TEXTURE_LABEL);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        MethodVisitor label = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "label", "()" + SUPPLIER_DESC, null, null);
        label.visitCode();
        label.visitTypeInsn(NEW, TEXTURES);
        label.visitInsn(DUP);
        label.visitMethodInsn(INVOKESPECIAL, TEXTURES, "<init>", "()V", false);
        label.visitInsn(ARETURN);
        label.visitMaxs(0, 0);
        label.visitEnd();

        // blank(width, height, clear) is new DynamicTexture(label, width, height, clear).
        MethodVisitor blank = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blank",
                "(IIZ)L" + DYNAMIC_TEXTURE + ";", null, null);
        blank.visitCode();
        blank.visitTypeInsn(NEW, DYNAMIC_TEXTURE);
        blank.visitInsn(DUP);
        blank.visitLdcInsn(TEXTURE_LABEL);
        blank.visitVarInsn(ILOAD, 0);
        blank.visitVarInsn(ILOAD, 1);
        blank.visitVarInsn(ILOAD, 2);
        blank.visitMethodInsn(INVOKESPECIAL, DYNAMIC_TEXTURE, "<init>", "(Ljava/lang/String;IIZ)V", false);
        blank.visitInsn(ARETURN);
        blank.visitMaxs(0, 0);
        blank.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The cameraOrientation helper alone, emitted exactly as the 26.x helper class emits it. */
    static byte[] generateCameraOrientation() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CAMERA, null, "java/lang/Object", null);
        IsOverloadBridgeSynthetic.emitCameraOrientation(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
    }
}
