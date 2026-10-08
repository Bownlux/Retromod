/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Post-remap repairs for Forge capability code in a mod migrated to NeoForge.
 *
 * <ul>
 *   <li>{@code holder.getCapability(cap[, side])} named a method Forge added to every entity, item
 *       stack, block entity and level. NeoForge has no such method, so the call goes to
 *       {@link LegacyCapabilityRuntime#get}, which asks the holder's own override and then the
 *       attached providers. A {@code super.getCapability(...)} inside a mod override goes to
 *       {@code getAttached} instead, which skips the override and so cannot recurse.</li>
 *   <li>Forge filtered {@code AttachCapabilitiesEvent<Entity>} listeners by their type argument;
 *       NeoForge's bus has no generic events. Each such listener gets a leading
 *       {@code if (!(event.getObject() instanceof Entity)) return;}, so an item-stack attach no
 *       longer reaches an entity listener and fails its cast.</li>
 * </ul>
 */
public final class LegacyCapabilityCallAdapter {

    private static final String CAP = LegacyCapabilitySynthetics.CAPABILITY;
    private static final String LAZY = LegacyCapabilitySynthetics.LAZY_OPTIONAL;
    private static final String EVENT = LegacyCapabilitySynthetics.ATTACH_EVENT;
    private static final String RUNTIME = LegacyCapabilitySynthetics.RUNTIME;

    private static final String GET_WITH_SIDE = "(L" + CAP + ";Lnet/minecraft/core/Direction;)L" + LAZY + ";";
    private static final String GET_NO_SIDE = "(L" + CAP + ";)L" + LAZY + ";";
    private static final String RUNTIME_WITH_SIDE = "(Ljava/lang/Object;L" + CAP + ";Ljava/lang/Object;)L" + LAZY + ";";
    private static final String RUNTIME_NO_SIDE = "(Ljava/lang/Object;L" + CAP + ";)L" + LAZY + ";";

    private LegacyCapabilityCallAdapter() {}

    /** Repair {@code classBytes} when the capability bridge is active, reading the mod jar for supertypes. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, RetromodTransformer::readCurrentJarClassForAdapter);
    }

    static byte[] apply(byte[] classBytes, Function<String, byte[]> jarClasses) {
        if (!mentionsCapabilities(classBytes)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= rewriteGetCapabilityCalls(method, jarClasses);
            changed |= guardGenericAttachListener(node, method);
        }
        if (!changed) return classBytes;
        // Frames stay expanded and correct: the only new branch target gets an explicit frame.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean mentionsCapabilities(byte[] classBytes) {
        String constants = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        return constants.contains(CAP) || constants.contains(EVENT);
    }

    private static boolean rewriteGetCapabilityCalls(MethodNode method, Function<String, byte[]> jarClasses) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || !call.name.equals("getCapability")) continue;
            boolean withSide = call.desc.equals(GET_WITH_SIDE);
            if (!withSide && !call.desc.equals(GET_NO_SIDE)) continue;
            String target;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                target = "get";
            } else if (call.getOpcode() == Opcodes.INVOKESPECIAL
                    && !declaredInJarHierarchy(call.owner, call.name, call.desc, jarClasses)) {
                target = "getAttached";
            } else {
                continue; // provider interface calls and real super implementations stay as they are
            }
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, target,
                    withSide ? RUNTIME_WITH_SIDE : RUNTIME_NO_SIDE, false));
            changed = true;
        }
        return changed;
    }

    private static boolean declaredInJarHierarchy(String owner, String name, String desc,
            Function<String, byte[]> jarClasses) {
        Set<String> seen = new HashSet<>();
        String current = owner;
        while (current != null && seen.add(current)) {
            byte[] bytes = jarClasses.apply(current);
            if (bytes == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)
                        && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                    return true;
                }
            }
            current = node.superName;
        }
        return false;
    }

    private static boolean guardGenericAttachListener(ClassNode owner, MethodNode method) {
        Type[] parameters = Type.getArgumentTypes(method.desc);
        if (parameters.length != 1 || !parameters[0].getInternalName().equals(EVENT)
                || Type.getReturnType(method.desc) != Type.VOID_TYPE
                || method.signature == null || !isSubscriber(method)) {
            return false;
        }
        String attachedType = firstTypeArgument(method.signature);
        if (attachedType == null || attachedType.equals("java/lang/Object")) return false;

        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int eventSlot = isStatic ? 0 : 1;
        LabelNode accepted = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, eventSlot));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getObject", "()Ljava/lang/Object;", false));
        guard.add(new TypeInsnNode(Opcodes.INSTANCEOF, attachedType));
        guard.add(new JumpInsnNode(Opcodes.IFNE, accepted));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(accepted);
        // A method that branches back to its first instruction already has a frame there, and
        // two frames at one offset are invalid. That frame describes the entry state too.
        if (!startsWithFrame(method)) {
            List<Object> locals = new ArrayList<>();
            if (!isStatic) locals.add(owner.name);
            locals.add(EVENT);
            guard.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]));
        }
        method.instructions.insert(guard);
        return true;
    }

    private static boolean startsWithFrame(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode) return true;
            if (insn.getOpcode() >= 0) return false;
        }
        return false;
    }

    private static boolean isSubscriber(MethodNode method) {
        if (method.visibleAnnotations == null) return false;
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.endsWith("/SubscribeEvent;")) return true;
        }
        return false;
    }

    /** The class named by the first parameter's first type argument, such as {@code Entity}. */
    static String firstTypeArgument(String methodSignature) {
        String[] result = new String[1];
        new SignatureReader(methodSignature).accept(new SignatureVisitor(Opcodes.ASM9) {
            private int parameterDepth = -1;
            private boolean inFirstParameter;
            private int depth;

            @Override
            public SignatureVisitor visitParameterType() {
                parameterDepth++;
                inFirstParameter = parameterDepth == 0;
                depth = 0;
                return this;
            }

            @Override
            public SignatureVisitor visitReturnType() {
                inFirstParameter = false;
                return this;
            }

            @Override
            public void visitClassType(String name) {
                if (inFirstParameter && depth == 1 && result[0] == null) result[0] = name;
                depth++;
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                return this;
            }

            @Override
            public void visitTypeVariable(String name) {
                if (inFirstParameter && depth == 1 && result[0] == null) result[0] = "java/lang/Object";
            }

            @Override
            public void visitEnd() {
                depth--;
            }
        });
        return result[0];
    }
}
