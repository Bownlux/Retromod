/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.List;

/** Gives a block or item built straight into a registration call with a constant name the id of that name while it is built. */
public final class CallSiteIdStamper {

    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final java.util.Set<String> ID_OWNERS = java.util.Set.of(IDENTIFIER, "net/minecraft/resources/ResourceLocation");
    private static final byte[] PROPERTIES = "$Properties;".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private CallSiteIdStamper() {}

    /** Stamps each {@code register(..., new Block(props), "name")} statement of one class with {@code namespace:name}. */
    public static byte[] apply(byte[] classBytes, String namespace, String bridge) {
        // A class that never builds Properties constructs no block or item.
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, PROPERTIES)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("net/minecraft/") || com.retromod.core.SyntheticEmbedder.isRetromodClass(node.name)) {
            return classBytes;
        }
        int stamped = 0;
        for (MethodNode method : node.methods) stamped += stamp(node, method, namespace, bridge);
        return stamped == 0 ? classBytes : StaticFieldIdStamper.write(classBytes, node, true);
    }

    /** One method's stamps; each wraps the statement from its first instruction to the registration call. */
    static int stamp(ClassNode owner, MethodNode method, String jarNamespace, String bridge) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;
        Frame<SourceValue>[] frames = null;
        record Stamp(AbstractInsnNode start, AbstractInsnNode call, String[] id) {}
        List<Stamp> stamps = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || !registers(call)) continue;
            if (frames == null) {
                try {
                    frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
                } catch (Throwable unanalyzable) {
                    return 0;
                }
            }
            // The helper's own id literal names the namespace best; otherwise the one the jar registers under.
            String namespace = helperNamespace(owner, call);
            String[] id = idOf(owner, method, frames, call, namespace != null ? namespace : jarNamespace);
            if (id == null) continue;
            AbstractInsnNode start = StaticFieldIdStamper.statementStart(method, frames, method.instructions.indexOf(call));
            if (start != null) stamps.add(new Stamp(start, call, id));
        }
        if (stamps.isEmpty()) return 0;
        // One spare local carries the replaced id from enter to leave; statements do not nest within a method.
        int previous = method.maxLocals;
        method.maxLocals = previous + 1;
        for (Stamp stamp : stamps) {
            InsnList enter = new InsnList();
            enter.add(new LdcInsnNode(stamp.id()[0]));
            enter.add(new LdcInsnNode(stamp.id()[1]));
            enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false));
            enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "enter", StaticFieldIdStamper.ENTER_DESC, false));
            enter.add(new VarInsnNode(Opcodes.ASTORE, previous));
            LabelNode tryStart = new LabelNode();
            enter.add(tryStart);
            method.instructions.insertBefore(stamp.start(), enter);
            InsnList leave = new InsnList();
            LabelNode tryEnd = new LabelNode();
            leave.add(tryEnd);
            leave.add(new VarInsnNode(Opcodes.ALOAD, previous));
            leave.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "leave", StaticFieldIdStamper.LEAVE_DESC, false));
            // A throwing constructor or register call leaves through this handler, so a mod that catches it never
            // keeps building with the id still set. It sits right here, inside any try of the mod's that encloses
            // the statement, so the rethrown exception still reaches that try's handler.
            LabelNode after = new LabelNode();
            LabelNode handler = new LabelNode();
            leave.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, after));
            leave.add(handler);
            leave.add(new VarInsnNode(Opcodes.ALOAD, previous));
            leave.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "leave", StaticFieldIdStamper.LEAVE_DESC, false));
            leave.add(new InsnNode(Opcodes.ATHROW));
            leave.add(after);
            method.instructions.insert(stamp.call(), leave);
            addInnermost(method, new TryCatchBlockNode(tryStart, tryEnd, handler, null));
        }
        return stamps.size();
    }

    /**
     * Adds the handler before every existing one that encloses it, so a mod's own try around the statement still
     * sees the exception only after the id is restored, and after any handler inside the statement.
     */
    private static void addInnermost(MethodNode method, TryCatchBlockNode added) {
        int start = method.instructions.indexOf(added.start);
        int end = method.instructions.indexOf(added.end);
        int position = 0;
        for (int i = 0; i < method.tryCatchBlocks.size(); i++) {
            TryCatchBlockNode existing = method.tryCatchBlocks.get(i);
            if (method.instructions.indexOf(existing.start) >= start && method.instructions.indexOf(existing.end) <= end) {
                position = i + 1;
            }
        }
        method.tryCatchBlocks.add(position, added);
    }

    /** A call that looks like a registration: a name with "register" in it, or a two-argument consumer. */
    private static boolean registers(MethodInsnNode call) {
        if (call.name.equals("<init>")) return false;
        String name = call.name.toLowerCase(java.util.Locale.ROOT);
        return name.contains("register") || (call.owner.equals("java/util/function/BiConsumer") && name.equals("accept"));
    }

    /**
     * {@code {namespace, path}} when the call takes an object built by {@code new} in the same statement and a
     * constant name, or null. A name with a colon carries its own namespace.
     */
    private static String[] idOf(ClassNode owner, MethodNode method, Frame<SourceValue>[] frames, MethodInsnNode call, String namespace) {
        int index = method.instructions.indexOf(call);
        Frame<SourceValue> frame = frames[index];
        if (frame == null) return null;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
        int base = frame.getStackSize() - arguments.length - receiver;
        if (base < 0) return null;
        String name = null;
        boolean built = false;
        for (int i = 0; i < arguments.length; i++) {
            SourceValue value = frame.getStack(base + receiver + i);
            if (value.insns.size() != 1) continue;
            AbstractInsnNode source = value.insns.iterator().next();
            if (source instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                if (name != null) return null;
                name = text;
            } else if (builtByNew(source) || builtByHelper(owner, source)) {
                built = true;
            }
        }
        if (name == null || !built || name.isEmpty()) return null;
        int colon = name.indexOf(':');
        if (colon < 0 && namespace == null) return null;
        return colon < 0 ? new String[] {namespace, name} : new String[] {name.substring(0, colon), name.substring(colon + 1)};
    }

    /** The literal namespace a same-class helper builds its id with, as {@code fromNamespaceAndPath("ns", name)}, or null. */
    private static String helperNamespace(ClassNode owner, MethodInsnNode call) {
        if (!call.owner.equals(owner.name)) return null;
        for (MethodNode helper : owner.methods) {
            if (!helper.name.equals(call.name) || !helper.desc.equals(call.desc) || helper.instructions == null) continue;
            for (AbstractInsnNode insn : helper.instructions) {
                // The factory or the two-string constructor, under the 26.x name or the old one a jar is read with.
                if (!(insn instanceof MethodInsnNode build) || !ID_OWNERS.contains(build.owner)
                        || !(build.name.equals("fromNamespaceAndPath") || build.name.equals("<init>"))
                        || !build.desc.startsWith("(Ljava/lang/String;Ljava/lang/String;)")) {
                    continue;
                }
                AbstractInsnNode path = build.getPrevious();
                AbstractInsnNode namespace = path == null ? null : path.getPrevious();
                if (namespace instanceof LdcInsnNode ldc && ldc.cst instanceof String text) return text;
            }
        }
        return null;
    }

    /** Whether the value comes from a static helper of the same class that builds exactly one object, like {@code log(color)}. */
    private static boolean builtByHelper(ClassNode owner, AbstractInsnNode source) {
        if (!(source instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.equals(owner.name)
                || Type.getReturnType(call.desc).getSort() != Type.OBJECT) {
            return false;
        }
        for (MethodNode helper : owner.methods) {
            if (!helper.name.equals(call.name) || !helper.desc.equals(call.desc) || helper.instructions == null) continue;
            // More than one object built would give each the same id, so only a helper with one is trusted.
            int built = 0;
            for (AbstractInsnNode insn : helper.instructions) {
                if (insn.getOpcode() == Opcodes.NEW || isRetromodFactory(insn)) built++;
            }
            return built == 1;
        }
        return false;
    }

    /** A constructor Retromod redirected to a generated {@code create} that builds a block or item, such as LeavesBlock's. */
    private static boolean isRetromodFactory(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode factory) || factory.getOpcode() != Opcodes.INVOKESTATIC
                || !factory.owner.startsWith("com/retromod/generated/") || !factory.name.equals("create")) {
            return false;
        }
        Type built = Type.getReturnType(factory.desc);
        return built.getSort() == Type.OBJECT && (built.getInternalName().startsWith("net/minecraft/world/level/block/")
                || built.getInternalName().startsWith("net/minecraft/world/item/"));
    }

    /** Whether the value is a new object: the copy {@code dup} makes right after its {@code new}, or a Retromod factory's result. */
    private static boolean builtByNew(AbstractInsnNode source) {
        if (source.getOpcode() == Opcodes.NEW) return true;
        // A constructor Retromod redirected, such as LeavesBlock's, now builds the object in a generated factory.
        if (isRetromodFactory(source)) return true;
        AbstractInsnNode previous = source.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return source.getOpcode() == Opcodes.DUP && previous != null && previous.getOpcode() == Opcodes.NEW;
    }
}
