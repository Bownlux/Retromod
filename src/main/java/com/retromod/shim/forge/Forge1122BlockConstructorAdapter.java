/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds a 1.12.2 block or item on its modern parent when that parent's constructor changed.
 *
 * <p>1.13 replaced {@code Material} with {@code BlockBehaviour.Properties}, and most block bases
 * changed shape again later: {@code BlockBush()} became {@code BushBlock(Properties)},
 * {@code BlockBreakable(Material, boolean)} became {@code HalfTransparentBlock(Properties)}, a bed
 * now needs its {@code DyeColor}, and a pressure plate its {@code BlockSetType}. The mod's
 * {@code super(...)} then named a constructor the parent no longer has, and the block failed with
 * {@code NoSuchMethodError} when its registry built it.
 *
 * <p>The call is pointed at the parent's smallest constructor that can be filled in. Old arguments
 * that lead the new list unchanged are kept, as a stair keeps its model state; otherwise they are
 * dropped. Default block properties, a stone block set, oak wood, white dye, a flame particle, the
 * oak tree grower, and zeroes fill the rest. A parameter of any other type means the block is left
 * as it was. What the old arguments chose, such as the material's hardness or a plate's
 * sensitivity, is lost: the block constructs with defaults.
 */
public final class Forge1122BlockConstructorAdapter {

    private static final String BLOCKS = "net/minecraft/world/level/block/";
    private static final String PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";

    /** How to push a default for each parameter type the adapter can fill. */
    private static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final Map<String, Function<String, InsnList>> DEFAULTS = Map.of(
            "L" + ITEM_PROPERTIES + ";", desc -> list(new TypeInsnNode(Opcodes.NEW, ITEM_PROPERTIES),
                    new InsnNode(Opcodes.DUP),
                    new MethodInsnNode(Opcodes.INVOKESPECIAL, ITEM_PROPERTIES, "<init>", "()V", false)),
            PROPERTIES, desc -> list(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "of", "()" + PROPERTIES, false)),
            "Lnet/minecraft/world/level/block/state/properties/BlockSetType;", desc -> constant(desc, "STONE"),
            "Lnet/minecraft/world/level/block/state/properties/WoodType;", desc -> constant(desc, "OAK"),
            "Lnet/minecraft/world/item/DyeColor;", desc -> constant(desc, "WHITE"),
            "Lnet/minecraft/core/particles/SimpleParticleType;", desc -> list(new FieldInsnNode(Opcodes.GETSTATIC,
                    "net/minecraft/core/particles/ParticleTypes", "FLAME", desc)),
            "Lnet/minecraft/world/level/block/grower/TreeGrower;", desc -> constant(desc, "OAK"),
            "Lnet/minecraft/world/level/block/state/BlockState;", desc -> list(
                    new FieldInsnNode(Opcodes.GETSTATIC, BLOCKS + "Blocks", "STONE", "L" + BLOCKS + "Block;"),
                    new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCKS + "Block", "defaultBlockState",
                            "()Lnet/minecraft/world/level/block/state/BlockState;", false)));

    private Forge1122BlockConstructorAdapter() {}

    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, ClassResourceInspector::read);
    }

    /** @param host reads a host class, or returns null when it cannot */
    static byte[] apply(byte[] classBytes, Function<String, ClassNode> host) {
        if (classBytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (node.superName == null || !(node.superName.startsWith(BLOCKS)
                || node.superName.startsWith("net/minecraft/world/item/"))) {
            return classBytes;
        }
        ClassNode parent = host.apply(node.superName);
        if (parent == null) return classBytes;
        boolean changed = neutralizeRemovedMembers(node, host);
        for (MethodNode ctor : node.methods) {
            if (!ctor.name.equals("<init>") || constructsParent(ctor, node.superName)) continue;
            for (AbstractInsnNode insn : ctor.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals(node.superName) && call.name.equals("<init>")
                        && !declares(parent, call.desc)) {
                    changed |= retarget(ctor, call, parent);
                }
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** 1.12 Forge block setters that have no modern counterpart; mining level is a block tag now. */
    private static final java.util.Set<String> REMOVED_FORGE_METHODS = java.util.Set.of("setHarvestLevel");

    /**
     * A 1.12 block reads and writes members it inherited that are gone, such as
     * {@code translucent} ({@code field_149785_s}), which kept its SRG name because no modern
     * member matches it, or Forge's {@code setHarvestLevel}. Each failed with
     * {@code NoSuchFieldError} or {@code NoSuchMethodError} the first time the constructor ran.
     * A write is dropped, a read gives the type's default, and a call returns the block itself
     * when the old method returned the block, so chained setters keep working. A write to a field
     * the host now declares final, such as {@code friction}, is dropped as well, because only the
     * declaring class may write it. Only members neither the mod's classes nor the host declare
     * are touched.
     */
    private static boolean neutralizeRemovedMembers(ClassNode node, Function<String, ClassNode> host) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.GETFIELD
                        || field.getOpcode() == Opcodes.PUTFIELD) && field.owner.equals(node.name)) {
                    FieldStatus status = fieldStatus(node, field, host);
                    if (status == FieldStatus.PRESENT) continue;
                    if (field.getOpcode() == Opcodes.GETFIELD && status == FieldStatus.FINAL) continue;
                    InsnList replacement = new InsnList();
                    Type type = Type.getType(field.desc);
                    if (field.getOpcode() == Opcodes.PUTFIELD) {
                        replacement.add(new InsnNode(type.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                        replacement.add(new InsnNode(Opcodes.POP));
                    } else {
                        replacement.add(new InsnNode(Opcodes.POP));
                        replacement.add(zero(type));
                    }
                    method.instructions.insertBefore(field, replacement);
                    method.instructions.remove(field);
                    changed = true;
                } else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(node.name) && looksRemoved(call.name)
                        && !declaredInChain(node, call.name, call.desc, host)) {
                    InsnList replacement = new InsnList();
                    Type[] args = Type.getArgumentTypes(call.desc);
                    for (int i = args.length - 1; i >= 0; i--) {
                        replacement.add(new InsnNode(args[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                    }
                    Type returns = Type.getReturnType(call.desc);
                    if (returns.getSort() == Type.VOID) {
                        replacement.add(new InsnNode(Opcodes.POP));
                    } else if (returns.getSort() == Type.OBJECT && isAncestor(node, returns.getInternalName(), host)) {
                        // The setter returned the block for chaining; the receiver is that block.
                        replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, returns.getInternalName()));
                    } else {
                        replacement.add(new InsnNode(Opcodes.POP));
                        replacement.add(zero(returns));
                    }
                    method.instructions.insertBefore(call, replacement);
                    method.instructions.remove(call);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private enum FieldStatus { PRESENT, FINAL, MISSING }

    private static FieldStatus fieldStatus(ClassNode node, FieldInsnNode field, Function<String, ClassNode> host) {
        for (String current = node.name; current != null; ) {
            ClassNode cls = current.equals(node.name) ? node : host.apply(current);
            if (cls == null) return FieldStatus.PRESENT;
            for (var declared : cls.fields) {
                if (declared.name.equals(field.name) && declared.desc.equals(field.desc)) {
                    boolean inherited = cls != node && (declared.access & Opcodes.ACC_FINAL) != 0;
                    return inherited ? FieldStatus.FINAL : FieldStatus.PRESENT;
                }
            }
            current = cls.superName;
        }
        return looksRemoved(field.name) ? FieldStatus.MISSING : FieldStatus.PRESENT;
    }

    private static boolean looksRemoved(String name) {
        return name.matches("(field|func)_\\d+_[a-z]+") || REMOVED_FORGE_METHODS.contains(name);
    }

    private static boolean declaredInChain(ClassNode node, String name, String desc, Function<String, ClassNode> host) {
        for (String current = node.name; current != null; ) {
            ClassNode cls = current.equals(node.name) ? node : host.apply(current);
            if (cls == null) return true;
            if (cls.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) return true;
            current = cls.superName;
        }
        return false;
    }

    private static boolean isAncestor(ClassNode node, String type, Function<String, ClassNode> host) {
        for (String current = node.name; current != null; ) {
            if (current.equals(type)) return true;
            ClassNode cls = current.equals(node.name) ? node : host.apply(current);
            if (cls == null) return false;
            current = cls.superName;
        }
        return false;
    }

    private static AbstractInsnNode zero(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> new InsnNode(Opcodes.ICONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            default -> new InsnNode(Opcodes.ACONST_NULL);
        };
    }

    /** A {@code new Parent(...)} inside the constructor would be mistaken for the super call. */
    private static boolean constructsParent(MethodNode ctor, String parent) {
        return Arrays.stream(ctor.instructions.toArray())
                .anyMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(parent));
    }

    private static boolean declares(ClassNode parent, String desc) {
        return parent.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(desc)
                && (m.access & Opcodes.ACC_PRIVATE) == 0);
    }

    private static boolean retarget(MethodNode ctor, MethodInsnNode call, ClassNode parent) {
        if (resolveStateSupplier(ctor, call, parent)) return true;
        Type[] old = Type.getArgumentTypes(call.desc);
        MethodNode best = null;
        int bestKept = -1;
        for (MethodNode candidate : parent.methods) {
            if (!candidate.name.equals("<init>") || (candidate.access & Opcodes.ACC_PRIVATE) != 0) continue;
            Type[] args = Type.getArgumentTypes(candidate.desc);
            int kept = keptPrefix(old, args);
            boolean fillable = true;
            for (int i = kept; i < args.length; i++) fillable &= defaultable(args[i]);
            if (!fillable) continue;
            if (best == null || kept > bestKept
                    || (kept == bestKept && args.length < Type.getArgumentTypes(best.desc).length)) {
                best = candidate;
                bestKept = kept;
            }
        }
        if (best == null) return false;
        InsnList fix = new InsnList();
        for (int i = old.length - 1; i >= bestKept; i--) {
            fix.add(new InsnNode(old[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        Type[] args = Type.getArgumentTypes(best.desc);
        for (int i = bestKept; i < args.length; i++) fix.add(defaultFor(args[i]));
        ctor.instructions.insertBefore(call, fix);
        call.desc = best.desc;
        return true;
    }

    private static final Type SUPPLIER = Type.getObjectType("java/util/function/Supplier");
    /** Registered with the 1.12 lifecycle synthetics, which run this adapter. */
    static final String STATES = "com/retromod/shim/forge/embedded/LegacyBlockStates";
    private static final Type STATE = Type.getObjectType(BLOCKS + "state/BlockState");
    private static final Type PROPERTIES_TYPE = Type.getObjectType(BLOCKS + "state/BlockBehaviour$Properties");

    /**
     * Forge's {@code StairBlock(Supplier<BlockState>, Properties)}, which mods used to name their base
     * block lazily, against a parent that takes the {@code BlockState} itself. The supplier is read
     * where the call is made, and the mod's own properties are kept.
     */
    private static boolean resolveStateSupplier(MethodNode ctor, MethodInsnNode call, ClassNode parent) {
        Type[] old = Type.getArgumentTypes(call.desc);
        String wanted = Type.getMethodDescriptor(Type.VOID_TYPE, STATE, PROPERTIES_TYPE);
        if (old.length != 2 || !old[0].equals(SUPPLIER) || !old[1].equals(PROPERTIES_TYPE) || !declares(parent, wanted)) {
            return false;
        }
        InsnList fix = new InsnList();
        fix.add(new InsnNode(Opcodes.SWAP));
        fix.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, STATES, "resolve",
                "(Ljava/util/function/Supplier;)Ljava/lang/Object;", false));
        fix.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, STATE.getInternalName()));
        fix.add(new InsnNode(Opcodes.SWAP));
        ctor.instructions.insertBefore(call, fix);
        call.desc = wanted;
        return true;
    }

    /** How many old arguments the candidate takes first, unchanged; all of them or none. */
    private static int keptPrefix(Type[] old, Type[] args) {
        if (old.length == 0 || old.length > args.length) return 0;
        for (int i = 0; i < old.length; i++) {
            if (!old[i].equals(args[i])) return 0;
        }
        return old.length;
    }

    private static boolean defaultable(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE -> true;
            case Type.OBJECT -> DEFAULTS.containsKey(type.getDescriptor());
            default -> false;
        };
    }

    private static InsnList defaultFor(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.INT -> list(new InsnNode(Opcodes.ICONST_0));
            case Type.FLOAT -> list(new InsnNode(Opcodes.FCONST_0));
            case Type.LONG -> list(new InsnNode(Opcodes.LCONST_0));
            case Type.DOUBLE -> list(new InsnNode(Opcodes.DCONST_0));
            default -> DEFAULTS.get(type.getDescriptor()).apply(type.getDescriptor());
        };
    }

    private static InsnList constant(String desc, String name) {
        return list(new FieldInsnNode(Opcodes.GETSTATIC, Type.getType(desc).getInternalName(), name, desc));
    }

    private static InsnList list(AbstractInsnNode... insns) {
        InsnList list = new InsnList();
        List<AbstractInsnNode> all = new ArrayList<>(List.of(insns));
        all.forEach(list::add);
        return list;
    }
}
