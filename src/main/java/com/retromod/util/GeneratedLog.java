/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Emits the bytecode that makes Retromod's generated classes warn through the game's SLF4J logger. */
public final class GeneratedLog {

    // Standard error never reaches latest.log on Forge and NeoForge, and Fabric logs it as INFO.
    // The names come from HostLibraryNames, so the release jar's SLF4J relocation leaves them alone.
    private static final String LOGGER_FACTORY = HostLibraryNames.SLF4J_PACKAGE + "/LoggerFactory";
    private static final String LOGGER = HostLibraryNames.SLF4J_PACKAGE + "/Logger";
    private static final String MARKER_FACTORY = HostLibraryNames.SLF4J_PACKAGE + "/MarkerFactory";
    private static final String MARKER = HostLibraryNames.SLF4J_PACKAGE + "/Marker";

    private GeneratedLog() {}

    /** Pushes the {@code Retromod} logger where {@code GETSTATIC System.err} stood, in the same one stack slot. */
    public static void pushLogger(MethodVisitor mv) {
        mv.visitLdcInsn("Retromod");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LOGGER_FACTORY, "getLogger",
                "(Ljava/lang/String;)L" + LOGGER + ";", false);
    }

    /** Takes the logger and a message from the stack and warns, where {@code println} stood. */
    public static void warn(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOGGER, "warn", "(Ljava/lang/String;)V", true);
    }

    /** Pushes Retromod's detail marker, which goes between the logger and the message of {@link #detail}. */
    public static void pushDetailMarker(MethodVisitor mv) {
        mv.visitLdcInsn(RetromodLog.DETAIL_NAME);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MARKER_FACTORY, "getMarker",
                "(Ljava/lang/String;)L" + MARKER + ";", false);
    }

    /** Takes the logger, the detail marker, and a message from the stack and logs a detail line at debug level. */
    public static void detail(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOGGER, "debug",
                "(L" + MARKER + ";Ljava/lang/String;)V", true);
    }

    /** Takes the logger, a message, and a throwable from the stack and warns with its stack trace. */
    public static void warnWithCause(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOGGER, "warn",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", true);
    }
}
