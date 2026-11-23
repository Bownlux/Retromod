/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns {@code instanceof T} into a static {@code (Object)Z} helper call, for a rebuilt type each mod carries its own copy of. */
final class InstanceofHelperAdapter {

    /**
     * The static helper that answers the check, by owner and name. {@code dropsCasts} is for a rebuilt
     * interface the host's own objects no longer implement: its casts are removed, since the verifier
     * takes any object where an interface is expected, and its calls are redirected to static helpers.
     */
    record Helper(String owner, String name, boolean dropsCasts) {
        Helper(String owner, String name) {
            this(owner, name, false);
        }
    }

    static final String HELPER_DESC = "(Ljava/lang/Object;)Z";

    private InstanceofHelperAdapter() {}

    static byte[] apply(byte[] bytes, Map<String, Helper> helpers) {
        if (helpers.isEmpty() || !namesAny(bytes, helpers.keySet())) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= rewrite(node, method, helpers);
        }
        if (!changed) return bytes;
        // The helper leaves an int where instanceof did, so the frames and stack sizes still hold.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewrite(ClassNode node, MethodNode method, Map<String, Helper> helpers) {
        if (method.instructions.size() == 0) return false;
        Set<String> castTo = castTypes(method);
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof InvokeDynamicInsnNode lambda) {
                changed |= widenLambdaTypes(node, lambda, helpers);
                continue;
            }
            if (insn.getOpcode() == Opcodes.CHECKCAST) {
                Helper helper = helpers.get(((TypeInsnNode) insn).desc);
                if (helper != null && helper.dropsCasts()) {
                    method.instructions.remove(insn);
                    changed = true;
                }
                continue;
            }
            if (insn.getOpcode() != Opcodes.INSTANCEOF) continue;
            String type = ((TypeInsnNode) insn).desc;
            Helper helper = helpers.get(type);
            // A method that also casts to the type keeps the exact check, since the cast would fail on
            // another mod's copy. A pattern binding such as instanceof T t always compiles to a cast.
            if (helper == null || (castTo.contains(type) && !helper.dropsCasts())) continue;
            method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, helper.owner(), helper.name(),
                    HELPER_DESC, false));
            changed = true;
        }
        return changed;
    }

    /**
     * A lambda or method reference casts its arguments and result to the instantiated types, so a cast-dropping
     * interface there becomes Object, which the erased functional interface already allows. An argument is widened
     * only where the implementation takes something else, or the metafactory refuses to link; javac's own lambda
     * body in this class is widened first, so a lambda over the interface accepts the host's objects too.
     */
    private static boolean widenLambdaTypes(ClassNode node, InvokeDynamicInsnNode lambda, Map<String, Helper> helpers) {
        if (!lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || lambda.bsmArgs.length < 3
                || !(lambda.bsmArgs[1] instanceof Handle impl) || !(lambda.bsmArgs[2] instanceof Type instantiated)
                || instantiated.getSort() != Type.METHOD) {
            return false;
        }
        boolean changed = false;
        MethodNode body = lambdaBody(node, impl, helpers);
        if (body != null) {
            body.desc = widenParameters(body.desc, helpers);
            // Another indy may have widened the same body already, so the handle follows its descriptor.
            if (!body.desc.equals(impl.getDesc())) {
                impl = new Handle(impl.getTag(), impl.getOwner(), impl.getName(), body.desc, impl.isInterface());
                lambda.bsmArgs[1] = impl;
                changed = true;
            }
        }
        // The implementation's parameters: the receiver of an instance method first, then the captured arguments.
        List<Type> implParameters = new ArrayList<>();
        int tag = impl.getTag();
        if (tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_INVOKESPECIAL) {
            implParameters.add(Type.getObjectType(impl.getOwner()));
        }
        implParameters.addAll(List.of(Type.getArgumentTypes(impl.getDesc())));
        int captured = Type.getArgumentTypes(lambda.desc).length;
        Type[] arguments = instantiated.getArgumentTypes();
        for (int i = 0; i < arguments.length; i++) {
            int implIndex = captured + i;
            boolean implTakesInterface = implIndex < implParameters.size()
                    && widen(implParameters.get(implIndex), helpers) != implParameters.get(implIndex);
            if (!implTakesInterface) arguments[i] = widen(arguments[i], helpers);
        }
        Type widened = Type.getMethodType(widen(instantiated.getReturnType(), helpers), arguments);
        if (!widened.equals(instantiated)) {
            lambda.bsmArgs[2] = widened;
            changed = true;
        }
        return changed;
    }

    /** javac's synthetic lambda body in this class that {@code impl} names, or null. */
    private static MethodNode lambdaBody(ClassNode node, Handle impl, Map<String, Helper> helpers) {
        if (!impl.getOwner().equals(node.name) || !impl.getName().startsWith("lambda$")) return null;
        String widened = widenParameters(impl.getDesc(), helpers);
        for (MethodNode method : node.methods) {
            if (method.name.equals(impl.getName()) && (method.desc.equals(impl.getDesc()) || method.desc.equals(widened))
                    && (method.access & Opcodes.ACC_SYNTHETIC) != 0) {
                return method;
            }
        }
        return null;
    }

    // Calls on the interface already go to static helpers, so the body takes any object in its place.
    private static String widenParameters(String desc, Map<String, Helper> helpers) {
        Type[] parameters = Type.getArgumentTypes(desc);
        for (int i = 0; i < parameters.length; i++) parameters[i] = widen(parameters[i], helpers);
        return Type.getMethodDescriptor(Type.getReturnType(desc), parameters);
    }

    private static Type widen(Type type, Map<String, Helper> helpers) {
        if (type.getSort() != Type.OBJECT) return type;
        Helper helper = helpers.get(type.getInternalName());
        return helper != null && helper.dropsCasts() ? Type.getType(Object.class) : type;
    }

    private static Set<String> castTypes(MethodNode method) {
        Set<String> types = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.CHECKCAST) types.add(((TypeInsnNode) insn).desc);
        }
        return types;
    }

    // A cheap scan of the raw bytes, so a class that never names a registered type is not parsed.
    private static boolean namesAny(byte[] bytes, Set<String> types) {
        for (String type : types) {
            if (contains(bytes, type.getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
