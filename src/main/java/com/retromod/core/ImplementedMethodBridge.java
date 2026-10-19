/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

/**
 * A method each implementor of {@code iface} gets, which calls {@code targetName} on itself.
 *
 * @param iface      the interface whose implementors or subinterfaces get the method
 * @param name       the method name, usually an erased method of a shared type
 * @param desc       the method descriptor
 * @param targetName the method it calls, declared by {@code iface}
 * @param targetDesc the descriptor it calls
 * @param sources    for each target parameter, the index of the bridge parameter passed to it, or
 *                   null to pass every parameter in order
 */
public record ImplementedMethodBridge(String iface, String name, String desc, String targetName,
        String targetDesc, List<Integer> sources) {

    public ImplementedMethodBridge {
        int bridgeCount = Type.getArgumentTypes(desc).length;
        int targetCount = Type.getArgumentTypes(targetDesc).length;
        if (sources == null && bridgeCount != targetCount) {
            throw new IllegalArgumentException(iface + "." + name + desc + " and " + targetName
                    + targetDesc + " differ in parameter count");
        }
        if (sources != null) {
            sources = List.copyOf(sources);
            if (sources.size() != targetCount
                    || sources.stream().anyMatch(index -> index < 0 || index >= bridgeCount)) {
                throw new IllegalArgumentException(iface + "." + name + desc
                        + " needs one bridge parameter for each parameter of " + targetName + targetDesc);
            }
        }
    }

    /** A bridge that passes every parameter in order. */
    public ImplementedMethodBridge(String iface, String name, String desc, String targetName,
            String targetDesc) {
        this(iface, name, desc, targetName, targetDesc, null);
    }

    /** Writes the method into the class {@code out} is building. */
    void emit(ClassVisitor out) {
        // Marked as a bridge, as javac marks its own erasure bridges, so tools skip it.
        MethodVisitor mv = out.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC
                | Opcodes.ACC_BRIDGE, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Type[] params = Type.getArgumentTypes(desc);
        Type[] targetParams = Type.getArgumentTypes(targetDesc);
        int[] slots = new int[params.length];
        int slot = 1;
        for (int i = 0; i < params.length; i++) {
            slots[i] = slot;
            slot += params[i].getSize();
        }
        // Each target parameter loads the bridge parameter it comes from, cast where they differ.
        for (int i = 0; i < targetParams.length; i++) {
            int source = sources == null ? i : sources.get(i);
            mv.visitVarInsn(params[source].getOpcode(Opcodes.ILOAD), slots[source]);
            if (needsCast(params[source], targetParams[i])) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, targetParams[i].getInternalName());
            }
        }
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, iface, targetName, targetDesc, true);

        // A void bridge drops what the target returns; any other return passes through.
        Type result = Type.getReturnType(desc);
        Type targetResult = Type.getReturnType(targetDesc);
        if (result.getSort() == Type.VOID && targetResult.getSort() != Type.VOID) {
            mv.visitInsn(targetResult.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        } else if (needsCast(targetResult, result)) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, result.getInternalName());
        }
        mv.visitInsn(result.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean needsCast(Type from, Type to) {
        boolean reference = to.getSort() == Type.OBJECT || to.getSort() == Type.ARRAY;
        return reference && !to.equals(from) && !"java/lang/Object".equals(to.getInternalName());
    }
}
