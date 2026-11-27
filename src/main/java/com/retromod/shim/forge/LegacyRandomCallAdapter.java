/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.OldRenderShapes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Moves a 1.18 mod's calls, overrides and method references that pass {@code java.util.Random} onto the 1.19 {@code RandomSource} methods. */
public final class LegacyRandomCallAdapter {

    static final String RANDOM = LegacyRandomSourceAdapter.RANDOM;
    static final String RANDOM_SOURCE = LegacyRandomSourceAdapter.RANDOM_SOURCE;
    private static final String SOURCE_TYPE = "net/minecraft/util/RandomSource";
    private static final String VIEW = LegacyRandomSourceAdapter.VIEW;
    static final String LOG = "com/retromod/shim/common/embedded/LegacyLog";
    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    /** Suffix of the bridge a method reference is pointed at. */
    static final String LAMBDA_BRIDGE = "$retromodRandom";

    private LegacyRandomCallAdapter() {}

    /** Post-remap pass over one class; nothing changes where the host lacks the {@code RandomSource} method. */
    public static byte[] apply(byte[] classBytes, RetromodTransformer transformer) {
        if (classBytes == null || !com.retromod.shim.common.LegacyWidgetInputAdapter.mentions(classBytes, RANDOM)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if (SyntheticEmbedder.isRetromodClass(node.name) || node.name.startsWith("net/minecraft/") || OldRenderShapes.isMixin(node)) {
            return classBytes;
        }
        Context context = new Context(node, transformer);
        int changes = 0;
        for (MethodNode method : new ArrayList<>(node.methods)) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    changes += retargetCall(context, method, call);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    changes += retargetMethodReference(context, indy);
                }
            }
        }
        changes += addOverrideBridges(context);
        if (changes == 0) return classBytes;
        // The added code has no branches, and each added handler carries its own frame, so the class's frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** What one class's pass needs to look things up. */
    private record Context(ClassNode node, RetromodTransformer transformer) {}

    /** A call to a host method with an old Random descriptor goes to the RandomSource method, converting each value. */
    private static int retargetCall(Context context, MethodNode method, MethodInsnNode call) {
        if (!call.desc.contains(RANDOM) || call.name.startsWith("<")) return 0;
        String base = hostOwner(context.node(), call.owner, call.name, call.desc);
        if (base == null) return 0;
        String newDesc = retyped(call.desc);
        String hostName = hostMethod(context.transformer(), base, call.name, newDesc);
        if (hostName == null) return 0;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        // Each Random argument becomes a RandomSource; the stack is spilled to locals so any position can be converted.
        if (containsRandom(arguments)) {
            InsnList before = new InsnList();
            int first = method.maxLocals;
            int[] slots = new int[arguments.length];
            int next = first;
            for (int i = 0; i < arguments.length; i++) {
                slots[i] = next;
                next += arguments[i].getSize();
            }
            for (int i = arguments.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ISTORE), slots[i]));
            }
            for (int i = 0; i < arguments.length; i++) {
                before.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slots[i]));
                if (arguments[i].getDescriptor().equals(RANDOM)) before.add(toSource());
            }
            method.instructions.insertBefore(call, before);
            method.maxLocals = next;
        }
        call.name = hostName;
        call.desc = newDesc;
        // A RandomSource the old method returned as a Random reaches the mod's code through the view.
        if (Type.getReturnType(newDesc).getDescriptor().equals(RANDOM_SOURCE)) method.instructions.insert(call, toView());
        return 1;
    }

    /** A lambda or method reference for a host interface whose method now takes a RandomSource. */
    private static int retargetMethodReference(Context context, InvokeDynamicInsnNode indy) {
        if (!METAFACTORY.equals(indy.bsm.getOwner()) || indy.bsmArgs.length < 3
                || !(indy.bsmArgs[0] instanceof Type sam) || !(indy.bsmArgs[1] instanceof Handle impl)
                || !(indy.bsmArgs[2] instanceof Type instantiated) || !sam.getDescriptor().contains(RANDOM)) {
            return 0;
        }
        String iface = Type.getReturnType(indy.desc).getInternalName();
        String newSam = retyped(sam.getDescriptor());
        String samName = iface.startsWith("net/minecraft/") ? hostMethod(context.transformer(), iface, indy.name, newSam) : null;
        if (samName == null) return 0;
        Handle target = bridgeFor(context, impl);
        if (target == null) return 0;
        indy.name = samName;
        indy.bsmArgs[0] = Type.getMethodType(newSam);
        indy.bsmArgs[1] = target;
        indy.bsmArgs[2] = Type.getMethodType(retyped(instantiated.getDescriptor()));
        return 1;
    }

    /** The handle a retyped reference calls: the host's own RandomSource method, or a bridge to the mod's method. */
    private static Handle bridgeFor(Context context, Handle impl) {
        String newDesc = retyped(impl.getDesc());
        if (impl.getOwner().startsWith("net/minecraft/")) {
            String hostName = hostMethod(context.transformer(), impl.getOwner(), impl.getName(), newDesc);
            return hostName == null ? null : new Handle(impl.getTag(), impl.getOwner(), hostName, newDesc, impl.isInterface());
        }
        boolean isStatic = impl.getTag() == Opcodes.H_INVOKESTATIC;
        if (!impl.getOwner().equals(context.node().name) || !(isStatic || impl.getTag() == Opcodes.H_INVOKEVIRTUAL
                || impl.getTag() == Opcodes.H_INVOKESPECIAL)) {
            return null;
        }
        String bridgeName = impl.getName() + LAMBDA_BRIDGE;
        if (find(context.node(), bridgeName, newDesc) == null) {
            int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | (isStatic ? Opcodes.ACC_STATIC : 0);
            context.node().methods.add(bridge(context.node(), access, bridgeName, newDesc, impl.getName(), impl.getDesc(),
                    isStatic ? Opcodes.INVOKESTATIC : (impl.getTag() == Opcodes.H_INVOKESPECIAL ? Opcodes.INVOKESPECIAL
                            : Opcodes.INVOKEVIRTUAL)));
        }
        int tag = isStatic ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKESPECIAL;
        return new Handle(tag, context.node().name, bridgeName, newDesc, (context.node().access & Opcodes.ACC_INTERFACE) != 0);
    }

    /** An instance method that overrode a host method with an old Random descriptor also answers the RandomSource one. */
    private static int addOverrideBridges(Context context) {
        ClassNode node = context.node();
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return 0;
        int added = 0;
        for (MethodNode method : new ArrayList<>(node.methods)) {
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || method.name.startsWith("<")
                    || method.name.endsWith(LAMBDA_BRIDGE) || !method.desc.contains(RANDOM)) {
                continue;
            }
            String newDesc = retyped(method.desc);
            String hostName = null;
            for (String base : hostSupertypes(node)) {
                hostName = hostMethod(context.transformer(), base, method.name, newDesc);
                if (hostName != null) break;
            }
            if (hostName == null || find(node, hostName, newDesc) != null) continue;
            int access = (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_SYNTHETIC;
            MethodNode bridge = bridge(node, access, hostName, newDesc, method.name, method.desc, Opcodes.INVOKEVIRTUAL);
            // The old override never ran before, so a member it reaches that the host lacks falls back to the host's own.
            // That needs a body to fall back to: an abstract or interface method has none.
            if ((node.version & 0xFFFF) >= Opcodes.V1_7 && concreteAbove(node, hostName, newDesc)) {
                fallBackToSuper(node, bridge);
            }
            node.methods.add(bridge);
            added++;
        }
        return added;
    }

    /** The first Minecraft class above {@code node} through the jar's own classes, then the Minecraft interfaces it lists. */
    private static List<String> hostSupertypes(ClassNode node) {
        List<String> types = new ArrayList<>();
        String superclass = hostSuperclass(node);
        if (superclass != null) types.add(superclass);
        for (String iface : node.interfaces) {
            if (iface.startsWith("net/minecraft/")) types.add(iface);
        }
        return types;
    }

    /** The first Minecraft class above {@code node} through the jar's own classes, or null. */
    private static String hostSuperclass(ClassNode node) {
        String current = node.superName;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current.startsWith("net/minecraft/")) return current;
            ClassNode parent = RetromodTransformer.readCurrentJarClassShapeForAdapter(current);
            if (parent == null) return null;
            current = parent.superName;
        }
        return null;
    }

    /** Whether a Minecraft superclass of {@code node} has a body for the host method, so a super call can reach it. */
    private static boolean concreteAbove(ClassNode node, String hostName, String desc) {
        String type = hostSuperclass(node);
        for (int depth = 0; type != null && !type.equals("java/lang/Object") && depth < 32; depth++) {
            ClassNode host = ClassResourceInspector.read(type);
            if (host == null) return false;
            MethodNode declared = find(host, hostName, desc);
            if (declared != null) return (declared.access & Opcodes.ACC_ABSTRACT) == 0;
            type = host.superName;
        }
        return false;
    }

    /** The Minecraft class a call on {@code owner} reaches, or null when a class of the jar declares the method itself. */
    private static String hostOwner(ClassNode self, String owner, String name, String desc) {
        String current = owner;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current.startsWith("net/minecraft/")) return current;
            ClassNode type = current.equals(self.name) ? self : RetromodTransformer.readCurrentJarClassShapeForAdapter(current);
            if (type == null || find(type, name, desc) != null) return null;
            current = type.superName;
        }
        return null;
    }

    /** {@code name(desc)} converting each RandomSource argument to a view, calling {@code target(targetDesc)}, and converting its result back. */
    private static MethodNode bridge(ClassNode owner, int access, String name, String desc, String target, String targetDesc,
            int invoke) {
        MethodNode method = new MethodNode(access, name, desc, null, null);
        method.visitCode();
        int slot = 0;
        if ((access & Opcodes.ACC_STATIC) == 0) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
        }
        for (Type argument : Type.getArgumentTypes(desc)) {
            method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            if (argument.getDescriptor().equals(RANDOM_SOURCE)) method.instructions.add(toView());
            slot += argument.getSize();
        }
        method.visitMethodInsn(invoke, owner.name, target, targetDesc, (owner.access & Opcodes.ACC_INTERFACE) != 0);
        Type result = Type.getReturnType(targetDesc);
        if (result.getDescriptor().equals(RANDOM)) method.instructions.add(toSource());
        method.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        method.visitMaxs(0, 0);
        method.visitEnd();
        return method;
    }

    /** Wraps the bridge so a {@link LinkageError} logs once and returns the superclass's {@code name(desc)} instead. */
    private static void fallBackToSuper(ClassNode owner, MethodNode bridge) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        bridge.instructions.insert(start);
        bridge.instructions.add(end);
        bridge.instructions.add(handler);
        // The handler starts with the bridge's arguments as locals and the error on the stack.
        Type[] arguments = Type.getArgumentTypes(bridge.desc);
        List<Object> locals = new ArrayList<>(List.of(owner.name));
        for (Type argument : arguments) locals.add(frameType(argument));
        bridge.instructions.add(new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(), 1,
                new Object[]{"java/lang/LinkageError"}));
        bridge.instructions.add(new InsnNode(Opcodes.POP));
        bridge.instructions.add(new LdcInsnNode(owner.name + "." + bridge.name + bridge.desc));
        bridge.instructions.add(new LdcInsnNode("An old " + bridge.name + " override in " + owner.name.replace('/', '.')
                + " reached a member this Minecraft version lacks, so the game's own " + bridge.name + " runs instead"));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOG, "warnOnce", "(Ljava/lang/String;Ljava/lang/String;)Z", false));
        bridge.instructions.add(new InsnNode(Opcodes.POP));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (Type argument : arguments) {
            bridge.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.superName, bridge.name, bridge.desc, false));
        bridge.instructions.add(new InsnNode(Type.getReturnType(bridge.desc).getOpcode(Opcodes.IRETURN)));
        bridge.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/LinkageError"));
    }

    /** A value's type as a stack map frame names it. */
    private static Object frameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            default -> type.getInternalName();
        };
    }

    private static InsnList toSource() {
        InsnList list = new InsnList();
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, VIEW, "source", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, SOURCE_TYPE));
        return list;
    }

    private static MethodInsnNode toView() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, VIEW, "wrap", "(Ljava/lang/Object;)" + RANDOM, false);
    }

    static String retyped(String desc) {
        return desc.replace(RANDOM, RANDOM_SOURCE);
    }

    private static boolean containsRandom(Type[] arguments) {
        for (Type argument : arguments) {
            if (argument.getDescriptor().equals(RANDOM)) return true;
        }
        return false;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    /** The host's name for {@code name(desc)} on {@code owner} or a supertype, or null when the host has none. */
    static String hostMethod(RetromodTransformer transformer, String owner, String name, String desc) {
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty() && seen.size() < 64) {
            String type = pending.poll();
            if (!seen.add(type) || type.equals("java/lang/Object")) continue;
            ClassNode host = ClassResourceInspector.read(type);
            if (host == null) continue;
            String hostName = transformer.remapQualifiedMethodName(type, name, desc);
            if (find(host, hostName, desc) != null) return hostName;
            if (host.superName != null) pending.add(host.superName);
            pending.addAll(host.interfaces);
        }
        return null;
    }
}
