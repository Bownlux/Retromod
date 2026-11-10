/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Keeps a command's color argument working after 26.2 replaced ColorArgument with TeamColorArgument. */
public final class LegacyColorArgumentBridge {

    static final String ARGUMENTS = "net/minecraft/commands/arguments/";
    static final String OLD = ARGUMENTS + "ColorArgument";
    static final String NEW = ARGUMENTS + "TeamColorArgument";
    static final String HELPER = "com/retromod/generated/LegacyColorArguments";
    private static final String CHAT_FORMATTING = "net/minecraft/ChatFormatting";
    private static final String TEAM_COLOR = "net/minecraft/world/scores/TeamColor";
    static final String GET_COLOR = "(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)L"
            + CHAT_FORMATTING + ";";

    private LegacyColorArgumentBridge() {}

    /** Registered for 26.2 and newer hosts, through each loader's 26.1 to 26.2 shim. */
    public static void register(RetromodTransformer t) {
        // The class first, so each old call is also matched once its descriptors name the new class.
        t.registerClassRedirect(OLD, NEW);
        // JourneyMap builds its waypoint command with color() while the server registers commands.
        t.registerMethodRedirect(OLD, "color", "()L" + OLD + ";", NEW, "teamColor", "()L" + NEW + ";");
        // The argument now parses a TeamColor, whose sixteen constants share ChatFormatting's color names.
        t.registerSyntheticClass(HELPER, generate());
        t.registerMethodRedirect(OLD, "getColor", GET_COLOR, HELPER, "getColor", GET_COLOR);
    }

    /** {@code getColor(context, name)}: the parsed team color as the formatting constant of the same name. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getColor", GET_COLOR, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, NEW, "getTeamColor",
                "(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)L" + TEAM_COLOR + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKESTATIC, CHAT_FORMATTING, "valueOf", "(Ljava/lang/String;)L" + CHAT_FORMATTING + ";",
                false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
