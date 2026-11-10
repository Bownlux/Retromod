/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.util.GeneratedLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ASM generators for the two synthetic shapes the renamed-SAM event bridges use:
 * {@link #samInterface} (old-named interface extending the new one, old SAM kept abstract,
 * new SAM as a forwarding default) and {@link #eventHolder} (old-named holder copying the
 * new holder's renamed {@code Event} fields). When reflection fails (absent/incompatible
 * fabric-api) the field stays {@code null} and a warning goes to the game log; only mods using
 * that event break.
 */
final class SamBridgeSynthetics {

    private SamBridgeSynthetics() {}

    /**
     * Synthetic SAM interface at the old name extending the new one.
     *
     * @param synth        internal name of the synthetic (redirect target)
     * @param newIface     internal name of the surviving 26.1 interface
     * @param oldSam       old SAM method name (stays the only abstract method)
     * @param newSam       new SAM method name (generated as a forwarding default)
     * @param desc         SAM descriptor in post-remap (Mojang/26.1) form, shared by old and new
     * @param eventOwner   dot-name of the class carrying the new {@code EVENT} field, or {@code null} for none
     * @param eventField   field name on {@code eventOwner}
     */
    static byte[] samInterface(String synth, String newIface, String oldSam, String newSam,
                               String desc, String eventOwner, String eventField) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                synth, null, "java/lang/Object", new String[]{newIface});

        // old SAM stays the single abstract method so LambdaMetafactory keeps linking
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, oldSam, desc, null, null).visitEnd();

        // default <newSam>(args...) { [return] <oldSam>(args...); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, newSam, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Type arg : Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, synth, oldSam, desc, true);
        mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        if (eventOwner != null) {
            visitEventField(cw, "EVENT");
            MethodVisitor cl = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            cl.visitCode();
            copyEventField(cl, synth, "EVENT", eventOwner, eventField);
            cl.visitInsn(Opcodes.RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Synthetic event-holder class at the old name; each row of {@code fields}
     * is {@code {fieldName, newOwnerDotName, newFieldName}}.
     */
    static byte[] eventHolder(String synth, String[][] fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                synth, null, "java/lang/Object", null);

        for (String[] f : fields) {
            visitEventField(cw, f[0]);
        }

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor cl = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        cl.visitCode();
        for (String[] f : fields) {
            copyEventField(cl, synth, f[0], f[1], f[2]);
        }
        cl.visitInsn(Opcodes.RETURN);
        cl.visitMaxs(0, 0);
        cl.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void visitEventField(ClassWriter cw, String name) {
        // interface fields must be final (else ClassFormatError); a final static is still assignable from <clinit>
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name,
                "Lnet/fabricmc/fabric/api/event/Event;", null, null).visitEnd();
    }

    // Emits: try { FIELD = (Event) Class.forName(owner).getField(srcField).get(null); } catch (Throwable t) { warn... }
    private static void copyEventField(MethodVisitor mv, String synth, String field,
                                       String ownerDot, String srcField) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label after = new org.objectweb.asm.Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");

        mv.visitLabel(start);
        mv.visitLdcInsn(ownerDot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitLdcInsn(srcField);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "net/fabricmc/fabric/api/event/Event");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, synth, field, "Lnet/fabricmc/fabric/api/event/Event;");
        mv.visitLabel(end);
        mv.visitJumpInsn(Opcodes.GOTO, after);

        mv.visitLabel(handler);
        // Class v61+ needs explicit stack-map frames at branch targets. Hand-emitted
        // rather than COMPUTE_FRAMES so generation never loads fabric classes for
        // common-superclass computation.
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
        GeneratedLog.pushLogger(mv);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "toString",
                "()Ljava/lang/String;", false);
        mv.visitLdcInsn("[Retromod] Could not connect " + synth.substring(synth.lastIndexOf('/') + 1)
                + "." + field + " to " + ownerDot + "." + srcField + "; mods using this event will not receive it: ");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        GeneratedLog.warn(mv);
        mv.visitLabel(after);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
    }
}
