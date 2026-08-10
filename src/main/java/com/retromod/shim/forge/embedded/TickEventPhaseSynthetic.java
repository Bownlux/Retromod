/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Stands in for Forge's removed {@code TickEvent.Phase} enum on a NeoForge host.
 *
 * <p>NeoForge replaced Forge's single fire-twice tick event (a {@code phase} of {@code START} then
 * {@code END}) with two separate events, {@code Pre} and {@code Post}. So {@code TickEvent.Phase}
 * and the {@code phase} field are both gone, yet a Forge mod still reads
 * {@code if (event.phase == TickEvent.Phase.END)}. The event API shim redirects the mod's tick
 * listeners to the concrete {@code Post} subclass, which fires where Forge's {@code END} did, so
 * this class supplies two constants to compare against and a bridge that answers {@code END} for a
 * {@code Post} event.
 *
 * <p>Generated as a plain final class, not a real enum: the mod only needs identity comparison
 * ({@code if_acmpeq}) against {@code START}/{@code END}, and two distinct singletons give exactly
 * that without the {@code values()}/{@code valueOf()} enum scaffolding. It is embedded per-mod by
 * {@code SyntheticEmbedder} under a Retromod package, so two mods carrying it never split-package.
 */
public final class TickEventPhaseSynthetic {

    private TickEventPhaseSynthetic() {}

    /** The name the shim registers and redirects Forge's {@code TickEvent$Phase} to. */
    public static final String INTERNAL = "com/retromod/shim/forge/embedded/TickEventPhase";

    private static final String DESC = "L" + INTERNAL + ";";

    /** {@code GETFIELD ...TickEvent$*.phase} becomes {@code INVOKESTATIC of(receiver)}. */
    public static final String PHASE_OF_NAME = "of";
    public static final String PHASE_OF_DESC = "(Ljava/lang/Object;)" + DESC;

    /**
     * The write half of the field bridge. Forge's {@code phase} was {@code final}, so a write from
     * another class could not have compiled and would fail verification anyway; this exists only so
     * the bridge registration is total, and it discards its arguments.
     */
    public static final String PHASE_SET_NAME = "set";
    public static final String PHASE_SET_DESC = "(Ljava/lang/Object;" + DESC + ")V";

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);

        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "START", DESC, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "END", DESC, null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (String field : new String[]{"START", "END"}) {
            clinit.visitTypeInsn(NEW, INTERNAL);
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, INTERNAL, "<init>", "()V", false);
            clinit.visitFieldInsn(PUTSTATIC, INTERNAL, field, DESC);
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // The listener was redirected to the Post subclass, which fires only where Forge's END did,
        // so the phase of any event reaching a translated listener is END. The receiver argument
        // keeps the call stack-balanced against the field read it replaces; it is not inspected.
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, PHASE_OF_NAME, PHASE_OF_DESC, null, null);
        of.visitCode();
        of.visitFieldInsn(GETSTATIC, INTERNAL, "END", DESC);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();

        MethodVisitor set = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                PHASE_SET_NAME, PHASE_SET_DESC, null, null);
        set.visitCode();
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
