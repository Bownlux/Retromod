/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Repairs a mixin into {@code SynchedEntityData} written for Minecraft 1.20.4 or older (#308).
 *
 * <p>1.20.5 rebuilt the class: the {@code ReadWriteLock lock} field is gone, and {@code itemsById}
 * changed from a fastutil {@code Int2ObjectMap} to a {@code DataItem[]} array indexed by the same
 * id. A mixin that shadows those fields fails to apply ({@code @Shadow field lock was not located}),
 * and Fossils and Archeology uses one to mark entity data dirty. The repair is exact:
 *
 * <ul>
 *   <li>{@code this.lock.readLock().lock()} and the matching {@code unlock()}, read or write, are
 *       dropped: the array is written only on the server thread, which is why Mojang removed the
 *       lock.</li>
 *   <li>The {@code itemsById} shadow is retyped to the array. {@code get(id)} becomes an array
 *       load and {@code values().iterator()} iterates the array.</li>
 * </ul>
 *
 * <p>Any other use of either field leaves the class untouched, so the mixin still fails visibly.
 */
public final class LegacySynchedEntityDataMixinRepair {

    private static final String TARGET = "net/minecraft/network/syncher/SynchedEntityData";
    private static final String ITEM_ARRAY = "[L" + TARGET + "$DataItem;";
    private static final String INT_MAP = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";
    private static final String LOCK = "Ljava/util/concurrent/locks/ReadWriteLock;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private LegacySynchedEntityDataMixinRepair() {}

    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, LegacySynchedEntityDataMixinRepair::hostRemovedLock);
    }

    static byte[] apply(byte[] classBytes, BooleanSupplier hostRemovedLock) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!constants.contains(TARGET) || !constants.contains(LOCK) && !constants.contains(INT_MAP)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (!targetsSynchedEntityData(node) || !hostRemovedLock.getAsBoolean()) return classBytes;
        if (!rewrite(node)) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewrite(ClassNode node) {
        FieldNode lock = shadow(node, "lock", LOCK);
        FieldNode items = shadow(node, "itemsById", INT_MAP);
        if (lock == null && items == null) return false;
        for (MethodNode method : node.methods) {
            if (!canRewrite(node, method)) return false;
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode field) || !field.owner.equals(node.name)) continue;
                if (field.name.equals("lock") && field.desc.equals(LOCK)) {
                    // this.lock.xLock().lock() leaves nothing on the stack; drop all three and pop this.
                    AbstractInsnNode which = field.getNext();
                    AbstractInsnNode action = which.getNext();
                    method.instructions.remove(which);
                    method.instructions.remove(action);
                    method.instructions.set(field, new InsnNode(Opcodes.POP));
                } else if (field.name.equals("itemsById") && field.desc.equals(INT_MAP)) {
                    field.desc = ITEM_ARRAY;
                    MethodInsnNode get = indexedGet(field);
                    if (get != null) {
                        method.instructions.set(get, new InsnNode(Opcodes.AALOAD));
                    } else {
                        AbstractInsnNode values = field.getNext();
                        AbstractInsnNode iterator = values.getNext();
                        method.instructions.set(values, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false));
                        method.instructions.set(iterator, new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                                "java/util/List", "iterator", "()Ljava/util/Iterator;", true));
                    }
                }
            }
        }
        if (lock != null) node.fields.remove(lock);
        if (items != null) items.desc = ITEM_ARRAY;
        return true;
    }

    /** Every use of the two fields must be one of the shapes the repair understands. */
    private static boolean canRewrite(ClassNode node, MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof FieldInsnNode field) || !field.owner.equals(node.name)) continue;
            if (field.name.equals("lock") && field.desc.equals(LOCK)) {
                AbstractInsnNode which = field.getNext();
                AbstractInsnNode action = which == null ? null : which.getNext();
                if (field.getOpcode() != Opcodes.GETFIELD
                        || !(isCall(which, "readLock") || isCall(which, "writeLock"))
                        || !(isCall(action, "lock") || isCall(action, "unlock"))) {
                    return false;
                }
            } else if (field.name.equals("itemsById") && field.desc.equals(INT_MAP)) {
                AbstractInsnNode next = field.getNext();
                boolean iterates = isCall(next, "values") && isCall(next.getNext(), "iterator");
                if (field.getOpcode() != Opcodes.GETFIELD || !iterates && indexedGet(field) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The {@code get(int)} in {@code itemsById.get(accessor.getId())}. Only an int-producing load
     * or call may sit between the field read and the lookup, so the array load sees the same
     * operands.
     */
    private static MethodInsnNode indexedGet(FieldInsnNode field) {
        int steps = 0;
        for (AbstractInsnNode insn = field.getNext(); insn != null && steps < 6; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) continue;
            steps++;
            if (insn instanceof MethodInsnNode call && call.owner.equals(INT_MAP.substring(1, INT_MAP.length() - 1))) {
                return call.name.equals("get") && call.desc.equals("(I)Ljava/lang/Object;") ? call : null;
            }
            if (insn instanceof FieldInsnNode || insn.getOpcode() >= Opcodes.IFEQ && insn.getOpcode() <= Opcodes.RETURN) {
                return null;
            }
        }
        return null;
    }

    private static boolean isCall(AbstractInsnNode insn, String name) {
        return insn instanceof MethodInsnNode call && call.name.equals(name);
    }

    private static FieldNode shadow(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) return field;
        }
        return null;
    }

    private static boolean targetsSynchedEntityData(ClassNode node) {
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(
                node.invisibleAnnotations, node.visibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals(MIXIN) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (String.valueOf(annotation.values.get(i + 1)).contains(TARGET)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Decided from the host version rather than by loading the class: transforms run on worker
     * threads whose context loader cannot see Minecraft, and loading SynchedEntityData early would
     * apply its mixins before the mod being repaired is in place.
     */
    private static boolean hostRemovedLock() {
        return com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
    }
}
