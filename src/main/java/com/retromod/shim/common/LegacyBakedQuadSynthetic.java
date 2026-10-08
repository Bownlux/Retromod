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
 * 26.1 moved a baked quad's material out of the {@code BakedQuad} record into
 * {@code BakedQuad.materialInfo()}, and replaced {@code VertexConsumer.putBulkData} with
 * {@code putBakedQuad(Pose, BakedQuad, QuadInstance)}, where a reusable {@code QuadInstance}
 * carries the color, light, and overlay. A 1.21.x mod that draws its own item or block quads
 * calls both, so every such quad loop died with {@code NoSuchMethodError} on 26.1 and newer.
 *
 * <p>The quad accessors hop through {@code materialInfo()}. The two {@code putBulkData} shapes
 * fill a fresh {@code QuadInstance} the way 1.21.x filled the vertices: the color is the given
 * RGBA, scaled per vertex by the brightness array when there is one, and the light is per vertex
 * or shared. {@code putBakedQuad} adds the quad's own light emission, as {@code putBulkData} did.
 * {@code BakedQuad.shade()} is not bridged, because 26.3 replaced it with a direction override.
 *
 * <p>Each forwarder takes the old receiver as its first argument, so the transformer turns the
 * call into an {@code INVOKESTATIC} and a {@code @Redirect} handler on the old call keeps its
 * shape. The class references 26.x types Retromod cannot see at build time, so it is generated
 * and embedded per mod. Registered from the 1.21.11 to 26.1 shim, so only 26.1 and newer hosts
 * get it.
 */
public final class LegacyBakedQuadSynthetic {

    private LegacyBakedQuadSynthetic() {}

    public static final String INTERNAL = "com/retromod/generated/LegacyBakedQuad";

    static final String QUAD = "net/minecraft/client/resources/model/geometry/BakedQuad";
    private static final String OLD_QUAD = "net/minecraft/client/renderer/block/model/BakedQuad";
    private static final String MATERIAL = QUAD + "$MaterialInfo";
    private static final String CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String POSE = "com/mojang/blaze3d/vertex/PoseStack$Pose";
    private static final String QUAD_INSTANCE = "com/mojang/blaze3d/vertex/QuadInstance";
    private static final String ARGB = "net/minecraft/util/ARGB";
    private static final String SPRITE_DESC = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";

    /** {old accessor, return descriptor}: the material members every 26.x host still declares. */
    private static final String[][] MATERIAL_ACCESSORS = {
        {"isTinted", "Z"},
        {"tintIndex", "I"},
        {"lightEmission", "I"},
        {"sprite", SPRITE_DESC},
    };

    /** The two 1.21.x {@code putBulkData} tails after {@code (Pose, BakedQuad}. */
    static final String UNIFORM_TAIL = "FFFFII)V";
    static final String PER_VERTEX_TAIL = "[FFFFF[II)V";

    public static byte[] generate() {
        // Frame merges only meet identical locals, so the writer never needs the MC hierarchy.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);
        for (String[] accessor : MATERIAL_ACCESSORS) {
            materialAccessor(cw, accessor[0], accessor[1]);
        }
        uniformPutBulkData(cw);
        perVertexPutBulkData(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    public static void register(RetromodTransformer t) {
        if (!t.getSyntheticClasses().containsKey(INTERNAL)) {
            t.registerSyntheticClass(INTERNAL, generate());
        }
        // A Fabric mod reaches the new package through the intermediary map; a Mojang-named mod
        // still spells the 1.21.x package until the class move runs, so both owners are covered.
        for (String quad : new String[]{QUAD, OLD_QUAD}) {
            for (String[] accessor : MATERIAL_ACCESSORS) {
                t.registerMethodRedirect(quad, accessor[0], "()" + accessor[1],
                        INTERNAL, accessor[0], "(L" + QUAD + ";)" + accessor[1]);
            }
            String head = "(L" + POSE + ";L" + quad + ";";
            String forwarderHead = "(L" + CONSUMER + ";L" + POSE + ";L" + QUAD + ";";
            t.registerMethodRedirect(CONSUMER, "putBulkData", head + UNIFORM_TAIL,
                    INTERNAL, "putBulkData", forwarderHead + UNIFORM_TAIL);
            t.registerMethodRedirect(CONSUMER, "putBulkData", head + PER_VERTEX_TAIL,
                    INTERNAL, "putBulkData", forwarderHead + PER_VERTEX_TAIL);
        }
    }

    /** {@code static T name(BakedQuad quad) { return quad.materialInfo().name(); }} */
    private static void materialAccessor(ClassWriter cw, String name, String returnDesc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + QUAD + ";)" + returnDesc,
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, QUAD, "materialInfo", "()L" + MATERIAL + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, MATERIAL, name, "()" + returnDesc, false);
        mv.visitInsn(returnDesc.equals("I") || returnDesc.equals("Z") ? IRETURN : ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** putBulkData(consumer, pose, quad, r, g, b, a, light, overlay): one color and light for all four vertices. */
    private static void uniformPutBulkData(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "putBulkData",
                "(L" + CONSUMER + ";L" + POSE + ";L" + QUAD + ";" + UNIFORM_TAIL, null, null);
        mv.visitCode();
        // slots: 0 consumer, 1 pose, 2 quad, 3 r, 4 g, 5 b, 6 a, 7 light, 8 overlay, 9 instance
        newQuadInstance(mv, 9);
        mv.visitVarInsn(ALOAD, 9);
        argb(mv, 6, 3, 4, 5, -1);
        mv.visitMethodInsn(INVOKEVIRTUAL, QUAD_INSTANCE, "setColor", "(I)V", false);
        mv.visitVarInsn(ALOAD, 9);
        mv.visitVarInsn(ILOAD, 7);
        mv.visitMethodInsn(INVOKEVIRTUAL, QUAD_INSTANCE, "setLightCoords", "(I)V", false);
        finishQuad(mv, 9, 8);
    }

    /**
     * putBulkData(consumer, pose, quad, brightness[], r, g, b, a, lights[], overlay): the color is
     * scaled by each vertex's brightness, and each vertex takes its own light.
     */
    private static void perVertexPutBulkData(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "putBulkData",
                "(L" + CONSUMER + ";L" + POSE + ";L" + QUAD + ";" + PER_VERTEX_TAIL, null, null);
        mv.visitCode();
        // slots: 0 consumer, 1 pose, 2 quad, 3 brightness, 4 r, 5 g, 6 b, 7 a, 8 lights,
        // 9 overlay, 10 instance, 11 vertex
        newQuadInstance(mv, 10);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 11);
        Label loop = new Label();
        Label done = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(ILOAD, 11);
        mv.visitInsn(ICONST_4);
        mv.visitJumpInsn(IF_ICMPGE, done);
        mv.visitVarInsn(ALOAD, 10);
        mv.visitVarInsn(ILOAD, 11);
        argb(mv, 7, 4, 5, 6, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, QUAD_INSTANCE, "setColor", "(II)V", false);
        mv.visitVarInsn(ALOAD, 10);
        mv.visitVarInsn(ILOAD, 11);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitVarInsn(ILOAD, 11);
        mv.visitInsn(IALOAD);
        mv.visitMethodInsn(INVOKEVIRTUAL, QUAD_INSTANCE, "setLightCoords", "(II)V", false);
        mv.visitIincInsn(11, 1);
        mv.visitJumpInsn(GOTO, loop);
        mv.visitLabel(done);
        finishQuad(mv, 10, 9);
    }

    private static void newQuadInstance(MethodVisitor mv, int slot) {
        mv.visitTypeInsn(NEW, QUAD_INSTANCE);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, QUAD_INSTANCE, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, slot);
    }

    /**
     * Pushes {@code ARGB.colorFromFloat(a, r, g, b)}, with r, g and b each multiplied by
     * {@code brightness[vertex]} when a brightness slot is given (the vertex index is in slot 11).
     */
    private static void argb(MethodVisitor mv, int a, int r, int g, int b, int brightnessSlot) {
        mv.visitVarInsn(FLOAD, a);
        for (int channel : new int[]{r, g, b}) {
            mv.visitVarInsn(FLOAD, channel);
            if (brightnessSlot >= 0) {
                mv.visitVarInsn(ALOAD, brightnessSlot);
                mv.visitVarInsn(ILOAD, 11);
                mv.visitInsn(FALOAD);
                mv.visitInsn(FMUL);
            }
        }
        mv.visitMethodInsn(INVOKESTATIC, ARGB, "colorFromFloat", "(FFFF)I", false);
    }

    private static void finishQuad(MethodVisitor mv, int instanceSlot, int overlaySlot) {
        mv.visitVarInsn(ALOAD, instanceSlot);
        mv.visitVarInsn(ILOAD, overlaySlot);
        mv.visitMethodInsn(INVOKEVIRTUAL, QUAD_INSTANCE, "setOverlayCoords", "(I)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, instanceSlot);
        mv.visitMethodInsn(INVOKEINTERFACE, CONSUMER, "putBakedQuad",
                "(L" + POSE + ";L" + QUAD + ";L" + QUAD_INSTANCE + ";)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
