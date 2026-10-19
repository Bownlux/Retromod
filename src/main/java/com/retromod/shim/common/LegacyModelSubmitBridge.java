/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Keeps renderers built for 26.2 submitting their models on 26.3.
 *
 * <p>Through 26.2 a renderer passed a {@code TextureAtlasSprite} and a block's breaking overlay to
 * {@code submitModel} and {@code submitModelPart}. 26.3 takes any {@code UvMapping}, which the
 * sprite still is, and draws the breaking overlay through its own {@code submitCrumblingOverlay}
 * call. An entity layer that used the old form failed with {@code NoSuchMethodError} on its first
 * frame. Each old call now makes the new one, then draws the overlay when it was given one, the
 * way 26.3's block entity renderers do.
 */
public final class LegacyModelSubmitBridge {

    static final String HELPER = "com/retromod/generated/LegacyModelSubmit";
    /**
     * The sprite id form, apart, because only a mod that calls it should carry a reference to
     * {@code SpriteId}, a class Retromod's verifier cannot see on a dedicated server.
     */
    static final String SPRITE_ID_HELPER = "com/retromod/generated/LegacyModelSubmitSpriteId";

    static final String ORDERED = "net/minecraft/client/renderer/OrderedSubmitNodeCollector";
    static final String COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector";
    private static final String MODEL = "Lnet/minecraft/client/model/Model;";
    private static final String PART = "Lnet/minecraft/client/model/geom/ModelPart;";
    private static final String POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    private static final String SPRITE = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
    private static final String UV = "Lnet/minecraft/client/renderer/texture/UvMapping;";
    private static final String CRUMBLING =
            "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;";
    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    private static final String SPRITE_ID = "Lnet/minecraft/client/resources/model/sprite/SpriteId;";
    private static final String SPRITE_GETTER = "Lnet/minecraft/client/resources/model/sprite/SpriteGetter;";
    private static final String OBJECT = "Ljava/lang/Object;";

    // The 26.2 forms.
    static final String MODEL_SPRITE =
            "(" + MODEL + OBJECT + POSE + RENDER_TYPE + "III" + SPRITE + "I" + CRUMBLING + ")V";
    static final String MODEL_PLAIN = "(" + MODEL + OBJECT + POSE + RENDER_TYPE + "III" + CRUMBLING + ")V";
    static final String MODEL_TEXTURE = "(" + MODEL + OBJECT + POSE + IDENTIFIER + "III" + CRUMBLING + ")V";
    static final String MODEL_SPRITE_ID =
            "(" + MODEL + OBJECT + POSE + "III" + SPRITE_ID + SPRITE_GETTER + "I" + CRUMBLING + ")V";
    static final String PART_SPRITE = "(" + PART + POSE + RENDER_TYPE + "II" + SPRITE + ")V";
    static final String PART_TINT = "(" + PART + POSE + RENDER_TYPE + "II" + SPRITE + "I" + CRUMBLING + ")V";
    static final String PART_OUTLINE = "(" + PART + POSE + RENDER_TYPE + "II" + SPRITE + "I" + CRUMBLING + "I)V";

    // The 26.3 forms they call.
    static final String NEW_MODEL_UV = "(" + MODEL + OBJECT + POSE + RENDER_TYPE + "III" + UV + "I)V";
    static final String NEW_MODEL_PLAIN = "(" + MODEL + OBJECT + POSE + RENDER_TYPE + "III)V";
    static final String NEW_PART_UV = "(" + PART + POSE + RENDER_TYPE + "II" + UV + ")V";
    static final String NEW_PART_TINT = "(" + PART + POSE + RENDER_TYPE + "II" + UV + "I)V";
    static final String NEW_PART_OUTLINE = "(" + PART + POSE + RENDER_TYPE + "II" + UV + "II)V";
    static final String CRUMBLE_MODEL = "(" + MODEL + OBJECT + POSE + RENDER_TYPE + "III" + CRUMBLING + ")V";
    static final String CRUMBLE_PART = "(" + PART + POSE + RENDER_TYPE + "III" + CRUMBLING + ")V";

    private LegacyModelSubmitBridge() {}

    /** Registers on a host whose collector lost the old forms. */
    public static void register(RetromodTransformer transformer) {
        if (hostTakesUvMapping()) registerRedirects(transformer);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerSyntheticClass(SPRITE_ID_HELPER, generateSpriteIdHelper());
        // Renderers call through either interface, depending on whether they ordered the collector.
        for (String owner : new String[] {ORDERED, COLLECTOR}) {
            for (String[] form : new String[][] {{"submitModel", MODEL_SPRITE}, {"submitModel", MODEL_PLAIN},
                    {"submitModel", MODEL_TEXTURE}, {"submitModelPart", PART_TINT},
                    {"submitModelPart", PART_OUTLINE}}) {
                transformer.registerMethodRedirect(owner, form[0], form[1],
                        HELPER, form[0], receiverFirst(form[1]), true);
            }
            transformer.registerMethodRedirect(owner, "submitModel", MODEL_SPRITE_ID,
                    SPRITE_ID_HELPER, "submitModel", receiverFirst(MODEL_SPRITE_ID), true);
            // The sprite is a UvMapping, so only the descriptor changes.
            transformer.registerMethodRedirect(owner, "submitModelPart", PART_SPRITE,
                    owner, "submitModelPart", NEW_PART_UV);
        }
    }

    /** Whether the host's collector takes a {@code UvMapping} and draws breaking overlays separately. */
    static boolean hostTakesUvMapping() {
        ClassNode ordered = ClassResourceInspector.read(ORDERED);
        // A dedicated server has no client classes and offline none are readable, so the host
        // version decides. The helper only runs from a renderer, which only a client loads.
        if (ordered == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        }
        return hostTakesUvMapping(ordered);
    }

    static boolean hostTakesUvMapping(ClassNode ordered) {
        return !hasMethod(ordered, "submitModel", MODEL_SPRITE)
                && hasMethod(ordered, "submitModel", NEW_MODEL_UV)
                && hasMethod(ordered, "submitCrumblingOverlay", CRUMBLE_MODEL)
                && hasMethod(ordered, "submitCrumblingOverlay", CRUMBLE_PART);
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static String receiverFirst(String desc) {
        return "(L" + ORDERED + ";" + desc.substring(1);
    }

    /** Writes the helper. Generated because its signatures name Minecraft types. */
    static byte[] generateHelper() {
        ClassWriter cw = beginHelper(HELPER);
        emitModelWithSprite(cw);
        emitModelPlain(cw);
        emitModelTexture(cw);
        emitPart(cw, PART_TINT, NEW_PART_TINT, false);
        emitPart(cw, PART_OUTLINE, NEW_PART_OUTLINE, true);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes the helper for the sprite id form. */
    static byte[] generateSpriteIdHelper() {
        ClassWriter cw = beginHelper(SPRITE_ID_HELPER);
        emitModelSpriteId(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter beginHelper(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        return cw;
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String oldDesc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                receiverFirst(oldDesc), null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Pushes collector, model, state, pose, then the render type from {@code renderTypeSlot}, then
     * light, overlay and tint from three consecutive slots.
     */
    private static void pushModelCall(MethodVisitor mv, int renderTypeSlot, int lightSlot) {
        for (int slot = 0; slot <= 3; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitVarInsn(Opcodes.ALOAD, renderTypeSlot);
        for (int slot = lightSlot; slot < lightSlot + 3; slot++) mv.visitVarInsn(Opcodes.ILOAD, slot);
    }

    /** Writes {@code if (crumbling != null) collector.submitCrumblingOverlay(model, ..., crumbling)}. */
    private static void emitModelCrumbling(MethodVisitor mv, int renderTypeSlot, int lightSlot,
            int crumblingSlot) {
        Label none = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, crumblingSlot);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        pushModelCall(mv, renderTypeSlot, lightSlot);
        mv.visitVarInsn(Opcodes.ALOAD, crumblingSlot);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, "submitCrumblingOverlay", CRUMBLE_MODEL, true);
        mv.visitLabel(none);
    }

    /**
     * (collector, model, state, pose, renderType 4, light 5, overlay 6, tint 7, sprite 8,
     * outline 9, crumbling 10).
     */
    private static void emitModelWithSprite(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "submitModel", MODEL_SPRITE);
        pushModelCall(mv, 4, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, "submitModel", NEW_MODEL_UV, true);
        emitModelCrumbling(mv, 4, 5, 10);
        end(mv);
    }

    /** (collector, model, state, pose, renderType 4, light 5, overlay 6, tint 7, crumbling 8). */
    private static void emitModelPlain(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "submitModel", MODEL_PLAIN);
        pushModelCall(mv, 4, 5);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, "submitModel", NEW_MODEL_PLAIN, true);
        emitModelCrumbling(mv, 4, 5, 8);
        end(mv);
    }

    /**
     * (collector, model, state, pose, texture 4, light 5, overlay 6, tint 7, crumbling 8),
     * resolving the render type the way 26.3's own default does.
     */
    private static void emitModelTexture(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "submitModel", MODEL_TEXTURE);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/model/Model", "renderType",
                "(" + IDENTIFIER + ")" + RENDER_TYPE, false);
        mv.visitVarInsn(Opcodes.ASTORE, 9);
        pushModelCall(mv, 9, 5);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, "submitModel", NEW_MODEL_PLAIN, true);
        emitModelCrumbling(mv, 9, 5, 8);
        end(mv);
    }

    /**
     * (collector, model, state, pose, light 4, overlay 5, tint 6, spriteId 7, sprites 8, outline 9,
     * crumbling 10), resolving the render type and sprite the way 26.3's own default does.
     */
    private static void emitModelSpriteId(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "submitModel", MODEL_SPRITE_ID);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/model/Model", "renderType",
                "()Ljava/util/function/Function;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/resources/model/sprite/SpriteId",
                "renderType", "(Ljava/util/function/Function;)" + RENDER_TYPE, false);
        mv.visitVarInsn(Opcodes.ASTORE, 11);
        pushModelCall(mv, 11, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/client/resources/model/sprite/SpriteGetter",
                "get", "(" + SPRITE_ID + ")" + SPRITE, true);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, "submitModel", NEW_MODEL_UV, true);
        emitModelCrumbling(mv, 11, 4, 10);
        end(mv);
    }

    /**
     * (collector, part, pose, renderType 3, light 4, overlay 5, sprite 6, tint 7, crumbling 8
     * [, outline 9]). In 26.2 the breaking overlay sat between the tint and the outline, so it is
     * taken out of the middle.
     */
    private static void emitPart(ClassWriter cw, String oldDesc, String newDesc, boolean withOutline) {
        MethodVisitor mv = begin(cw, "submitModelPart", oldDesc);
        for (int slot = 0; slot <= 3; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        if (withOutline) mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, "submitModelPart", newDesc, true);
        Label none = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        for (int slot = 0; slot <= 3; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        for (int slot = 4; slot <= 5; slot++) mv.visitVarInsn(Opcodes.ILOAD, slot);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, "submitCrumblingOverlay", CRUMBLE_PART, true);
        mv.visitLabel(none);
        end(mv);
    }
}
