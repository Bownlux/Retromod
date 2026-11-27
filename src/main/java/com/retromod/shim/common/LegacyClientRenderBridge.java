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
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps pre-1.19.4 entity renderers drawing on a 1.19.4 or newer host.
 *
 * <p>1.19.4 moved a mob's limb swing from the {@code animationPosition}, {@code animationSpeed}
 * and {@code animationSpeedOld} fields into a {@code WalkAnimationState}, replaced the
 * {@code seeThrough} flag of {@code Font.drawInBatch} with a display mode, and dropped the float
 * {@code Mth.fastInvSqrt}. Model libraries such as GeckoLib 3 read the limb swing on every frame,
 * so the first rendered mob crashed the client. Reads go through the walk animation state, the
 * previous speed being its value at partial tick 0. A write to the speed sets it, and writes to
 * the position and previous speed are dropped, because the state only advances as a whole.
 */
public final class LegacyClientRenderBridge {

    static final String SYNTH = "com/retromod/generated/LegacyClientRender";

    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String WALK = "net/minecraft/world/entity/WalkAnimationState";
    private static final String FONT = "net/minecraft/client/gui/Font";
    private static final String DISPLAY_MODE = FONT + "$DisplayMode";
    private static final String BUFFERS = "Lnet/minecraft/client/renderer/MultiBufferSource;";
    private static final String[] TEXT_TYPES = {"Ljava/lang/String;", "Lnet/minecraft/network/chat/Component;",
        "Lnet/minecraft/util/FormattedCharSequence;"};
    private static final String[] MATRIX_TYPES = {"Lorg/joml/Matrix4f;", "Lcom/mojang/math/Matrix4f;"};
    private static final String STRING_DRAW = "(L" + FONT + ";Ljava/lang/String;FFIZLorg/joml/Matrix4f;" + BUFFERS + "ZII)I";
    static final String BIDI_DRAW = STRING_DRAW.replace("ZII)I", "ZIIZ)I");
    private static final String SPLITTER = "net/minecraft/client/StringSplitter";

    /** The host's display-mode text draw, which 1.21.6 made void, 26.1 widened to Matrix4fc, and 26.2 removed. */
    enum TextDraw {
        RETURNS_END_X("Lorg/joml/Matrix4f;", "I"),
        VOID("Lorg/joml/Matrix4f;", "V"),
        VOID_WIDE_MATRIX("Lorg/joml/Matrix4fc;", "V"),
        NONE(null, null);

        final String matrix;
        final String result;

        TextDraw(String matrix, String result) {
            this.matrix = matrix;
            this.result = result;
        }

        /** The host draw for one text type, or null when the host has none. */
        String descriptor(String text) {
            if (matrix == null) return null;
            return "(" + text + "FFIZ" + matrix + BUFFERS + "L" + DISPLAY_MODE + ";II)" + result;
        }
    }

    private LegacyClientRenderBridge() {
    }

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer transformer, Function<String, ClassNode> host) {
        transformer.registerSyntheticClass(SYNTH, generate(textDraw(host.apply(FONT))));
        String getter = "(L" + LIVING + ";)F";
        String setter = "(L" + LIVING + ";F)V";
        transformer.registerFieldStaticBridge(LIVING, "animationPosition", SYNTH, "walkPosition", getter,
                "ignoreWalk", setter);
        transformer.registerFieldStaticBridge(LIVING, "animationSpeed", SYNTH, "walkSpeed", getter,
                "setWalkSpeed", setter);
        transformer.registerFieldStaticBridge(LIVING, "animationSpeedOld", SYNTH, "walkSpeedOld", getter,
                "ignoreWalk", setter);

        transformer.registerMethodRedirect("net/minecraft/util/Mth", "fastInvSqrt", "(F)F",
                SYNTH, "fastInvSqrt", "(F)F");

        for (String text : TEXT_TYPES) {
            for (String matrix : MATRIX_TYPES) {
                String oldDesc = "(" + text + "FFIZ" + matrix + BUFFERS + "ZII)I";
                transformer.registerMethodRedirect(FONT, "drawInBatch", oldDesc, SYNTH, "drawInBatch",
                        "(L" + FONT + ";" + text + "FFIZLorg/joml/Matrix4f;" + BUFFERS + "ZII)I", true);
            }
        }
        // The string draw also had a form with a trailing bidirectional flag, which the same draw answers.
        for (String matrix : MATRIX_TYPES) {
            transformer.registerMethodRedirect(FONT, "drawInBatch", "(" + TEXT_TYPES[0] + "FFIZ" + matrix + BUFFERS + "ZIIZ)I",
                    SYNTH, "drawInBatch", BIDI_DRAW, true);
        }
    }

    /** The text draw the host's Font has; offline or on an SRG host, the shape of the target version. */
    static TextDraw textDraw(ClassNode font) {
        if (font != null) {
            for (TextDraw draw : TextDraw.values()) {
                if (draw != TextDraw.NONE && declares(font, "drawInBatch", draw.descriptor(TEXT_TYPES[0]))) return draw;
            }
            // A Mojang-named host without the draw removed it; an SRG-named one only hides the name.
            if (RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) return TextDraw.NONE;
        }
        String target = RetromodVersion.TARGET_MC_VERSION;
        if (RetromodVersion.compareMcVersions(target, "26.2") >= 0) return TextDraw.NONE;
        if (RetromodVersion.compareMcVersions(target, "26.1") >= 0) return TextDraw.VOID_WIDE_MATRIX;
        if (RetromodVersion.compareMcVersions(target, "1.21.6") >= 0) return TextDraw.VOID;
        return TextDraw.RETURNS_END_X;
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
    }

    static byte[] generate() {
        return generate(textDraw(null));
    }

    static byte[] generate(TextDraw draw) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);

        walkRead(cw, "walkPosition", "position", false);
        walkRead(cw, "walkSpeed", "speed", false);
        walkRead(cw, "walkSpeedOld", "speed", true);

        MethodVisitor setSpeed = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setWalkSpeed", "(L" + LIVING + ";F)V",
                null, null);
        setSpeed.visitCode();
        walkState(setSpeed);
        setSpeed.visitVarInsn(FLOAD, 1);
        setSpeed.visitMethodInsn(INVOKEVIRTUAL, WALK, "setSpeed", "(F)V", false);
        setSpeed.visitInsn(RETURN);
        setSpeed.visitMaxs(0, 0);
        setSpeed.visitEnd();

        MethodVisitor ignore = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "ignoreWalk", "(L" + LIVING + ";F)V",
                null, null);
        ignore.visitCode();
        ignore.visitInsn(RETURN);
        ignore.visitMaxs(0, 0);
        ignore.visitEnd();

        MethodVisitor invSqrt = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fastInvSqrt", "(F)F", null, null);
        invSqrt.visitCode();
        invSqrt.visitVarInsn(FLOAD, 0);
        invSqrt.visitInsn(F2D);
        invSqrt.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Mth", "fastInvSqrt", "(D)D", false);
        invSqrt.visitInsn(D2F);
        invSqrt.visitInsn(FRETURN);
        invSqrt.visitMaxs(0, 0);
        invSqrt.visitEnd();

        for (String text : TEXT_TYPES) {
            drawInBatch(cw, text, draw);
        }
        bidiDrawInBatch(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void walkState(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, LIVING, "walkAnimation", "L" + WALK + ";");
    }

    private static void walkRead(ClassWriter cw, String name, String accessor, boolean atPreviousTick) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + LIVING + ";)F", null, null);
        mv.visitCode();
        walkState(mv);
        if (atPreviousTick) {
            mv.visitInsn(FCONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, WALK, accessor, "(F)F", false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, WALK, accessor, "()F", false);
        }
        mv.visitInsn(FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * The old seeThrough flag picks the see-through display mode, otherwise the normal one. The
     * old result was the end x, which a host draw that returns nothing leaves to be worked out.
     */
    private static void drawInBatch(ClassWriter cw, String text, TextDraw draw) {
        String desc = "(L" + FONT + ";" + text + "FFIZLorg/joml/Matrix4f;" + BUFFERS + "ZII)I";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "drawInBatch", desc, null, null);
        mv.visitCode();
        // 26.2 removed the draw with the buffer source, so the text is not drawn there.
        if (draw != TextDraw.NONE) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(FLOAD, 2);
            mv.visitVarInsn(FLOAD, 3);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            mv.visitVarInsn(ALOAD, 6);
            mv.visitVarInsn(ALOAD, 7);
            Label normal = new Label();
            Label chosen = new Label();
            mv.visitVarInsn(ILOAD, 8);
            mv.visitJumpInsn(IFEQ, normal);
            mv.visitFieldInsn(GETSTATIC, DISPLAY_MODE, "SEE_THROUGH", "L" + DISPLAY_MODE + ";");
            mv.visitJumpInsn(GOTO, chosen);
            mv.visitLabel(normal);
            mv.visitFieldInsn(GETSTATIC, DISPLAY_MODE, "NORMAL", "L" + DISPLAY_MODE + ";");
            mv.visitLabel(chosen);
            mv.visitVarInsn(ILOAD, 9);
            mv.visitVarInsn(ILOAD, 10);
            mv.visitMethodInsn(INVOKEVIRTUAL, FONT, "drawInBatch", draw.descriptor(text), false);
            if (draw == TextDraw.RETURNS_END_X) {
                mv.visitInsn(IRETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return;
            }
        }
        // The end x is the x after the last glyph, one further with a shadow. The unrounded width
        // keeps it exact for fonts whose glyphs advance by fractions.
        mv.visitVarInsn(FLOAD, 2);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, FONT, "getSplitter", "()L" + SPLITTER + ";", false);
        mv.visitVarInsn(ALOAD, 1);
        String measured = text.equals("Lnet/minecraft/network/chat/Component;") ? "Lnet/minecraft/network/chat/FormattedText;" : text;
        mv.visitMethodInsn(INVOKEVIRTUAL, SPLITTER, "stringWidth", "(" + measured + ")F", false);
        mv.visitInsn(FADD);
        mv.visitInsn(F2I);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The string draw with the bidirectional flag; the font's own setting decides now, so the flag is dropped. */
    private static void bidiDrawInBatch(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "drawInBatch", BIDI_DRAW, null, null);
        mv.visitCode();
        int[] loads = {ALOAD, ALOAD, FLOAD, FLOAD, ILOAD, ILOAD, ALOAD, ALOAD, ILOAD, ILOAD, ILOAD};
        for (int slot = 0; slot < loads.length; slot++) {
            mv.visitVarInsn(loads[slot], slot);
        }
        mv.visitMethodInsn(INVOKESTATIC, SYNTH, "drawInBatch", STRING_DRAW, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
