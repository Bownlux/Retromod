/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Stamps a registered static field's id around the statement that fills it. */
public final class StaticFieldIdStamper {

    private StaticFieldIdStamper() {}

    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    /** {@code static Object enter(Identifier id)}: sets the id and returns the one it replaced. */
    public static final String ENTER_DESC = "(L" + IDENTIFIER + ";)Ljava/lang/Object;";
    /** {@code static void leave(Object previous)}: restores the id {@code enter} replaced. */
    public static final String LEAVE_DESC = "(Ljava/lang/Object;)V";
    /** {@code static void isolate()}: sets the current id aside and clears it. */
    public static final String ISOLATE = "isolate";
    /** {@code static void restore()}: puts back the id the matching {@code isolate} set aside. */
    public static final String RESTORE = "restore";

    private static final String THREAD_LOCAL = "java/lang/ThreadLocal";
    private static final String L_THREAD_LOCAL = "L" + THREAD_LOCAL + ";";
    private static final String ARRAY_LIST = "java/util/ArrayList";
    /** The bridge field that keeps each thread's set-aside ids, a {@code ThreadLocal<ArrayList>}. */
    private static final String SAVED = "SAVED";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    /** Stamps every store in {@code m} that {@code idOf} knows an id for, and counts them. */
    public static int stamp(String owner, MethodNode m, Function<FieldInsnNode, String[]> idOf, String bridge) {
        if (m.instructions == null || m.instructions.size() == 0) return 0;
        // idOf gives {namespace, path} for a putstatic, or null to leave it alone. A method with
        // no such store is not analyzed at all.
        boolean anyStore = false;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && idOf.apply(put) != null) {
                anyStore = true;
                break;
            }
        }
        if (!anyStore) return 0;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (Throwable unanalyzable) {
            return 0;
        }
        // Find where the statement that fills each field starts.
        record Stamp(AbstractInsnNode store, AbstractInsnNode start, String[] id) {}
        List<Stamp> stamps = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (!(insn instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) continue;
            String[] id = idOf.apply(put);
            if (id == null) continue;
            AbstractInsnNode start = statementStart(m, frames, m.instructions.indexOf(put));
            if (start != null) stamps.add(new Stamp(put, start, id));
        }
        if (stamps.isEmpty()) return 0;

        // One spare local carries the replaced id from enter to leave. Statements never nest
        // within one method, so every stamp in it can share the slot.
        int previous = m.maxLocals;
        m.maxLocals = previous + 1;
        for (Stamp stamp : stamps) {
            // bridge.enter(id) goes before the statement's first instruction, because the value
            // may come from new, a factory or a builder chain, and each of them builds the
            // Properties whose factories read the id.
            InsnList enter = new InsnList();
            enter.add(new LdcInsnNode(stamp.id()[0]));
            enter.add(new LdcInsnNode(stamp.id()[1]));
            enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false));
            enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "enter", ENTER_DESC, false));
            enter.add(new VarInsnNode(Opcodes.ASTORE, previous));
            m.instructions.insertBefore(stamp.start(), enter);
            // bridge.leave(previous) puts back the id that was there instead of clearing it.
            // Filling a field often initializes another class mid-statement:
            // ModItems.FOO = new BlockItem(ModBlocks.FOO, ...) runs ModBlocks' initializer, which
            // stamps its own fields, and clearing there would leave the item with no id.
            InsnList leave = new InsnList();
            leave.add(new VarInsnNode(Opcodes.ALOAD, previous));
            leave.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "leave", LEAVE_DESC, false));
            m.instructions.insert(stamp.store(), leave);
        }
        return stamps.size();
    }

    /** Stamps the registered static fields one class fills, and starts its initializer with no id. */
    public static byte[] apply(byte[] classBytes, StaticFieldRegistryIds jarIds, String bridge) {
        if (classBytes == null) return classBytes;
        // Skip the parse when the class stores into no field the jar registers and has no static
        // initializer.
        StaticFieldRegistryIds jar = jarIds != null ? jarIds : StaticFieldRegistryIds.NONE;
        if (!jar.mayStoreInto(classBytes) && !mayHaveClassInitializer(classBytes)) return classBytes;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        // A store counts when the jar registers that field, whichever class owns it. The bridge
        // is the class whose Properties factories read the id on this loader.
        int stamped = 0;
        for (MethodNode m : cn.methods) {
            stamped += stamp(cn.name, m, put -> jar.fieldsOf(put.owner).get(put.name), bridge);
        }
        boolean isolated = isolateClassInitializer(cn, bridge);
        if (stamped == 0 && !isolated) return classBytes;
        return write(classBytes, cn, stamped > 0);
    }

    /** Writes back a class this pass changed, or returns {@code original} when writing fails. */
    public static byte[] write(byte[] original, ClassNode cn, boolean recomputeFrames) {
        // A stamp adds a local, so its frames are recomputed. An isolated initializer gains no
        // local and carries its own handler frame, so without a stamp every frame is kept exactly
        // as compiled.
        try {
            ClassWriter cw = recomputeFrames
                    ? new SafeClassWriter(new ClassReader(original), ClassWriter.COMPUTE_FRAMES)
                    : new ClassWriter(new ClassReader(original), 0);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable failed) {
            return original; // never ship a half-rewritten class
        }
    }

    private static final byte[] CLASS_INITIALIZER = utf8("<clinit>");

    /** Whether the class's constant pool names a static initializer, checked without parsing it. */
    public static boolean mayHaveClassInitializer(byte[] classBytes) {
        return classBytes != null && containsBytes(classBytes, CLASS_INITIALIZER);
    }

    /** Starts the static initializer of {@code cn} with no id, and restores the caller's id after it. */
    public static boolean isolateClassInitializer(ClassNode cn, String bridge) {
        // Mixin classes are skipped, because Mixin merges their initializers into Minecraft's. So
        // is the bridge, in any relocated copy, because its own initializer creates what isolate
        // uses. An initializer that already calls isolate is left as it is.
        MethodNode init = classInitializer(cn);
        if (init == null || !runsCode(init) || isMixin(cn) || cn.name.endsWith(bridge)
                || callsBridge(init, bridge, ISOLATE)) {
            return false;
        }
        // Another class often initializes inside a stamped statement: ModItems.FOO = new
        // BlockItem(ModBlocks.FOO, ...) runs ModBlocks' initializer with the item's id set. 26.x never
        // checks a block's id against its key, so an unproven block would take the item's name, drops
        // and model. So the initializer starts with no id, and only its own stamps set one.
        InsnList code = init.instructions;
        LabelNode start = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, ISOLATE, "()V", false));
        entry.add(start);
        code.insert(entry);
        // The caller's id comes back before every return.
        for (AbstractInsnNode insn : code.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                code.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, RESTORE, "()V", false));
            }
        }
        // An exception leaving the initializer restores the id too, so a mod that catches a
        // failed class init carries on with its own id. The handler's frame needs no locals, and
        // the writer leaves it out of a class too old to have frames.
        LabelNode handler = new LabelNode();
        code.add(handler);
        code.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[]{"java/lang/Throwable"}));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, RESTORE, "()V", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        init.tryCatchBlocks.add(new TryCatchBlockNode(start, handler, handler, null));
        init.maxStack = Math.max(init.maxStack, 1);
        return true;
    }

    private static MethodNode classInitializer(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("<clinit>".equals(m.name) && m.instructions != null && m.instructions.size() > 0) return m;
        }
        return null;
    }

    /** An initializer that only stores constants cannot build anything, so it is left alone. */
    private static boolean runsCode(MethodNode init) {
        for (AbstractInsnNode insn : init.instructions) {
            if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) return true;
        }
        return false;
    }

    private static boolean isMixin(ClassNode cn) {
        return hasMixinAnnotation(cn.invisibleAnnotations) || hasMixinAnnotation(cn.visibleAnnotations);
    }

    private static boolean hasMixinAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (MIXIN.equals(annotation.desc)) return true;
        }
        return false;
    }

    /** Whether {@code m} already calls {@code method} on the bridge, including a relocated copy. */
    private static boolean callsBridge(MethodNode m, String bridge, String method) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(method) && call.owner.endsWith(bridge)) {
                return true;
            }
        }
        return false;
    }

    /** Emits the bridge's {@code isolate()}, {@code restore()} and the per-thread id list they share. */
    public static void emitIsolation(ClassVisitor cw, String owner) {
        // The bridge keeps its id in its ThreadLocal CURRENT, and must fill SAVED from its own
        // static initializer with emitSavedInit. The list lives in the bridge rather than in a
        // local of the isolated initializer, so that initializer gains no local and keeps its
        // compiled frames.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, SAVED, L_THREAD_LOCAL,
                null, null).visitEnd();

        // static void isolate() { ArrayList saved = SAVED.get(); if (saved == null) { saved = new
        // ArrayList(); SAVED.set(saved); } saved.add(CURRENT.get()); CURRENT.remove(); }
        MethodVisitor isolate = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, ISOLATE, "()V", null, null);
        isolate.visitCode();
        Label have = new Label();
        isolate.visitFieldInsn(Opcodes.GETSTATIC, owner, SAVED, L_THREAD_LOCAL);
        isolate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "get", "()Ljava/lang/Object;", false);
        isolate.visitTypeInsn(Opcodes.CHECKCAST, ARRAY_LIST);
        isolate.visitVarInsn(Opcodes.ASTORE, 0);
        isolate.visitVarInsn(Opcodes.ALOAD, 0);
        isolate.visitJumpInsn(Opcodes.IFNONNULL, have);
        isolate.visitTypeInsn(Opcodes.NEW, ARRAY_LIST);
        isolate.visitInsn(Opcodes.DUP);
        isolate.visitMethodInsn(Opcodes.INVOKESPECIAL, ARRAY_LIST, "<init>", "()V", false);
        isolate.visitVarInsn(Opcodes.ASTORE, 0);
        isolate.visitFieldInsn(Opcodes.GETSTATIC, owner, SAVED, L_THREAD_LOCAL);
        isolate.visitVarInsn(Opcodes.ALOAD, 0);
        isolate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "set", "(Ljava/lang/Object;)V", false);
        isolate.visitLabel(have);
        isolate.visitVarInsn(Opcodes.ALOAD, 0);
        isolate.visitFieldInsn(Opcodes.GETSTATIC, owner, "CURRENT", L_THREAD_LOCAL);
        isolate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "get", "()Ljava/lang/Object;", false);
        isolate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "add", "(Ljava/lang/Object;)Z", false);
        isolate.visitInsn(Opcodes.POP);
        isolate.visitFieldInsn(Opcodes.GETSTATIC, owner, "CURRENT", L_THREAD_LOCAL);
        isolate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "remove", "()V", false);
        isolate.visitInsn(Opcodes.RETURN);
        isolate.visitMaxs(0, 0);
        isolate.visitEnd();

        // static void restore() { ArrayList saved = SAVED.get(); Object previous = saved == null
        // || saved.isEmpty() ? null : saved.remove(saved.size() - 1);
        // if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        MethodVisitor restore = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, RESTORE, "()V", null, null);
        restore.visitCode();
        Label clear = new Label();
        restore.visitFieldInsn(Opcodes.GETSTATIC, owner, SAVED, L_THREAD_LOCAL);
        restore.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "get", "()Ljava/lang/Object;", false);
        restore.visitTypeInsn(Opcodes.CHECKCAST, ARRAY_LIST);
        restore.visitVarInsn(Opcodes.ASTORE, 0);
        restore.visitVarInsn(Opcodes.ALOAD, 0);
        restore.visitJumpInsn(Opcodes.IFNULL, clear);
        restore.visitVarInsn(Opcodes.ALOAD, 0);
        restore.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "isEmpty", "()Z", false);
        restore.visitJumpInsn(Opcodes.IFNE, clear);
        restore.visitVarInsn(Opcodes.ALOAD, 0);
        restore.visitVarInsn(Opcodes.ALOAD, 0);
        restore.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "size", "()I", false);
        restore.visitInsn(Opcodes.ICONST_1);
        restore.visitInsn(Opcodes.ISUB);
        restore.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "remove", "(I)Ljava/lang/Object;", false);
        restore.visitVarInsn(Opcodes.ASTORE, 1);
        restore.visitVarInsn(Opcodes.ALOAD, 1);
        restore.visitJumpInsn(Opcodes.IFNULL, clear);
        restore.visitFieldInsn(Opcodes.GETSTATIC, owner, "CURRENT", L_THREAD_LOCAL);
        restore.visitVarInsn(Opcodes.ALOAD, 1);
        restore.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "set", "(Ljava/lang/Object;)V", false);
        restore.visitInsn(Opcodes.RETURN);
        restore.visitLabel(clear);
        restore.visitFieldInsn(Opcodes.GETSTATIC, owner, "CURRENT", L_THREAD_LOCAL);
        restore.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "remove", "()V", false);
        restore.visitInsn(Opcodes.RETURN);
        restore.visitMaxs(0, 0);
        restore.visitEnd();
    }

    /** {@code SAVED = new ThreadLocal();}, for the bridge's static initializer. */
    public static void emitSavedInit(MethodVisitor clinit, String owner) {
        clinit.visitTypeInsn(Opcodes.NEW, THREAD_LOCAL);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, THREAD_LOCAL, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, SAVED, L_THREAD_LOCAL);
    }


    /** The first instruction of the statement that ends at {@code end}, or null when unknown. */
    static AbstractInsnNode statementStart(MethodNode m, Frame<SourceValue>[] frames, int end) {
        // Walk back to the last real instruction that runs with an empty operand stack and that
        // no jump from earlier code lands after.
        for (int i = end - 1; i >= 0; i--) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn.getOpcode() < 0) continue;
            if (frames[i] == null) return null;
            if (frames[i].getStackSize() == 0 && !enteredFromBefore(m, i, end)) {
                // The arguments were evaluated before this point, Properties factory included,
                // so the statement starts where they did.
                int spilled = spilledArgumentsStart(m, i);
                if (spilled >= 0) {
                    i = spilled;
                    continue;
                }
                return insn;
            }
        }
        return null;
    }

    /** The index of the first spilled-argument store above a rebuilt {@code NEW}, or -1. */
    private static int spilledArgumentsStart(MethodNode m, int newIndex) {
        AbstractInsnNode at = m.instructions.get(newIndex);
        if (at.getOpcode() != Opcodes.NEW) return -1;
        // The transformer rebuilds a NEW this way when a class has a constructor redirect but this
        // overload matched none. The arguments, already on the stack, are stored in reverse to
        // RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT and up, then NEW and DUP follow.
        List<Integer> slots = new ArrayList<>();
        int first = -1;
        for (AbstractInsnNode before = previousReal(at); before instanceof VarInsnNode store
                && isStore(store.getOpcode()) && store.var >= RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT;
                before = previousReal(before)) {
            slots.add(store.var);
            first = m.instructions.indexOf(before);
        }
        if (slots.isEmpty()) return -1;
        // The arguments must then be loaded back in order. Only that exact shape counts, so a
        // mod's own earlier statement never joins this one.
        AbstractInsnNode next = nextReal(at);
        if (next != null && next.getOpcode() == Opcodes.DUP) next = nextReal(next);
        for (int slot : slots) {
            if (!(next instanceof VarInsnNode load) || !isLoad(load.getOpcode()) || load.var != slot) return -1;
            next = nextReal(next);
        }
        return first;
    }

    /** The instruction before {@code insn}, skipping labels, line numbers and frames. */
    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    /** The instruction after {@code insn}, skipping labels, line numbers and frames. */
    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static boolean isStore(int opcode) {
        return opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE;
    }

    private static boolean isLoad(int opcode) {
        return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD;
    }

    /** Whether a jump or switch before {@code start} lands between it and {@code end}. */
    private static boolean enteredFromBefore(MethodNode m, int start, int end) {
        // FIELD = enabled ? new Item(...) : null has an empty stack at the start of each branch,
        // and stamping one branch would enter on one path and leave on both.
        for (int i = 0; i < start; i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            List<LabelNode> targets = new ArrayList<>();
            if (insn instanceof JumpInsnNode jump) {
                targets.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
            for (LabelNode label : targets) {
                int target = m.instructions.indexOf(label);
                if (target > start && target <= end) return true;
            }
        }
        return false;
    }

    static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
