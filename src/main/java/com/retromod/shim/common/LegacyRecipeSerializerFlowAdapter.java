/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Adapts a 1.21.x recipe serializer object where a mod hands it on as a {@code RecipeSerializer}.
 *
 * <p>{@link LegacyRecipeSerializerShim} drops the {@code RecipeSerializer} interface from a mod's
 * serializer class, because 26.1 made that type a final class. The mod's code still passes its
 * serializer object as a {@code RecipeSerializer}: it returns one from a supplier lambda, stores
 * one in a typed field, or passes one to a helper such as
 * {@code registerSerializer(String, RecipeSerializer)}. The verifier then rejects the method with
 * {@code VerifyError: Bad return type} or a bad argument type, and the class that registers the
 * mod's recipes never loads.
 *
 * <p>Each such hand-off now goes through the bridge's {@code adapt}, which returns a real
 * {@code RecipeSerializer} built from the object's codecs, cached per object, and passes a real
 * one through unchanged. A value that is already typed as a {@code RecipeSerializer} at the
 * hand-off (a cast, a field read, a call result or an unchanged parameter) is left alone, unless
 * a branch can reach the hand-off with another value. Only a value on top of the
 * stack is adapted, which covers returns, field stores and a serializer passed as the last
 * argument. A serializer passed earlier in an argument list is not adapted and still fails
 * verification.
 */
public final class LegacyRecipeSerializerFlowAdapter {

    private LegacyRecipeSerializerFlowAdapter() {}

    static final String RECIPE_SERIALIZER = LegacyRecipeSerializerShim.RECIPE_SERIALIZER;
    private static final String L_RECIPE_SERIALIZER = "L" + RECIPE_SERIALIZER + ";";
    private static final byte[] MARKER = RECIPE_SERIALIZER.getBytes(StandardCharsets.UTF_8);

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !mentionsRecipeSerializer(classBytes)) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("net/minecraft/") || node.name.startsWith("com/retromod/")) {
            return classBytes;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= adaptHandOffs(method);
        }
        if (!changed) return classBytes;
        try {
            ClassWriter writer = new SafeClassWriter(new ClassReader(classBytes),
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException | LinkageError unwritable) {
            return classBytes;
        }
    }

    private static boolean adaptHandOffs(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return false;
        boolean returnsSerializer = Type.getReturnType(method.desc).getDescriptor()
                .equals(L_RECIPE_SERIALIZER);
        Set<LabelNode> jumpTargets = jumpTargets(method);
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!handsOffSerializerOnTop(insn, returnsSerializer)) continue;
            if (alreadySerializer(method, insn.getPrevious(), jumpTargets)) continue;
            method.instructions.insertBefore(insn, adaptTop());
            changed = true;
        }
        return changed;
    }

    /** Whether {@code insn} consumes a {@code RecipeSerializer}-typed value from the top of the stack. */
    private static boolean handsOffSerializerOnTop(AbstractInsnNode insn, boolean returnsSerializer) {
        int op = insn.getOpcode();
        if (op == Opcodes.ARETURN) return returnsSerializer;
        if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC) {
            return ((FieldInsnNode) insn).desc.equals(L_RECIPE_SERIALIZER);
        }
        if (insn instanceof MethodInsnNode call && !call.owner.equals(RECIPE_SERIALIZER)) {
            Type[] args = Type.getArgumentTypes(call.desc);
            return args.length > 0
                    && args[args.length - 1].getDescriptor().equals(L_RECIPE_SERIALIZER);
        }
        return false;
    }

    /** A value the verifier already types as a {@code RecipeSerializer}, so it needs no adapting. */
    private static boolean alreadySerializer(MethodNode method, AbstractInsnNode previous,
            Set<LabelNode> jumpTargets) {
        while (previous != null && previous.getOpcode() < 0) {
            // Another path can jump in here with a different value on the stack.
            if (jumpTargets.contains(previous)) return false;
            previous = previous.getPrevious();
        }
        if (previous == null) return false;
        if (previous instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            return isUnchangedSerializerParameter(method, load.var);
        }
        if (previous.getOpcode() == Opcodes.CHECKCAST) {
            return ((TypeInsnNode) previous).desc.equals(RECIPE_SERIALIZER);
        }
        if (previous.getOpcode() == Opcodes.ACONST_NULL) return true;
        if (previous instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.GETSTATIC
                || field.getOpcode() == Opcodes.GETFIELD)) {
            return field.desc.equals(L_RECIPE_SERIALIZER);
        }
        if (previous instanceof MethodInsnNode call) {
            return Type.getReturnType(call.desc).getDescriptor().equals(L_RECIPE_SERIALIZER);
        }
        return false;
    }

    /** A parameter declared as a {@code RecipeSerializer} that the method never overwrites. */
    private static boolean isUnchangedSerializerParameter(MethodNode method, int slot) {
        int next = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        String declared = null;
        for (Type parameter : Type.getArgumentTypes(method.desc)) {
            if (next == slot) {
                declared = parameter.getDescriptor();
                break;
            }
            next += parameter.getSize();
        }
        if (!L_RECIPE_SERIALIZER.equals(declared)) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE
                    && store.var == slot) {
                return false;
            }
        }
        return true;
    }

    private static Set<LabelNode> jumpTargets(MethodNode method) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) {
                targets.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }
        return targets;
    }

    private static InsnList adaptTop() {
        InsnList adapt = new InsnList();
        adapt.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyRecipeSerializerShim.BRIDGE,
                "adapt", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        adapt.add(new TypeInsnNode(Opcodes.CHECKCAST, RECIPE_SERIALIZER));
        return adapt;
    }

    private static boolean mentionsRecipeSerializer(byte[] bytes) {
        outer:
        for (int i = 0; i <= bytes.length - MARKER.length; i++) {
            for (int j = 0; j < MARKER.length; j++) {
                if (bytes[i + j] != MARKER[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
