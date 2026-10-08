/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps the 26.1 {@code Minecraft.getInstance().renderBuffers().bufferSource()} chain linking on
 * 26.2, where both links are gone.
 *
 * <p>26.2 moved the {@code RenderBuffers} owner from {@code Minecraft} to {@code GameRenderer}
 * (reached through the public {@code Minecraft.gameRenderer} field), and it deleted
 * {@code bufferSource()} and {@code crumblingBufferSource()} together with
 * {@code MultiBufferSource$BufferSource} itself (verified against the 26.1.2 and 26.2 jars). A mod
 * that grabs the shared buffer source for its renderer then fails with {@code NoSuchMethodError}
 * on the first frame.
 *
 * <p>{@code renderBuffers()} forwards to the real 26.2 object. The two buffer-source accessors
 * hand back one shared {@link RenderBufferSynthetics} stand-in, the same type the deleted
 * {@code BufferSource} is already redirected to, so the mod's geometry code runs. Drawing that
 * geometry is still staged in the stand-in: its {@code endBatch} warns once and drops the batch.
 * Registered from the 26.1 to 26.2 layer only.
 */
public final class LegacyRenderBuffersBridge {

    private LegacyRenderBuffersBridge() {}

    public static final String INTERNAL = "com/retromod/shim/common/embedded/RenderBuffersBridge";

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String RENDER_BUFFERS = "net/minecraft/client/renderer/RenderBuffers";
    private static final String OLD_BUFFER_SOURCE =
            "net/minecraft/client/renderer/MultiBufferSource$BufferSource";
    private static final String BYTE_BUFFER_BUILDER = "com/mojang/blaze3d/vertex/ByteBufferBuilder";

    private static final String L_RB = "L" + RENDER_BUFFERS + ";";
    private static final String L_BS = "L" + RenderBufferSynthetics.BS + ";";

    /** Matches the stand-in's own per-type capacity, which is vanilla's transient buffer size. */
    private static final int SHARED_CAPACITY = 786432;

    public static void register(RetromodTransformer t) {
        if (!t.getSyntheticClasses().containsKey(INTERNAL)) {
            t.registerSyntheticClass(INTERNAL, generate());
        }
        t.registerMethodRedirect(MINECRAFT, "renderBuffers", "()" + L_RB,
                INTERNAL, "renderBuffers", "(L" + MINECRAFT + ";)" + L_RB);
        for (String accessor : new String[]{"bufferSource", "crumblingBufferSource"}) {
            t.registerMethodRedirect(RENDER_BUFFERS, accessor, "()L" + OLD_BUFFER_SOURCE + ";",
                    INTERNAL, "sharedBufferSource", "(" + L_RB + ")" + L_BS);
        }
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "shared", L_BS, null, null).visitEnd();

        MethodVisitor rb = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "renderBuffers",
                "(L" + MINECRAFT + ";)" + L_RB, null, null);
        rb.visitCode();
        rb.visitVarInsn(ALOAD, 0);
        rb.visitFieldInsn(GETFIELD, MINECRAFT, "gameRenderer", "L" + GAME_RENDERER + ";");
        rb.visitMethodInsn(INVOKEVIRTUAL, GAME_RENDERER, "renderBuffers", "()" + L_RB, false);
        rb.visitInsn(ARETURN);
        rb.visitMaxs(0, 0);
        rb.visitEnd();

        // Render calls arrive on the render thread only, so a plain lazy field is enough.
        MethodVisitor bs = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sharedBufferSource",
                "(" + L_RB + ")" + L_BS, null, null);
        bs.visitCode();
        Label ready = new Label();
        bs.visitFieldInsn(GETSTATIC, INTERNAL, "shared", L_BS);
        bs.visitJumpInsn(IFNONNULL, ready);
        bs.visitTypeInsn(NEW, RenderBufferSynthetics.BS);
        bs.visitInsn(DUP);
        bs.visitTypeInsn(NEW, BYTE_BUFFER_BUILDER);
        bs.visitInsn(DUP);
        bs.visitLdcInsn(SHARED_CAPACITY);
        bs.visitMethodInsn(INVOKESPECIAL, BYTE_BUFFER_BUILDER, "<init>", "(I)V", false);
        bs.visitMethodInsn(INVOKESPECIAL, RenderBufferSynthetics.BS, "<init>",
                "(L" + BYTE_BUFFER_BUILDER + ";)V", false);
        bs.visitFieldInsn(PUTSTATIC, INTERNAL, "shared", L_BS);
        bs.visitLabel(ready);
        bs.visitFieldInsn(GETSTATIC, INTERNAL, "shared", L_BS);
        bs.visitInsn(ARETURN);
        bs.visitMaxs(0, 0);
        bs.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
