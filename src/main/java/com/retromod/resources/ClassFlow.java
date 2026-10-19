/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds where the values a mod's code passes around come from, within one class. */
final class ClassFlow {

    // Some resources an old mod needs were never files: a spawn egg's colors and an armor item's
    // texture were constants in its code. Converters ask this class which instruction made an
    // argument, so a color reached through a lambda or a helper is found like one written inline.
    final ClassNode node;
    private final Map<MethodNode, Frame<SourceValue>[]> frames = new IdentityHashMap<>();

    private ClassFlow(ClassNode node) {
        this.node = node;
    }

    /** The class in {@code bytes}, or null when it does not parse. */
    static ClassFlow parse(byte[] bytes) {
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (RuntimeException malformed) {
            return null;
        }
        return new ClassFlow(node);
    }

    /** Whether a class name is one of Retromod's own, which the runtime embeds into mods. */
    static boolean isRetromodClass(String entryName) {
        return entryName.startsWith("com/retromod/") || entryName.contains("/com/retromod/");
    }

    /** The operand stack before each instruction, or null when the method cannot be analyzed. */
    Frame<SourceValue>[] frames(MethodNode method) {
        if (frames.containsKey(method)) return frames.get(method);
        Frame<SourceValue>[] result;
        try {
            result = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
        } catch (AnalyzerException | RuntimeException unanalyzable) {
            result = null;
        }
        // A method that cannot be analyzed is remembered as null, so it is not tried again.
        frames.put(method, result);
        return result;
    }

    /** The value a call gets as declared argument {@code index}, without the receiver, or null. */
    SourceValue argument(MethodNode method, AbstractInsnNode call, int index) {
        String descriptor;
        if (call instanceof MethodInsnNode invoke) {
            descriptor = invoke.desc;
        } else if (call instanceof InvokeDynamicInsnNode dynamic) {
            descriptor = dynamic.desc;
        } else {
            return null;
        }
        // Null also means the call is unreachable or the method cannot be analyzed.
        Frame<SourceValue>[] all = frames(method);
        if (all == null) return null;
        Frame<SourceValue> frame = all[method.instructions.indexOf(call)];
        int arguments = Type.getArgumentTypes(descriptor).length;
        if (frame == null || index < 0 || index >= arguments) return null;
        // The arguments are the top entries of the stack before the call, in declared order.
        return frame.getStack(frame.getStackSize() - arguments + index);
    }

    /** The object an instance call is made on, or null when the call is unreachable or unanalyzable. */
    SourceValue receiver(MethodNode method, MethodInsnNode call) {
        Frame<SourceValue>[] all = frames(method);
        if (all == null || call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        Frame<SourceValue> frame = all[method.instructions.indexOf(call)];
        int arguments = Type.getArgumentTypes(call.desc).length;
        if (frame == null || frame.getStackSize() <= arguments) return null;
        // The receiver sits just below the arguments.
        return frame.getStack(frame.getStackSize() - arguments - 1);
    }

    /** The values every store reaching {@code load} put in its local, or null when unknown. */
    SourceValue storedValue(MethodNode method, VarInsnNode load) {
        Frame<SourceValue>[] all = frames(method);
        // The analyzer gives a parameter no source, so a store merged with one would hide it.
        if (all == null || load.var < parameterSlots(method)) return null;
        Frame<SourceValue> frame = all[method.instructions.indexOf(load)];
        if (frame == null) return null;
        SourceValue local = frame.getLocal(load.var);
        if (local.insns.isEmpty()) return null;
        // Every source must be a store. What it stored is the top of the stack just before it.
        // Anything else could have set the local, so the value is unknown.
        Set<AbstractInsnNode> stored = new LinkedHashSet<>();
        for (AbstractInsnNode store : local.insns) {
            if (!(store instanceof VarInsnNode variable) || store.getOpcode() < Opcodes.ISTORE
                    || store.getOpcode() > Opcodes.ASTORE) {
                return null;
            }
            Frame<SourceValue> before = all[method.instructions.indexOf(variable)];
            if (before == null) return null;
            stored.addAll(before.getStack(before.getStackSize() - 1).insns);
        }
        return new SourceValue(1, stored);
    }

    /** The int a value always holds, or null when more than one instruction or a computation made it. */
    static Integer intConstant(SourceValue value) {
        // The compiler pushes small ints with iconst, bipush or sipush, and larger ones with ldc.
        AbstractInsnNode producer = single(value);
        if (producer instanceof LdcInsnNode ldc && ldc.cst instanceof Integer constant) return constant;
        if (producer instanceof IntInsnNode push
                && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH)) {
            return push.operand;
        }
        if (producer instanceof InsnNode insn && insn.getOpcode() >= Opcodes.ICONST_M1
                && insn.getOpcode() <= Opcodes.ICONST_5) {
            return insn.getOpcode() - Opcodes.ICONST_0;
        }
        // Only constants count. A value computed at run time is unknown, never guessed.
        return null;
    }

    /** The string a value always holds, or null. */
    static String stringConstant(SourceValue value) {
        return single(value) instanceof LdcInsnNode ldc && ldc.cst instanceof String constant
                ? constant : null;
    }

    /** The static field a value was read from, as {@code owner.name}, or null. */
    static String staticField(SourceValue value) {
        return single(value) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                ? field.owner + "." + field.name : null;
    }

    /** The declared parameter a value was loaded from, or -1. */
    static int parameter(MethodNode method, SourceValue value) {
        if (!(single(value) instanceof VarInsnNode load) || load.getOpcode() < Opcodes.ILOAD
                || load.getOpcode() > Opcodes.ALOAD) {
            return -1;
        }
        // Walk the parameter slots, which start after "this" in an instance method. A parameter
        // the method assigns to does not count, since the value could then come from anywhere.
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        Type[] parameters = Type.getArgumentTypes(method.desc);
        for (int index = 0; index < parameters.length; index++) {
            if (slot == load.var) return writesSlot(method, slot) ? -1 : index;
            slot += parameters[index].getSize();
        }
        return -1;
    }

    /** Every call in this class to {@code target}, as a method of this class. */
    List<Site> callsTo(MethodNode target) {
        List<Site> sites = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && call.name.equals(target.name) && call.desc.equals(target.desc)) {
                    sites.add(new Site(method, call, 0));
                }
            }
        }
        return sites;
    }

    /** Every {@code invokedynamic} in this class that runs {@code target}, such as a lambda body. */
    List<Site> lambdasOf(MethodNode target) {
        List<Site> sites = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode dynamic)) continue;
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle && handle.getOwner().equals(node.name)
                            && handle.getName().equals(target.name) && handle.getDesc().equals(target.desc)) {
                        // The offset counts the leading lambda parameters the call does not
                        // capture: an instance lambda receives "this" first.
                        int receiver = handle.getTag() == Opcodes.H_INVOKESTATIC ? 0 : 1;
                        sites.add(new Site(method, dynamic, receiver));
                    }
                }
            }
        }
        return sites;
    }

    /** The string constants and written field names in the statement that holds {@code insn}. */
    Set<String> statementNames(MethodNode method, AbstractInsnNode insn) {
        Set<String> names = new LinkedHashSet<>();
        Frame<SourceValue>[] all = frames(method);
        if (all == null) return names;
        // The statement is the instructions between the nearest points where the stack is empty.
        int index = method.instructions.indexOf(insn);
        int start = index;
        while (start > 0 && !emptyStack(all[start])) start--;
        int end = index + 1;
        while (end < all.length && !emptyStack(all[end])) end++;
        // ITEMS.register("ruby_spawn_egg", () -> new SpawnEggItem(...)) and
        // RUBY_SPAWN_EGG = new SpawnEggItem(...) are each one statement, so these name the item
        // the constructor in it builds.
        for (int i = start; i < end && i < method.instructions.size(); i++) {
            AbstractInsnNode current = method.instructions.get(i);
            if (current instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                names.add(text);
            } else if (current instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.PUTSTATIC
                    || field.getOpcode() == Opcodes.PUTFIELD)) {
                names.add(field.name.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return names;
    }

    /** Whether a method is a lambda body the compiler generated. */
    static boolean isLambda(MethodNode method) {
        return (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$");
    }

    /** A call, or an {@code invokedynamic} that captures arguments, inside {@code method}. */
    record Site(MethodNode method, AbstractInsnNode insn, int offset) {}

    private static boolean emptyStack(Frame<SourceValue> frame) {
        return frame == null || frame.getStackSize() == 0;
    }

    /** The one instruction that made a value, or null when several paths could have. */
    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }

    /** The local slots the receiver and the parameters take. */
    private static int parameterSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type parameter : Type.getArgumentTypes(method.desc)) slots += parameter.getSize();
        return slots;
    }

    /** Whether the method stores to or increments a local slot anywhere. */
    private static boolean writesSlot(MethodNode method, int slot) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode store && store.var == slot
                    && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE) {
                return true;
            }
            if (insn instanceof IincInsnNode increment && increment.var == slot) return true;
        }
        return false;
    }
}
