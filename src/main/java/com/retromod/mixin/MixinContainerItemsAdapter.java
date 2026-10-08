/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a 1.21.x Mixin that shadows {@code ItemContainerContents.items} working on 26.1.
 *
 * <p>1.21.x stored the container as a {@code NonNullList<ItemStack>}. 26.1 stores it as a
 * {@code List<Optional<ItemStackTemplate>>} under the same name, so the old {@code @Shadow} no
 * longer resolves and the whole Mixin fails to apply. The shadow is retyped to the host field and
 * every read is adapted where it is used: {@code size()} and {@code isEmpty()} read the new list
 * directly, {@code get(int)} turns the slot's template into a fresh stack, and any other use gets
 * a complete {@code NonNullList} copy. The old field was immutable in practice, so a copy keeps
 * the old meaning. A class that writes the field is left unchanged, because writes cannot be
 * translated without changing what the container holds.
 *
 * <p>This only acts when the indexed host jar proves the new field type, so a Mixin built for the
 * host passes through unchanged.
 */
final class MixinContainerItemsAdapter {

    static final String CONTENTS = "net/minecraft/world/item/component/ItemContainerContents";
    private static final String FIELD = "items";
    private static final String NON_NULL_LIST = "net/minecraft/core/NonNullList";
    private static final String LEGACY_DESC = "L" + NON_NULL_LIST + ";";
    private static final String HOST_DESC = "Ljava/util/List;";
    private static final String HOST_SIGNATURE =
            "Ljava/util/List<Ljava/util/Optional<Lnet/minecraft/world/item/ItemStackTemplate;>;>;";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String TEMPLATE = "net/minecraft/world/item/ItemStackTemplate";
    private static final String LIST = "java/util/List";
    private static final String OPTIONAL = "java/util/Optional";

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE_DESC = "Lorg/spongepowered/asm/mixin/Unique;";

    static final String SLOT_STACK = "retromod$slotStack";
    static final String SLOT_STACK_DESC = "(Ljava/lang/Object;)L" + ITEM_STACK + ";";
    static final String LEGACY_ITEMS = "retromod$legacyItems";
    static final String LEGACY_ITEMS_DESC = "(" + HOST_DESC + ")" + LEGACY_DESC;

    private MixinContainerItemsAdapter() {}

    /** Whether this class needs the adapter: a container Mixin with the legacy shadow. */
    static boolean handles(ClassNode node) {
        return targetsContents(node) && legacyShadow(node) != null;
    }

    /**
     * Retypes the shadow and adapts every read. Returns false and leaves the class untouched when
     * the host does not prove the new field, or when the class writes the field.
     */
    static boolean apply(ClassNode node, FuzzyMethodResolver host) {
        if (host == null || !host.isIndexed()
                || !host.hasField(CONTENTS, FIELD, HOST_DESC) || !handles(node)) {
            return false;
        }
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || writesField(node)) return false;

        // Analyze every reading method before changing any, so a failure leaves no partial edit.
        List<MethodNode> readers = new ArrayList<>();
        List<Frame<SourceValue>[]> readerFrames = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!readsField(node, method)) continue;
            try {
                readerFrames.add(new Analyzer<>(new SourceInterpreter()).analyze(node.name, method));
            } catch (AnalyzerException e) {
                return false;
            }
            readers.add(method);
        }
        boolean needsCopy = false;
        boolean needsSlot = false;
        for (int i = 0; i < readers.size(); i++) {
            int[] uses = adaptReads(node, readers.get(i), readerFrames.get(i));
            needsCopy |= uses[0] > 0;
            needsSlot |= uses[1] > 0 || uses[0] > 0;
        }

        FieldNode shadow = legacyShadow(node);
        shadow.desc = HOST_DESC;
        shadow.signature = HOST_SIGNATURE;
        if (needsSlot) node.methods.add(slotStackMethod());
        if (needsCopy) node.methods.add(legacyItemsMethod(node.name));
        return true;
    }

    /**
     * Rewrites each read in one method. Returns {copies, slot reads} so the caller adds only the
     * helpers the class uses.
     */
    private static int[] adaptReads(ClassNode node, MethodNode method, Frame<SourceValue>[] frames) {
        AbstractInsnNode[] insns = method.instructions.toArray();
        int copies = 0;
        int slots = 0;
        for (AbstractInsnNode insn : insns) {
            if (!isFieldRead(node, insn)) continue;
            FieldInsnNode read = (FieldInsnNode) insn;
            read.desc = HOST_DESC;
            MethodInsnNode consumer = soleListConsumer(method, insns, frames, read);
            if (consumer != null && isSizeQuery(consumer)) {
                retargetToList(consumer);
            } else if (consumer != null && isIndexedGet(consumer)) {
                retargetToList(consumer);
                method.instructions.insert(consumer, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        node.name, SLOT_STACK, SLOT_STACK_DESC, false));
                slots++;
            } else {
                method.instructions.insert(read, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        node.name, LEGACY_ITEMS, LEGACY_ITEMS_DESC, false));
                copies++;
            }
        }
        method.maxStack = Math.max(method.maxStack, 2);
        return new int[] {copies, slots};
    }

    /**
     * The one instruction that uses the read value as its receiver, when that instruction is a
     * {@code NonNullList} call. A value that also flows anywhere else returns null.
     */
    private static MethodInsnNode soleListConsumer(MethodNode method, AbstractInsnNode[] insns,
            Frame<SourceValue>[] frames, FieldInsnNode read) {
        MethodInsnNode found = null;
        for (int i = 0; i < insns.length; i++) {
            Frame<SourceValue> frame = frames[i];
            if (frame == null) continue;
            for (int slot = 0; slot < frame.getStackSize(); slot++) {
                if (!frame.getStack(slot).insns.contains(read)) continue;
                // A copy would reach a second consumer the analysis below cannot follow.
                if (isStackCopy(insns[i])) return null;
                // A stack map frame declared here still says NonNullList, which a retargeted read
                // would contradict. The full copy keeps every declared type true.
                if (insns[i] instanceof FrameNode) return null;
                Frame<SourceValue> next = i + 1 < frames.length ? frames[i + 1] : null;
                // A value the instruction leaves in place is only passing through it.
                if (next != null && next.getStackSize() > slot
                        && next.getStack(slot).insns.contains(read)) {
                    continue;
                }
                if (!(insns[i] instanceof MethodInsnNode call)
                        || !NON_NULL_LIST.equals(call.owner)
                        || receiverSlot(frame, call) != slot
                        || (found != null && found != call)) {
                    return null;
                }
                found = call;
            }
            for (int local = 0; local < frame.getLocals(); local++) {
                if (frame.getLocal(local).insns.contains(read)) return null;
            }
        }
        return found;
    }

    private static boolean isStackCopy(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP;
    }

    private static int receiverSlot(Frame<SourceValue> frame, MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) return -1;
        return frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
    }

    private static boolean isSizeQuery(MethodInsnNode call) {
        return ("size".equals(call.name) && "()I".equals(call.desc))
                || ("isEmpty".equals(call.name) && "()Z".equals(call.desc));
    }

    private static boolean isIndexedGet(MethodInsnNode call) {
        return "get".equals(call.name) && "(I)Ljava/lang/Object;".equals(call.desc);
    }

    private static void retargetToList(MethodInsnNode call) {
        call.setOpcode(Opcodes.INVOKEINTERFACE);
        call.owner = LIST;
        call.itf = true;
    }

    /** {@code Optional<ItemStackTemplate>} slot to a new stack, or the empty stack. */
    private static MethodNode slotStackMethod() {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                SLOT_STACK, SLOT_STACK_DESC, null, null);
        m.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE_DESC)));
        Label empty = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitTypeInsn(Opcodes.CHECKCAST, OPTIONAL);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "isPresent", "()Z", false);
        m.visitJumpInsn(Opcodes.IFEQ, empty);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitTypeInsn(Opcodes.CHECKCAST, OPTIONAL);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "get", "()Ljava/lang/Object;", false);
        m.visitTypeInsn(Opcodes.CHECKCAST, TEMPLATE);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEMPLATE, "create", "()L" + ITEM_STACK + ";",
                false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitLabel(empty);
        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        m.visitFieldInsn(Opcodes.GETSTATIC, ITEM_STACK, "EMPTY", "L" + ITEM_STACK + ";");
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(2, 1);
        return m;
    }

    /** A full {@code NonNullList<ItemStack>} copy of the host list, for any other use. */
    private static MethodNode legacyItemsMethod(String owner) {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                LEGACY_ITEMS, LEGACY_ITEMS_DESC, null, null);
        m.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE_DESC)));
        Label loop = new Label();
        Label done = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "size", "()I", true);
        m.visitFieldInsn(Opcodes.GETSTATIC, ITEM_STACK, "EMPTY", "L" + ITEM_STACK + ";");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, NON_NULL_LIST, "withSize",
                "(ILjava/lang/Object;)" + LEGACY_DESC, false);
        m.visitVarInsn(Opcodes.ASTORE, 1);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitVarInsn(Opcodes.ISTORE, 2);
        m.visitLabel(loop);
        m.visitFrame(Opcodes.F_APPEND, 2, new Object[] {NON_NULL_LIST, Opcodes.INTEGER}, 0, null);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "size", "()I", true);
        m.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "get", "(I)Ljava/lang/Object;", true);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, SLOT_STACK, SLOT_STACK_DESC, false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NON_NULL_LIST, "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;", false);
        m.visitInsn(Opcodes.POP);
        m.visitIincInsn(2, 1);
        m.visitJumpInsn(Opcodes.GOTO, loop);
        m.visitLabel(done);
        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(4, 3);
        return m;
    }

    private static boolean targetsContents(ClassNode node) {
        for (List<AnnotationNode> annotations : annotationLists(node)) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
                List<String> targets = new ArrayList<>();
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    Object key = annotation.values.get(i);
                    Object value = annotation.values.get(i + 1);
                    if (!(value instanceof List<?> list)) continue;
                    for (Object entry : list) {
                        if ("value".equals(key) && entry instanceof Type type) {
                            targets.add(type.getInternalName());
                        } else if ("targets".equals(key) && entry instanceof String name) {
                            targets.add(name.replace('.', '/'));
                        }
                    }
                }
                return targets.size() == 1 && CONTENTS.equals(targets.get(0));
            }
        }
        return false;
    }

    private static FieldNode legacyShadow(ClassNode node) {
        for (FieldNode field : node.fields) {
            if (!FIELD.equals(field.name) || !LEGACY_DESC.equals(field.desc)) continue;
            if (hasAnnotation(field.visibleAnnotations, SHADOW_DESC)
                    || hasAnnotation(field.invisibleAnnotations, SHADOW_DESC)) {
                return field;
            }
        }
        return null;
    }

    private static boolean writesField(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.PUTFIELD && insn instanceof FieldInsnNode field
                        && node.name.equals(field.owner) && FIELD.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean readsField(ClassNode node, MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (isFieldRead(node, insn)) return true;
        }
        return false;
    }

    private static boolean isFieldRead(ClassNode node, AbstractInsnNode insn) {
        return insn.getOpcode() == Opcodes.GETFIELD && insn instanceof FieldInsnNode field
                && node.name.equals(field.owner) && FIELD.equals(field.name)
                && LEGACY_DESC.equals(field.desc);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) return true;
        }
        return false;
    }

    private static List<List<AnnotationNode>> annotationLists(ClassNode node) {
        List<List<AnnotationNode>> lists = new ArrayList<>(2);
        if (node.visibleAnnotations != null) lists.add(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) lists.add(node.invisibleAnnotations);
        return lists;
    }
}
