/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a stand-in base class for a Minecraft class that mods extend and that a later version
 * deleted.
 *
 * <p>A deleted base is not the same problem as a moved one. A move is a rename, and
 * {@link RetromodTransformer#registerClassRedirect} handles it. A deletion leaves nothing with the
 * old constructors, so the {@code super(...)} call in every subclass has nowhere to land. The
 * subclass then fails at class load or at verification no matter how the {@code extends} slot is
 * rewritten.
 *
 * <p>The generated class extends a surviving base and declares one constructor per legacy
 * descriptor. Each constructor forwards to the surviving base's constructor by matching argument
 * types, and drops the arguments the new base has no use for. Pair it with
 * {@link RetromodTransformer#registerSuperclassRebase(String, String)}, which moves both the
 * {@code extends} edge and the {@code super(...)} owner onto the generated class while keeping the
 * descriptor, which is exactly what these constructors are shaped for.
 *
 * <p>The limit is worth stating plainly. This restores linkage, not behavior. Arguments the modern
 * base does not take are discarded, so whatever the old base did with them is gone. Use it for a
 * base whose remaining job is registration or identity, and write a real adapter when the dropped
 * arguments carried behavior.
 */
public final class LegacyBaseSynthesizer {

    private LegacyBaseSynthesizer() {}

    /** The JVM's limit on a method's argument slots, counting {@code this}. */
    private static final int MAX_CTOR_ARGUMENT_SLOTS = 255;

    /**
     * Generate a base class that extends {@code modernBase} and absorbs each legacy constructor.
     *
     * @param generatedName    internal name to generate, under a Retromod-owned package
     * @param modernBase       internal name of the surviving base to extend
     * @param modernCtorDesc   descriptor of the {@code modernBase} constructor to call
     * @param legacyCtorDescs  descriptors the subclasses' {@code super(...)} calls use
     * @return class bytes
     * @throws IllegalArgumentException when a legacy descriptor cannot supply the modern
     *         constructor's arguments, which means the pairing is wrong and must not be shipped
     */
    public static byte[] generate(String generatedName, String modernBase, String modernCtorDesc,
            List<String> legacyCtorDescs) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                generatedName, null, modernBase, null);

        Type[] modernArgs = Type.getArgumentTypes(modernCtorDesc);
        boolean generatedModern = false;
        for (String legacyDesc : legacyCtorDescs) {
            if (legacyDesc.equals(modernCtorDesc)) generatedModern = true;
            emitForwardingCtor(cw, generatedName, modernBase, modernCtorDesc, modernArgs, legacyDesc);
        }
        // A subclass compiled against a version where the base already had the modern shape calls
        // it directly, so keep it available even when it was not listed.
        if (!generatedModern) {
            emitForwardingCtor(cw, generatedName, modernBase, modernCtorDesc, modernArgs,
                    modernCtorDesc);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Whether a descriptor read out of a mod is usable as a constructor on the generated base.
     *
     * <p>The descriptor comes from the mod's own {@code super(...)} call, so it is attacker text
     * that only has to satisfy ASM's parser, not the JVM's rules. A constructor may take at most
     * 255 argument slots including {@code this}, and a wide argument takes two, so a mod declaring
     * three hundred int arguments would otherwise produce a base the JVM rejects outright with
     * {@code ClassFormatError}. That base is shared by every mod extending the same removed class,
     * so one bad jar would break all of them.
     */
    public static boolean isUsableCtorDescriptor(String descriptor) {
        if (descriptor == null) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(descriptor);
            if (!Type.getReturnType(descriptor).equals(Type.VOID_TYPE)) return false;
        } catch (RuntimeException malformed) {
            return false;
        }
        int slots = 1; // this
        for (Type arg : args) {
            if (arg.getSort() == Type.VOID) return false;
            slots += arg.getSize();
            if (slots > MAX_CTOR_ARGUMENT_SLOTS) return false;
        }
        return true;
    }

    private static void emitForwardingCtor(ClassWriter cw, String generatedName, String modernBase,
            String modernCtorDesc, Type[] modernArgs, String legacyDesc) {
        if (!isUsableCtorDescriptor(legacyDesc)) {
            throw new IllegalArgumentException("Not a usable constructor descriptor: " + legacyDesc);
        }
        Type[] legacyArgs = Type.getArgumentTypes(legacyDesc);
        int[] slots = matchArguments(modernArgs, legacyArgs);
        if (slots == null) {
            throw new IllegalArgumentException("Cannot forward " + generatedName + legacyDesc
                    + " to " + modernBase + modernCtorDesc
                    + ": the legacy arguments do not supply the modern ones");
        }

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", legacyDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (int i = 0; i < slots.length; i++) {
            mv.visitVarInsn(modernArgs[i].getOpcode(Opcodes.ILOAD), slots[i]);
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, modernBase, "<init>", modernCtorDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Local-variable slot for each modern argument, taken from the legacy argument list in order.
     *
     * <p>Matching is by exact type and left to right, so a legacy list that carries the modern
     * arguments in their original order works and one that reorders or retypes them is refused.
     * Guessing across a type change is how a linkage fix turns into a silent behavior change.
     *
     * @return one slot per modern argument, or null when the legacy list cannot supply them
     */
    private static int[] matchArguments(Type[] modernArgs, Type[] legacyArgs) {
        List<Integer> slots = new ArrayList<>(modernArgs.length);
        int next = 0;
        int slot = 1; // slot 0 is this
        for (Type legacyArg : legacyArgs) {
            if (next < modernArgs.length && legacyArg.equals(modernArgs[next])) {
                slots.add(slot);
                next++;
            }
            slot += legacyArg.getSize();
        }
        if (next != modernArgs.length) return null;

        int[] result = new int[slots.size()];
        for (int i = 0; i < result.length; i++) result[i] = slots.get(i);
        return result;
    }
}
