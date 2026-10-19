/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.synth;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

/** A class of typed static methods that forward to compiled helpers taking {@code Object}. */
public record StaticForwardSpec(String name, List<Forward> forwards) {

    /**
     * One static method {@code name(desc)} that calls {@code targetOwner.targetName(targetDesc)}.
     * Arguments and the result are cast where the types differ; primitives must match.
     */
    public record Forward(String name, String desc, String targetOwner, String targetName,
            String targetDesc, boolean targetIsInterface) {}

    public StaticForwardSpec {
        forwards = List.copyOf(forwards);
    }

    /** Generates the class. */
    public byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null,
                "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        for (Forward forward : forwards) {
            Type[] own = Type.getArgumentTypes(forward.desc());
            Type[] target = Type.getArgumentTypes(forward.targetDesc());
            if (own.length != target.length) {
                throw new IllegalArgumentException(name + "." + forward.name()
                        + ": the target takes " + target.length + " arguments, not " + own.length);
            }
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    forward.name(), forward.desc(), null, null);
            mv.visitCode();
            int[] slots = SynthBytecode.slots(own, 0);
            for (int i = 0; i < own.length; i++) {
                mv.visitVarInsn(own[i].getOpcode(Opcodes.ILOAD), slots[i]);
                SynthBytecode.castIfNeeded(mv, own[i], target[i]);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, forward.targetOwner(), forward.targetName(),
                    forward.targetDesc(), forward.targetIsInterface());
            Type returned = Type.getReturnType(forward.targetDesc());
            Type expected = Type.getReturnType(forward.desc());
            if (expected.getSort() == Type.VOID) {
                SynthBytecode.pop(mv, returned);
            } else {
                SynthBytecode.castIfNeeded(mv, returned, expected);
            }
            mv.visitInsn(expected.getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }
}
