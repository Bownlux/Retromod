/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives an object a mod's own deferred registry builds from a supplier the id it is registered under, while the
 * supplier runs. Moonlight's registry queue reads its key from a final field, calls the supplier, and registers the
 * result under that key in the same method.
 */
public final class SupplierRegistrationIdStamper {

    private static final String SUPPLIER = "java/util/function/Supplier";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String WRITABLE = "net/minecraft/core/WritableRegistry";
    private static final String WRITABLE_REGISTER = "(L" + RESOURCE_KEY + ";Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)";
    private static final byte[] WRITABLE_BYTES = WRITABLE.getBytes(StandardCharsets.ISO_8859_1);

    /** The supplier call to wrap and the final field of this class that holds its registry key. */
    private record Window(MethodInsnNode get, FieldInsnNode key) {}

    private SupplierRegistrationIdStamper() {}

    public static byte[] apply(byte[] classBytes, String bridge) {
        // Only a class that registers into a writable registry itself can hold such a queue.
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, WRITABLE_BYTES)) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("net/minecraft/") || com.retromod.core.SyntheticEmbedder.isRetromodClass(node.name)
                || StaticFieldIdStamper.isMixin(node)) {
            return classBytes;
        }
        int stamped = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || method.instructions == null) continue;
            for (Window window : windows(node, method)) {
                emit(method, window, bridge);
                stamped++;
            }
        }
        return stamped == 0 ? classBytes : StaticFieldIdStamper.write(classBytes, node, true);
    }

    /** Each registration whose key is a final field of this object and whose value one supplier call in the method built. */
    static List<Window> windows(ClassNode owner, MethodNode method) {
        List<Window> windows = new ArrayList<>();
        List<MethodInsnNode> registrations = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && isWritableRegister(call)) registrations.add(call);
        }
        if (registrations.isEmpty()) return windows;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
        } catch (Throwable unanalyzable) {
            return windows;
        }
        for (MethodInsnNode call : registrations) {
            Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
            if (frame == null) continue;
            int top = frame.getStackSize();
            FieldInsnNode key = ownFinalField(owner, method, frames, frame.getStack(top - 3));
            MethodInsnNode get = supplierCall(owner, method, frames, frame.getStack(top - 2));
            if (key == null || get == null || !key.desc.equals("L" + RESOURCE_KEY + ";")) continue;
            // The supplier runs before the registration, once, in straight code.
            if (method.instructions.indexOf(get) > method.instructions.indexOf(call)) continue;
            if (windows.stream().anyMatch(window -> window.get() == get)) continue;
            windows.add(new Window(get, key));
        }
        return windows;
    }

    /**
     * The registry's own register, or the writable registration bridge that stands in for it from 26.1, for every
     * registry conversion, with the registry as its first argument; either way the key and the value sit in the
     * same stack slots.
     */
    private static boolean isWritableRegister(MethodInsnNode call) {
        if (call.owner.equals(WRITABLE) && call.name.equals("register")) return call.desc.startsWith(WRITABLE_REGISTER);
        return call.owner.endsWith(LegacyRegistryMapCodecBridge.WRITABLE_REGISTER) && call.name.equals("registerWritable")
                && call.desc.equals(LegacyRegistryMapCodecBridge.WRITABLE_HELPER_DESC);
    }

    /** The {@code this.field} read a value comes from, when the field is a final instance field of this class. */
    private static FieldInsnNode ownFinalField(ClassNode owner, MethodNode method, Frame<SourceValue>[] frames, SourceValue value) {
        AbstractInsnNode source = single(value);
        if (!(source instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETFIELD || !read.owner.equals(owner.name)) return null;
        FieldNode field = owner.fields.stream().filter(f -> f.name.equals(read.name) && f.desc.equals(read.desc)).findFirst().orElse(null);
        if (field == null || (field.access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) != Opcodes.ACC_FINAL) return null;
        return readsThis(method, frames, read) ? read : null;
    }

    /** The one {@code Supplier.get()} a value comes from, directly, through a cast, or through one store to a field of this object. */
    private static MethodInsnNode supplierCall(ClassNode owner, MethodNode method, Frame<SourceValue>[] frames, SourceValue value) {
        AbstractInsnNode source = single(value);
        while (source != null && source.getOpcode() == Opcodes.CHECKCAST) {
            source = single(frames[method.instructions.indexOf(source)].getStack(frames[method.instructions.indexOf(source)].getStackSize() - 1));
        }
        if (source instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD && read.owner.equals(owner.name)) {
            FieldInsnNode store = onlyStore(method, read);
            if (store == null) return null;
            Frame<SourceValue> atStore = frames[method.instructions.indexOf(store)];
            source = atStore == null ? null : single(atStore.getStack(atStore.getStackSize() - 1));
        }
        return source instanceof MethodInsnNode call && call.owner.equals(SUPPLIER) && call.name.equals("get")
                && call.desc.equals("()Ljava/lang/Object;") ? call : null;
    }

    /**
     * The store a field read sees: the nearest one before it in straight code. Null when a jump, a jump target or a
     * handler lies between them, since another path could have stored something else.
     */
    private static FieldInsnNode onlyStore(MethodNode method, FieldInsnNode read) {
        java.util.Set<LabelNode> targets = new java.util.HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) targets.add(block.handler);
        for (AbstractInsnNode insn = read.getPrevious(); insn != null; insn = insn.getPrevious()) {
            if (insn instanceof JumpInsnNode || (insn instanceof LabelNode label && targets.contains(label))) return null;
            if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(read.owner)
                    && put.name.equals(read.name)) {
                return put;
            }
        }
        return null;
    }

    /** Whether a field read's receiver is {@code this}. */
    private static boolean readsThis(MethodNode method, Frame<SourceValue>[] frames, FieldInsnNode read) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(read)];
        AbstractInsnNode receiver = frame == null ? null : single(frame.getStack(frame.getStackSize() - 1));
        return receiver instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0;
    }

    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }

    /** Sets the key's id around the supplier call and puts the previous one back after it, also when it throws. */
    private static void emit(MethodNode method, Window window, String bridge) {
        int previous = method.maxLocals++;
        InsnList enter = new InsnList();
        enter.add(new VarInsnNode(Opcodes.ALOAD, 0));
        enter.add(new FieldInsnNode(Opcodes.GETFIELD, window.key().owner, window.key().name, window.key().desc));
        enter.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RESOURCE_KEY, "identifier", "()L" + IDENTIFIER + ";", false));
        enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "enter", StaticFieldIdStamper.ENTER_DESC, false));
        enter.add(new VarInsnNode(Opcodes.ASTORE, previous));
        LabelNode start = new LabelNode();
        enter.add(start);
        method.instructions.insertBefore(window.get(), enter);

        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode after = new LabelNode();
        InsnList leave = new InsnList();
        leave.add(end);
        leave.add(new VarInsnNode(Opcodes.ALOAD, previous));
        leave.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "leave", StaticFieldIdStamper.LEAVE_DESC, false));
        leave.add(new JumpInsnNode(Opcodes.GOTO, after));
        leave.add(handler);
        leave.add(new VarInsnNode(Opcodes.ALOAD, previous));
        leave.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "leave", StaticFieldIdStamper.LEAVE_DESC, false));
        leave.add(new InsnNode(Opcodes.ATHROW));
        leave.add(after);
        method.instructions.insert(window.get(), leave);
        method.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, null));
    }
}
