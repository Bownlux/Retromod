/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.function.Consumer;

/** Lets a bridge helper for a removed method run the receiver's own override of that method first. */
final class OwnOverrideCall {

    /** The embedded {@link com.retromod.shim.common.embedded.LegacyOwnOverride}, which finds the override. */
    static final String RUNTIME = "com/retromod/shim/common/embedded/LegacyOwnOverride";

    private static final String L_RUNTIME = "L" + RUNTIME + ";";
    private static final String HANDLE = "java/lang/invoke/MethodHandle";

    private OwnOverrideCall() {}

    /** The helper method that answers a {@code super} call for helper method {@code name}. */
    static String superName(String name) {
        return "super$" + name;
    }

    /** Embeds the lookup class and routes {@code super} calls to {@code helper.name} to its twin. */
    static void register(RetromodTransformer transformer, String helper, String name, String helperDesc) {
        SyntheticEmbedder.registerClassResource(transformer, RUNTIME, OwnOverrideCall.class);
        // The override's own super call must not dispatch again, so the transformer sends it to a
        // super$ twin that only calls the host.
        transformer.registerSuperCallHelper(helper, name, helperDesc, superName(name));
    }

    /** Declares the static field that caches one lookup on the helper class. */
    static void declareLookup(ClassVisitor helperClass, String field) {
        helperClass.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, field, L_RUNTIME, null, null)
                .visitEnd();
    }

    /** Emits a call to the receiver's own {@code name} override when it declares one, else falls through. */
    static void emitOwnCall(MethodVisitor mv, String helper, String field, String name, String callDesc,
            Consumer<MethodVisitor> pushArguments) {
        // A call such as ((Mob) entity).finalizeSpawn(...) in the removed five-argument form reached
        // a mob's own override by virtual dispatch. The redirect sends it to a static helper, so the
        // helper has to make that dispatch itself. callDesc is the call as the helper sees it: the
        // receiver, then the parameters.
        Label ready = new Label();
        Label none = new Label();
        // Load the lookup cached in the helper's static field, creating it on first use.
        mv.visitFieldInsn(Opcodes.GETSTATIC, helper, field, L_RUNTIME);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNONNULL, ready);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, RUNTIME);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(name);
        mv.visitLdcInsn(Type.getMethodType(callDesc));
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RUNTIME, "<init>",
                "(Ljava/lang/String;Ljava/lang/invoke/MethodType;)V", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, helper, field, L_RUNTIME);
        mv.visitLabel(ready);
        // Find the override the receiver in local 0 declares, with callDesc's parameters after the receiver.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNTIME, "find",
                "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        // Call it with what pushArguments loads and return its result.
        pushArguments.accept(mv);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HANDLE, "invokeExact", callDesc, false);
        mv.visitInsn(Type.getReturnType(callDesc).getOpcode(Opcodes.IRETURN));
        // No override: drop the null handle and fall through to the code that follows.
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.POP);
    }

    /** Loads the helper method's own arguments, in order, for a call with the same descriptor. */
    static Consumer<MethodVisitor> ownArguments(String helperDesc) {
        return mv -> {
            int local = 0;
            for (Type argument : Type.getArgumentTypes(helperDesc)) {
                mv.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
                local += argument.getSize();
            }
        };
    }
}
