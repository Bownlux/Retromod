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
 * 26.2 reduced {@code ChatFormatting} to its constants, {@code getByCode}, {@code stripFormatting},
 * and {@code toString}. The per-constant accessors a 26.1 mod calls ({@code isColor},
 * {@code isFormat}, {@code getColor}, {@code getChar}, {@code getName}) are gone, so a tooltip or
 * chat helper that filters colors fails with {@code NoSuchMethodError} the first time it runs.
 *
 * <p>Every answer is still derivable on 26.2. The constant order is unchanged (verified against
 * the 26.1.2 and 26.2 jars): the sixteen colors come first, then the five formats, then
 * {@code RESET}. {@code TextColor.fromLegacyFormat} still maps a color constant to its value, and
 * {@code toString()} is still the section sign followed by the code character.
 *
 * <p>Each accessor becomes a static forwarder on a generated class that takes the constant as its
 * first argument, so the transformer turns {@code INVOKEVIRTUAL ChatFormatting.isColor()} into an
 * {@code INVOKESTATIC} without touching the stack. Registered from the 26.1 to 26.2 layer only.
 */
public final class LegacyChatFormattingBridge {

    private LegacyChatFormattingBridge() {}

    public static final String INTERNAL = "com/retromod/generated/LegacyChatFormatting";

    private static final String CHAT_FORMATTING = "net/minecraft/ChatFormatting";
    private static final String L_CF = "L" + CHAT_FORMATTING + ";";
    private static final String TEXT_COLOR = "net/minecraft/network/chat/TextColor";

    /** Ordinal of the first format constant ({@code OBFUSCATED}); everything below is a color. */
    private static final int FIRST_FORMAT_ORDINAL = 16;
    /** Ordinal of {@code RESET}, the constant after the last format ({@code ITALIC}). */
    private static final int RESET_ORDINAL = 21;

    /** The removed 26.1 accessors: name and their instance descriptor. */
    private static final String[][] ACCESSORS = {
        {"isColor", "()Z"},
        {"isFormat", "()Z"},
        {"getColor", "()Ljava/lang/Integer;"},
        {"getChar", "()C"},
        {"getName", "()Ljava/lang/String;"},
    };

    /** The receiver-as-first-argument descriptor the forwarder is declared with. */
    static String staticDesc(String instanceDesc) {
        return "(" + L_CF + instanceDesc.substring(1);
    }

    public static void register(RetromodTransformer t) {
        if (!t.getSyntheticClasses().containsKey(INTERNAL)) {
            t.registerSyntheticClass(INTERNAL, generate());
        }
        for (String[] accessor : ACCESSORS) {
            t.registerMethodRedirect(CHAT_FORMATTING, accessor[0], accessor[1],
                    INTERNAL, accessor[0], staticDesc(accessor[1]));
        }
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);

        // isColor: ordinal < 16
        MethodVisitor isColor = begin(cw, "isColor", "()Z");
        ordinalCompare(isColor, FIRST_FORMAT_ORDINAL, IF_ICMPGE);
        isColor.visitEnd();

        // isFormat: 16 <= ordinal < 21
        MethodVisitor isFormat = begin(cw, "isFormat", "()Z");
        Label no = new Label();
        isFormat.visitVarInsn(ALOAD, 0);
        isFormat.visitMethodInsn(INVOKEVIRTUAL, CHAT_FORMATTING, "ordinal", "()I", false);
        isFormat.visitInsn(DUP);
        isFormat.visitIntInsn(BIPUSH, FIRST_FORMAT_ORDINAL);
        Label popNo = new Label();
        isFormat.visitJumpInsn(IF_ICMPLT, popNo);
        isFormat.visitIntInsn(BIPUSH, RESET_ORDINAL);
        isFormat.visitJumpInsn(IF_ICMPGE, no);
        isFormat.visitInsn(ICONST_1);
        isFormat.visitInsn(IRETURN);
        isFormat.visitLabel(popNo);
        isFormat.visitInsn(POP);
        isFormat.visitLabel(no);
        isFormat.visitInsn(ICONST_0);
        isFormat.visitInsn(IRETURN);
        isFormat.visitMaxs(0, 0);
        isFormat.visitEnd();

        // getColor: TextColor.fromLegacyFormat(f), boxed, or null for formats and RESET
        MethodVisitor getColor = begin(cw, "getColor", "()Ljava/lang/Integer;");
        Label none = new Label();
        getColor.visitVarInsn(ALOAD, 0);
        getColor.visitMethodInsn(INVOKESTATIC, TEXT_COLOR, "fromLegacyFormat",
                "(" + L_CF + ")L" + TEXT_COLOR + ";", false);
        getColor.visitInsn(DUP);
        getColor.visitJumpInsn(IFNULL, none);
        getColor.visitMethodInsn(INVOKEVIRTUAL, TEXT_COLOR, "getValue", "()I", false);
        getColor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false);
        getColor.visitInsn(ARETURN);
        getColor.visitLabel(none);
        // The null left on the stack is typed TextColor, which the verifier rejects as an Integer.
        getColor.visitInsn(POP);
        getColor.visitInsn(ACONST_NULL);
        getColor.visitInsn(ARETURN);
        getColor.visitMaxs(0, 0);
        getColor.visitEnd();

        // getChar: toString() is the section sign plus the code character
        MethodVisitor getChar = begin(cw, "getChar", "()C");
        getChar.visitVarInsn(ALOAD, 0);
        getChar.visitMethodInsn(INVOKEVIRTUAL, CHAT_FORMATTING, "toString",
                "()Ljava/lang/String;", false);
        getChar.visitInsn(ICONST_1);
        getChar.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        getChar.visitInsn(IRETURN);
        getChar.visitMaxs(0, 0);
        getChar.visitEnd();

        // getName: name().toLowerCase(Locale.ROOT), exactly as 26.1 computed it
        MethodVisitor getName = begin(cw, "getName", "()Ljava/lang/String;");
        getName.visitVarInsn(ALOAD, 0);
        getName.visitMethodInsn(INVOKEVIRTUAL, CHAT_FORMATTING, "name",
                "()Ljava/lang/String;", false);
        getName.visitFieldInsn(GETSTATIC, "java/util/Locale", "ROOT", "Ljava/util/Locale;");
        getName.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toLowerCase",
                "(Ljava/util/Locale;)Ljava/lang/String;", false);
        getName.visitInsn(ARETURN);
        getName.visitMaxs(0, 0);
        getName.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String instanceDesc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, staticDesc(instanceDesc),
                null, null);
        mv.visitCode();
        return mv;
    }

    /** Returns true unless {@code ordinal <bound>} satisfies {@code falseJump}. */
    private static void ordinalCompare(MethodVisitor mv, int bound, int falseJump) {
        Label no = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CHAT_FORMATTING, "ordinal", "()I", false);
        mv.visitIntInsn(BIPUSH, bound);
        mv.visitJumpInsn(falseJump, no);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(no);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
    }
}
