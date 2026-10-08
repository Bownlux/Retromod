/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Client drawing calls that pre-1.20 Forge mods make with a {@code PoseStack}.
 *
 * <p>1.20 folded {@code GuiComponent} into {@code GuiGraphics}. The static {@code blit(PoseStack,
 * ...)} helpers that HUD overlays and screens called through {@code Gui}, {@code Screen} or
 * {@code GuiComponent} are gone, and the GUI events hand out a {@code GuiGraphics} instead of a
 * {@code PoseStack}. A mod's overlay then dies on its first frame with {@code NoSuchMethodError}.
 *
 * <p>The generated {@code LegacyGuiComponent} draws the same textured quad the old helpers drew:
 * position-and-texture vertices through the tesselator, with whatever texture the mod bound
 * through {@code RenderSystem.setShaderTexture}, exactly as 1.19.2 did. That is why it does not
 * route through {@code GuiGraphics.blit}, which needs the texture's id. The events' old
 * {@code getPoseStack()} returns the pose stack of their {@code GuiGraphics}. A 1.19.2
 * {@code HumanoidArmorLayer} gets the {@code ModelManager} that 1.20 added to its constructor.
 *
 * <p>Text and fill helpers ({@code drawString}, {@code fill}) and the sprite overload of
 * {@code blit} are not bridged. The quad code links the buffer API that 1.21 replaced, so this
 * registers only on hosts from 1.20 up to, not including, 1.21.
 */
public final class LegacyGuiComponentBridge {

    static final String GENERATED = "com/retromod/generated/LegacyGuiComponent";

    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String POSE = "com/mojang/blaze3d/vertex/PoseStack$Pose";
    private static final String MATRIX = "org/joml/Matrix4f";
    private static final String TESSELATOR = "com/mojang/blaze3d/vertex/Tesselator";
    private static final String BUFFER_BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final String RENDERED = "com/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer";
    private static final String VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String MODE = "com/mojang/blaze3d/vertex/VertexFormat$Mode";
    private static final String FORMAT = "com/mojang/blaze3d/vertex/VertexFormat";
    private static final String DEFAULT_FORMAT = "com/mojang/blaze3d/vertex/DefaultVertexFormat";
    private static final String UPLOADER = "com/mojang/blaze3d/vertex/BufferUploader";
    private static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String SHADER = "net/minecraft/client/renderer/ShaderInstance";
    private static final String GUI_GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
    private static final String ARMOR_LAYER = "net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer";
    private static final String LAYER_PARENT = "net/minecraft/client/renderer/entity/RenderLayerParent";
    private static final String HUMANOID_MODEL = "net/minecraft/client/model/HumanoidModel";

    private static final String L_POSE_STACK = "L" + POSE_STACK + ";";
    private static final String QUAD_DESC = "(" + L_POSE_STACK + "IIIIIFFFF)V";

    /** The old {@code GuiComponent} statics, keyed by descriptor, and the classes mods called them through. */
    static final String BLIT_UV_DESC = "(" + L_POSE_STACK + "IIFFIIII)V";
    static final String BLIT_SIZED_DESC = "(" + L_POSE_STACK + "IIIIFFIIII)V";
    static final String BLIT_Z_DESC = "(" + L_POSE_STACK + "IIIFFIIII)V";
    static final String BLIT_INSTANCE_DESC = "(" + L_POSE_STACK + "IIIIII)V";
    private static final String[] GUI_COMPONENT_OWNERS = {
            "net/minecraft/client/gui/GuiComponent",
            "net/minecraft/client/gui/Gui",
            "net/minecraft/client/gui/screens/Screen",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
            "net/minecraft/client/gui/components/AbstractWidget",
            "net/minecraftforge/client/gui/overlay/ForgeGui",
    };

    /** Forge GUI events whose {@code getPoseStack()} became {@code getGuiGraphics()} in 1.20. */
    static final String[][] POSE_EVENTS = {
            {"net/minecraftforge/client/event/RenderGuiEvent",
                    "net/minecraftforge/client/event/RenderGuiEvent$Pre",
                    "net/minecraftforge/client/event/RenderGuiEvent$Post"},
            {"net/minecraftforge/client/event/RenderGuiOverlayEvent",
                    "net/minecraftforge/client/event/RenderGuiOverlayEvent$Pre",
                    "net/minecraftforge/client/event/RenderGuiOverlayEvent$Post"},
            {"net/minecraftforge/client/event/ScreenEvent$Render",
                    "net/minecraftforge/client/event/ScreenEvent$Render$Pre",
                    "net/minecraftforge/client/event/ScreenEvent$Render$Post"},
            {"net/minecraftforge/client/event/ScreenEvent$BackgroundRendered"},
    };

    static final String OLD_ARMOR_LAYER_DESC =
            "(L" + LAYER_PARENT + ";L" + HUMANOID_MODEL + ";L" + HUMANOID_MODEL + ";)V";
    static final String ARMOR_LAYER_DESC =
            "(L" + LAYER_PARENT + ";L" + HUMANOID_MODEL + ";L" + HUMANOID_MODEL + ";L" + MODEL_MANAGER + ";)V";

    private LegacyGuiComponentBridge() {}

    /** Whether the host still has the 1.20 buffer and armor-layer API this bridge links. */
    static boolean supportsHost(String host) {
        return RetromodVersion.compareMcVersions(host, "1.20") >= 0
                && RetromodVersion.compareMcVersions(host, "1.21") < 0;
    }

    public static void register(RetromodTransformer transformer) {
        if (!supportsHost(RetromodVersion.TARGET_MC_VERSION)) return;
        transformer.registerSyntheticClass(GENERATED, generate());

        for (String owner : GUI_COMPONENT_OWNERS) {
            transformer.registerInheritedMethodRedirect(owner, "blit", BLIT_UV_DESC,
                    GENERATED, "blit", BLIT_UV_DESC);
            transformer.registerInheritedMethodRedirect(owner, "blit", BLIT_SIZED_DESC,
                    GENERATED, "blit", BLIT_SIZED_DESC);
            transformer.registerInheritedMethodRedirect(owner, "blit", BLIT_Z_DESC,
                    GENERATED, "blit", BLIT_Z_DESC);
            // The instance blit drew from a 256x256 texture; the receiver becomes an ignored argument.
            // A screen calls it as this.blit(...), which names the mod's own screen as the owner.
            transformer.registerInheritedMethodRedirect(owner, "blit", BLIT_INSTANCE_DESC,
                    GENERATED, "blitInstance", "(Ljava/lang/Object;" + BLIT_INSTANCE_DESC.substring(1));
        }

        if (!com.retromod.util.McReflect.isNeoForge()) {
            for (String[] family : POSE_EVENTS) {
                String declared = family[0];
                for (String owner : family) {
                    transformer.registerMethodRedirect(owner, "getPoseStack", "()" + L_POSE_STACK,
                            GENERATED, "pose", "(L" + declared + ";)" + L_POSE_STACK, true);
                }
            }
        }

        transformer.registerConstructorRedirect(ARMOR_LAYER, OLD_ARMOR_LAYER_DESC,
                GENERATED, "armorLayer",
                "(L" + LAYER_PARENT + ";L" + HUMANOID_MODEL + ";L" + HUMANOID_MODEL + ";)L" + ARMOR_LAYER + ";");
        transformer.registerSuperConstructorAdapter(ARMOR_LAYER, OLD_ARMOR_LAYER_DESC, ARMOR_LAYER_DESC,
                LegacyGuiComponentBridge::pushModelManager);
    }

    private static void pushModelManager(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, MINECRAFT, "getModelManager", "()L" + MODEL_MANAGER + ";", false);
    }

    /**
     * {@code class LegacyGuiComponent implements Supplier<ShaderInstance>}. It is its own shader
     * supplier so the quad needs no lambda, whose method handle would also need remapping.
     */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, GENERATED, null, "java/lang/Object",
                new String[]{"java/util/function/Supplier"});

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitMethodInsn(INVOKESTATIC, GAME_RENDERER, "getPositionTexShader", "()L" + SHADER + ";", false);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        emitQuad(cw);
        emitBlitUv(cw);
        emitBlitSized(cw);
        emitBlitZ(cw);
        emitBlitInstance(cw);
        for (String[] family : POSE_EVENTS) emitPose(cw, family[0]);
        emitArmorLayer(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code quad(pose, x0, x1, y0, y1, z, u0, u1, v0, v1)}: 1.19.2's {@code GuiComponent.innerBlit}. */
    private static void emitQuad(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "quad", QUAD_DESC, null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, GENERATED);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, GENERATED, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESTATIC, RENDER_SYSTEM, "setShader", "(Ljava/util/function/Supplier;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, POSE_STACK, "last", "()L" + POSE + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, POSE, "pose", "()L" + MATRIX + ";", false);
        mv.visitVarInsn(ASTORE, 10);
        mv.visitMethodInsn(INVOKESTATIC, TESSELATOR, "getInstance", "()L" + TESSELATOR + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, TESSELATOR, "getBuilder", "()L" + BUFFER_BUILDER + ";", false);
        mv.visitVarInsn(ASTORE, 11);
        mv.visitVarInsn(ALOAD, 11);
        mv.visitFieldInsn(GETSTATIC, MODE, "QUADS", "L" + MODE + ";");
        mv.visitFieldInsn(GETSTATIC, DEFAULT_FORMAT, "POSITION_TEX", "L" + FORMAT + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, BUFFER_BUILDER, "begin", "(L" + MODE + ";L" + FORMAT + ";)V", false);
        // Corners in the old order: (x0,y0) (x0,y1) (x1,y1) (x1,y0), locals 1..4 and 6..9.
        int[][] corners = {{1, 3, 6, 8}, {1, 4, 6, 9}, {2, 4, 7, 9}, {2, 3, 7, 8}};
        for (int[] corner : corners) {
            mv.visitVarInsn(ALOAD, 11);
            mv.visitVarInsn(ALOAD, 10);
            mv.visitVarInsn(ILOAD, corner[0]);
            mv.visitInsn(I2F);
            mv.visitVarInsn(ILOAD, corner[1]);
            mv.visitInsn(I2F);
            mv.visitVarInsn(ILOAD, 5);
            mv.visitInsn(I2F);
            mv.visitMethodInsn(INVOKEINTERFACE, VERTEX_CONSUMER, "vertex",
                    "(L" + MATRIX + ";FFF)L" + VERTEX_CONSUMER + ";", true);
            mv.visitVarInsn(FLOAD, corner[2]);
            mv.visitVarInsn(FLOAD, corner[3]);
            mv.visitMethodInsn(INVOKEINTERFACE, VERTEX_CONSUMER, "uv", "(FF)L" + VERTEX_CONSUMER + ";", true);
            mv.visitMethodInsn(INVOKEINTERFACE, VERTEX_CONSUMER, "endVertex", "()V", true);
        }
        mv.visitVarInsn(ALOAD, 11);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUFFER_BUILDER, "end", "()L" + RENDERED + ";", false);
        mv.visitMethodInsn(INVOKESTATIC, UPLOADER, "drawWithShader", "(L" + RENDERED + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code blit(pose, x, y, u, v, width, height, textureWidth, textureHeight)}. */
    private static void emitBlitUv(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blit", BLIT_UV_DESC, null, null);
        mv.visitCode();
        // blit(pose, x, y, width, height, u, v, width, height, textureWidth, textureHeight)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitVarInsn(FLOAD, 3);
        mv.visitVarInsn(FLOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitVarInsn(ILOAD, 7);
        mv.visitVarInsn(ILOAD, 8);
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "blit", BLIT_SIZED_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code blit(pose, x, y, width, height, u, v, uWidth, vHeight, textureWidth, textureHeight)}. */
    private static void emitBlitSized(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blit", BLIT_SIZED_DESC, null, null);
        mv.visitCode();
        emitQuadCall(mv, 1, 2, 3, 4, -1, 5, 6, 7, 8, 9, 10);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code blit(pose, x, y, z, u, v, width, height, textureHeight, textureWidth)}. */
    private static void emitBlitZ(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blit", BLIT_Z_DESC, null, null);
        mv.visitCode();
        emitQuadCall(mv, 1, 2, 6, 7, 3, 4, 5, 6, 7, 9, 8);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The instance {@code blit(pose, x, y, u, v, width, height)} on a 256x256 texture. */
    private static void emitBlitInstance(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blitInstance",
                "(Ljava/lang/Object;" + BLIT_INSTANCE_DESC.substring(1), null, null);
        mv.visitCode();
        // Shift every argument index by one for the receiver, then call the sized overload.
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitVarInsn(ILOAD, 7);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitInsn(I2F);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(I2F);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitVarInsn(ILOAD, 7);
        mv.visitIntInsn(SIPUSH, 256);
        mv.visitIntInsn(SIPUSH, 256);
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "blit", BLIT_SIZED_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Pushes {@code quad(pose, x, x + width, y, y + height, z, u / tw, (u + uw) / tw, v / th,
     * (v + vh) / th)} from local slots and calls it. A {@code zSlot} of -1 means z = 0.
     */
    private static void emitQuadCall(MethodVisitor mv, int x, int y, int width, int height, int zSlot,
            int u, int v, int uWidth, int vHeight, int textureWidth, int textureHeight) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, x);
        mv.visitVarInsn(ILOAD, x);
        mv.visitVarInsn(ILOAD, width);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ILOAD, y);
        mv.visitVarInsn(ILOAD, y);
        mv.visitVarInsn(ILOAD, height);
        mv.visitInsn(IADD);
        if (zSlot < 0) mv.visitInsn(ICONST_0); else mv.visitVarInsn(ILOAD, zSlot);
        emitTexCoord(mv, u, -1, textureWidth);
        emitTexCoord(mv, u, uWidth, textureWidth);
        emitTexCoord(mv, v, -1, textureHeight);
        emitTexCoord(mv, v, vHeight, textureHeight);
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "quad", QUAD_DESC, false);
    }

    /** {@code (offset + span) / (float) size}, or {@code offset / (float) size} when spanSlot is -1. */
    private static void emitTexCoord(MethodVisitor mv, int offsetSlot, int spanSlot, int sizeSlot) {
        mv.visitVarInsn(FLOAD, offsetSlot);
        if (spanSlot >= 0) {
            mv.visitVarInsn(ILOAD, spanSlot);
            mv.visitInsn(I2F);
            mv.visitInsn(FADD);
        }
        mv.visitVarInsn(ILOAD, sizeSlot);
        mv.visitInsn(I2F);
        mv.visitInsn(FDIV);
    }

    /** {@code pose(event)}: {@code event.getGuiGraphics().pose()}. */
    private static void emitPose(ClassWriter cw, String event) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "pose",
                "(L" + event + ";)" + L_POSE_STACK, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, event, "getGuiGraphics", "()L" + GUI_GRAPHICS + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, GUI_GRAPHICS, "pose", "()" + L_POSE_STACK, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code new HumanoidArmorLayer(parent, inner, outer)} with the client's model manager. */
    private static void emitArmorLayer(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "armorLayer",
                "(L" + LAYER_PARENT + ";L" + HUMANOID_MODEL + ";L" + HUMANOID_MODEL + ";)L" + ARMOR_LAYER + ";",
                null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, ARMOR_LAYER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        pushModelManager(mv);
        mv.visitMethodInsn(INVOKESPECIAL, ARMOR_LAYER, "<init>", ARMOR_LAYER_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
