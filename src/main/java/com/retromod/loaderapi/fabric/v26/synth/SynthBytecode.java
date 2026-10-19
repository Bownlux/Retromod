/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.synth;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Small bytecode steps the spec generators share. */
final class SynthBytecode {

    private SynthBytecode() {}

    /** Local variable slots of each argument, starting at {@code first}. */
    static int[] slots(Type[] arguments, int first) {
        int[] slots = new int[arguments.length];
        int slot = first;
        for (int i = 0; i < arguments.length; i++) {
            slots[i] = slot;
            slot += arguments[i].getSize();
        }
        return slots;
    }

    /**
     * Casts the reference on the stack from {@code from} to {@code to}. Primitive types must match,
     * because a generator never boxes; a mismatch is a spec error.
     */
    static void castIfNeeded(MethodVisitor mv, Type from, Type to) {
        if (from.equals(to)) return;
        boolean fromReference = from.getSort() == Type.OBJECT || from.getSort() == Type.ARRAY;
        boolean toReference = to.getSort() == Type.OBJECT || to.getSort() == Type.ARRAY;
        if (!fromReference || !toReference) {
            throw new IllegalArgumentException("Cannot adapt " + from + " to " + to
                    + " without boxing; use a converted argument instead");
        }
        if (!"java/lang/Object".equals(to.getInternalName())) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, to.getSort() == Type.ARRAY
                    ? to.getDescriptor() : to.getInternalName());
        }
    }

    /** Pushes null, zero or false for {@code type}; nothing for void. */
    static void pushDefault(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> { }
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> mv.visitInsn(Opcodes.ICONST_0);
            case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
            default -> mv.visitInsn(Opcodes.ACONST_NULL);
        }
    }

    /** Pops a value of {@code type} from the stack; nothing for void. */
    static void pop(MethodVisitor mv, Type type) {
        if (type.getSize() == 2) mv.visitInsn(Opcodes.POP2);
        else if (type.getSize() == 1) mv.visitInsn(Opcodes.POP);
    }
}
