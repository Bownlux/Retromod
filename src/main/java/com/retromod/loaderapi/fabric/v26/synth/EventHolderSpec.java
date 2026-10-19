/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.synth;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * A holder whose static event fields point at the host's own events, copied by reflection.
 *
 * @param name   internal name of the generated holder
 * @param fields one entry per event field
 */
public record EventHolderSpec(String name, List<EventField> fields) {

    static final String EVENT_DESC = "Lnet/fabricmc/fabric/api/event/Event;";

    /**
     * One static event field.
     *
     * @param field     the field name mods read
     * @param hostOwner binary name of the host class with the event, such as
     *                  {@code net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry}
     * @param hostField the field name on the host class
     */
    public record EventField(String field, String hostOwner, String hostField) {}

    public EventHolderSpec {
        fields = List.copyOf(fields);
    }

    /** Generates the holder class. */
    public byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null,
                "java/lang/Object", null);
        for (EventField field : fields) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    field.field(), EVENT_DESC, null, null).visitEnd();
        }

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (EventField field : fields) {
            copyEventField(clinit, field);
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // Emits: try { FIELD = (Event) Class.forName(owner).getField(src).get(null); }
    //        catch (Throwable t) { System.err.println(...); }
    private void copyEventField(MethodVisitor mv, EventField field) {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label after = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");

        mv.visitLabel(start);
        mv.visitLdcInsn(field.hostOwner());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitLdcInsn(field.hostField());
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "net/fabricmc/fabric/api/event/Event");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, name, field.field(), EVENT_DESC);
        mv.visitLabel(end);
        mv.visitJumpInsn(Opcodes.GOTO, after);

        // Frames are written by hand so generating never loads Fabric API for type merging.
        mv.visitLabel(handler);
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "toString",
                "()Ljava/lang/String;", false);
        mv.visitLdcInsn("[Retromod] Could not connect " + name.substring(name.lastIndexOf('/') + 1)
                + "." + field.field() + " to " + field.hostOwner() + "." + field.hostField()
                + "; mods using this event will not receive it: ");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false);
        mv.visitLabel(after);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
    }
}
