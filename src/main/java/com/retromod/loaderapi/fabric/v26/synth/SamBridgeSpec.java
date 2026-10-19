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
import java.util.Objects;

/**
 * An interface that keeps an old abstract method and implements a host method by adapting arguments.
 *
 * @param name            internal name of the generated interface
 * @param superInterfaces interfaces it extends, usually the host interface
 * @param oldSam          the old method name, kept abstract
 * @param oldDesc         the old method descriptor, in 26.x Mojang names
 * @param hostSam         the host method name, generated as a default
 * @param hostDesc        the host method descriptor
 * @param args            one source per old parameter, in order
 * @param result          how the old method's result becomes the host method's result
 */
public record SamBridgeSpec(String name, List<String> superInterfaces, String oldSam,
        String oldDesc, String hostSam, String hostDesc, List<Arg> args, Result result) {

    /** Where one old argument comes from. */
    public sealed interface Arg permits HostArg, NullArg, ConvertedArg {}

    /** Host argument {@code index}, cast to the old parameter type when they differ. */
    public record HostArg(int index) implements Arg {}

    /** {@code null}, zero or false, for a parameter the host no longer passes. */
    public record NullArg() implements Arg {}

    /** Host argument {@code index} passed through a static method {@code owner.name(desc)}. */
    public record ConvertedArg(int index, String owner, String name, String desc, boolean itf)
            implements Arg {}

    /** How the old result is returned from the host method. */
    public enum Result {
        /** Return it as it is, cast to the host return type when they differ. */
        SAME,
        /** Discard it; the host method returns nothing. */
        DISCARD,
        /** The old method returns nothing; return null, zero or false. */
        DEFAULT
    }

    public SamBridgeSpec {
        Objects.requireNonNull(name, "name");
        superInterfaces = List.copyOf(superInterfaces);
        args = List.copyOf(args);
        if (args.size() != Type.getArgumentTypes(oldDesc).length) {
            throw new IllegalArgumentException(name + ": expected one source per parameter of "
                    + oldDesc + ", got " + args.size());
        }
    }

    /** Same arguments in the same order, so only the method name or erased types differ. */
    public static List<Arg> passThrough(String desc) {
        Type[] types = Type.getArgumentTypes(desc);
        Arg[] args = new Arg[types.length];
        for (int i = 0; i < types.length; i++) args[i] = new HostArg(i);
        return List.of(args);
    }

    /** Generates the interface. */
    public byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                name, null, "java/lang/Object", superInterfaces.toArray(new String[0]));

        // The old method stays the single abstract one, so lambdas keep linking.
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, oldSam, oldDesc, null, null)
                .visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, hostSam, hostDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Type[] hostTypes = Type.getArgumentTypes(hostDesc);
        Type[] oldTypes = Type.getArgumentTypes(oldDesc);
        int[] hostSlots = SynthBytecode.slots(hostTypes, 1);
        for (int i = 0; i < args.size(); i++) {
            loadArg(mv, args.get(i), oldTypes[i], hostTypes, hostSlots);
        }
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, name, oldSam, oldDesc, true);
        returnResult(mv, Type.getReturnType(oldDesc), Type.getReturnType(hostDesc));
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void loadArg(MethodVisitor mv, Arg arg, Type oldType, Type[] hostTypes,
            int[] hostSlots) {
        if (arg instanceof HostArg host) {
            Type hostType = hostTypes[host.index()];
            mv.visitVarInsn(hostType.getOpcode(Opcodes.ILOAD), hostSlots[host.index()]);
            SynthBytecode.castIfNeeded(mv, hostType, oldType);
        } else if (arg instanceof ConvertedArg converted) {
            Type hostType = hostTypes[converted.index()];
            mv.visitVarInsn(hostType.getOpcode(Opcodes.ILOAD), hostSlots[converted.index()]);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, converted.owner(), converted.name(),
                    converted.desc(), converted.itf());
            SynthBytecode.castIfNeeded(mv, Type.getReturnType(converted.desc()), oldType);
        } else {
            SynthBytecode.pushDefault(mv, oldType);
        }
    }

    private void returnResult(MethodVisitor mv, Type oldReturn, Type hostReturn) {
        // A host method that returns nothing drops the old result whatever the mode.
        if (result == Result.SAME && hostReturn.getSort() != Type.VOID) {
            SynthBytecode.castIfNeeded(mv, oldReturn, hostReturn);
        } else {
            SynthBytecode.pop(mv, oldReturn);
            if (result == Result.DEFAULT) SynthBytecode.pushDefault(mv, hostReturn);
        }
        mv.visitInsn(hostReturn.getOpcode(Opcodes.IRETURN));
    }
}
