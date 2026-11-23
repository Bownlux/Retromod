/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
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

/**
 * Keeps a block's old overrides running and its old super calls linking where the host changed a block hook's
 * parameters: {@code neighborChanged} (1.21.2), {@code onRemove}, {@code getCloneItemStack} and {@code fallOn} (1.21.5).
 */
public final class LegacyBlockOverridesAdapter {

    private static final String BEHAVIOUR = "net/minecraft/world/level/block/state/BlockBehaviour";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String LEVEL_TYPE = "net/minecraft/world/level/Level";
    private static final String LEVEL = "L" + LEVEL_TYPE + ";";
    private static final String SERVER_LEVEL_TYPE = "net/minecraft/server/level/ServerLevel";
    private static final String SERVER_LEVEL = "L" + SERVER_LEVEL_TYPE + ";";
    private static final String POS_TYPE = "net/minecraft/core/BlockPos";
    private static final String POS = "L" + POS_TYPE + ";";
    private static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    private static final String ORIENTATION_TYPE = "net/minecraft/world/level/redstone/Orientation";
    private static final String DIRECTION_TYPE = "net/minecraft/core/Direction";
    private static final String READER = "Lnet/minecraft/world/level/LevelReader;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";

    // One flag per change, set by the shims only where the host made it.
    public static final String NEIGHBOR_CHANGED = "legacyBlockOverrides.neighborChanged";
    public static final String ON_REMOVE = "legacyBlockOverrides.onRemove";
    public static final String CLONE_STACK = "legacyBlockOverrides.getCloneItemStack";
    public static final String FALL_ON = "legacyBlockOverrides.fallOn";
    public static final String ENTITY_INSIDE = "legacyBlockOverrides.entityInside";
    private static final String APPLIER = "net/minecraft/world/entity/InsideBlockEffectApplier";
    // Holds the host's applier while an old entityInside override runs, so its super call can pass it on.
    static final String INSIDE_HELPER = "com/retromod/generated/LegacyInsideApplier";
    static final String OLD_INSIDE = "(" + STATE + LEVEL + POS + ENTITY + ")V";
    static final String NEW_INSIDE = "(" + STATE + LEVEL + POS + ENTITY + "L" + APPLIER + ";Z)V";

    static final String OLD_NEIGHBOR = "(" + STATE + LEVEL + POS + BLOCK + POS + "Z)V";
    static final String NEW_NEIGHBOR = "(" + STATE + LEVEL + POS + BLOCK + "L" + ORIENTATION_TYPE + ";Z)V";
    static final String OLD_REMOVE = "(" + STATE + LEVEL + POS + STATE + "Z)V";
    static final String NEW_REMOVE = "(" + STATE + SERVER_LEVEL + POS + "Z)V";
    static final String OLD_CLONE = "(" + READER + POS + STATE + ")" + STACK;
    static final String NEW_CLONE = "(" + READER + POS + STATE + "Z)" + STACK;
    // 1.20.1 asked a block getter, which the level reader the host passes still is.
    static final String OLD_CLONE_1201 = "(Lnet/minecraft/world/level/BlockGetter;" + POS + STATE + ")" + STACK;
    static final String OLD_FALL = "(" + LEVEL + STATE + POS + ENTITY + "F)V";
    static final String NEW_FALL = "(" + LEVEL + STATE + POS + ENTITY + "D)V";

    private LegacyBlockOverridesAdapter() {}

    /** Turns on each rule whose change the host shows; offline, the target version decides. */
    public static void register(RetromodTransformer t) {
        ClassNode behaviour = ClassResourceInspector.read(BEHAVIOUR);
        enable(t, behaviour, NEIGHBOR_CHANGED, "neighborChanged", NEW_NEIGHBOR, OLD_NEIGHBOR, "1.21.2");
        enable(t, behaviour, ON_REMOVE, "affectNeighborsAfterRemoval", NEW_REMOVE, null, "1.21.5");
        enable(t, behaviour, CLONE_STACK, "getCloneItemStack", NEW_CLONE, OLD_CLONE, "1.21.5");
        if (enable(t, behaviour, ENTITY_INSIDE, "entityInside", NEW_INSIDE, OLD_INSIDE, "1.21.5")
                && !t.getSyntheticClasses().containsKey(INSIDE_HELPER)) {
            t.registerSyntheticClass(INSIDE_HELPER, generateInsideHelper());
        }
        ClassNode block = ClassResourceInspector.read("net/minecraft/world/level/block/Block");
        enable(t, block, FALL_ON, "fallOn", NEW_FALL, OLD_FALL, "1.21.5");
    }

    private static boolean enable(RetromodTransformer t, ClassNode host, String flag, String name, String newDesc,
            String oldDesc, String since) {
        boolean changed = host == null
                ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0
                : declares(host, name, newDesc) && (oldDesc == null || !declares(host, name, oldDesc));
        if (changed) t.enablePostProcess(flag);
        return changed;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    /** Repairs one class under the enabled rules, or returns it unchanged. */
    public static byte[] apply(byte[] classBytes, Set<String> flags) {
        if (classBytes == null || !(flags.contains(NEIGHBOR_CHANGED) || flags.contains(ON_REMOVE)
                || flags.contains(CLONE_STACK) || flags.contains(FALL_ON) || flags.contains(ENTITY_INSIDE))) {
            return classBytes;
        }
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || SyntheticEmbedder.isRetromodClass(cn.name)
                || cn.name.startsWith("net/minecraft/") || StaticFieldIdStamper.isMixin(cn)) {
            return classBytes;
        }
        Set<String> declared = new HashSet<>();
        for (MethodNode m : cn.methods) declared.add(m.name + m.desc);
        int changes = 0;
        List<MethodNode> bridges = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            changes += rewriteSuperCalls(m, flags);
            if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT)) != 0) continue;
            String key = m.name + m.desc;
            if (flags.contains(NEIGHBOR_CHANGED) && key.equals("neighborChanged" + OLD_NEIGHBOR)
                    && !declared.contains("neighborChanged" + NEW_NEIGHBOR)) {
                bridges.add(neighborBridge(cn, m.access));
            } else if (flags.contains(ON_REMOVE) && key.equals("onRemove" + OLD_REMOVE)
                    && !declared.contains("affectNeighborsAfterRemoval" + NEW_REMOVE)) {
                bridges.add(removeBridge(cn, m.access));
            } else if (flags.contains(CLONE_STACK) && (key.equals("getCloneItemStack" + OLD_CLONE)
                    || key.equals("getCloneItemStack" + OLD_CLONE_1201)) && !declared.contains("getCloneItemStack" + NEW_CLONE)) {
                bridges.add(cloneBridge(cn, m.access, m.desc));
                declared.add("getCloneItemStack" + NEW_CLONE);
            } else if (flags.contains(FALL_ON) && key.equals("fallOn" + OLD_FALL)
                    && !declared.contains("fallOn" + NEW_FALL)) {
                bridges.add(fallBridge(cn, m.access));
            } else if (flags.contains(ENTITY_INSIDE) && key.equals("entityInside" + OLD_INSIDE)
                    && !declared.contains("entityInside" + NEW_INSIDE)) {
                bridges.add(insideBridge(cn, m.access));
            }
        }
        cn.methods.addAll(bridges);
        changes += bridges.size();
        if (changes == 0) return classBytes;
        try {
            SafeClassWriter cw = new SafeClassWriter(new ClassReader(classBytes), ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    /** A super call to an old hook on a game class reaches its current form instead. */
    private static int rewriteSuperCalls(MethodNode m, Set<String> flags) {
        if (m.instructions == null || m.instructions.size() == 0) return 0;
        int changes = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode external && external.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && external.owner.startsWith("net/minecraft/") && flags.contains(CLONE_STACK)
                    && external.name.equals("getCloneItemStack") && external.desc.equals(OLD_CLONE)) {
                // An outside call reaches the protected hook through the state's public form, which asks the same block.
                m.instructions.insertBefore(external, cloneThroughState(m));
                m.instructions.remove(external);
                changes++;
                continue;
            }
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.startsWith("net/minecraft/")) {
                continue;
            }
            String key = call.name + call.desc;
            if (flags.contains(NEIGHBOR_CHANGED) && key.equals("neighborChanged" + OLD_NEIGHBOR)) {
                // [.., fromPos, moved] to [.., null orientation, moved]: the source position has no orientation.
                int moved = m.maxLocals++;
                InsnList list = new InsnList();
                list.add(new VarInsnNode(Opcodes.ISTORE, moved));
                list.add(new InsnNode(Opcodes.POP));
                list.add(new InsnNode(Opcodes.ACONST_NULL));
                list.add(new VarInsnNode(Opcodes.ILOAD, moved));
                m.instructions.insertBefore(call, list);
                call.desc = NEW_NEIGHBOR;
                changes++;
            } else if (flags.contains(ON_REMOVE) && key.equals("onRemove" + OLD_REMOVE)) {
                m.instructions.insertBefore(call, superRemove(m, call.owner));
                m.instructions.remove(call);
                changes++;
            } else if (flags.contains(CLONE_STACK) && key.equals("getCloneItemStack" + OLD_CLONE_1201)) {
                // [.., getter, pos, state]: the getter the old override was given is the host's level reader.
                int state = m.maxLocals++;
                int pos = m.maxLocals++;
                InsnList list = new InsnList();
                list.add(new VarInsnNode(Opcodes.ASTORE, state));
                list.add(new VarInsnNode(Opcodes.ASTORE, pos));
                list.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/level/LevelReader"));
                list.add(new VarInsnNode(Opcodes.ALOAD, pos));
                list.add(new VarInsnNode(Opcodes.ALOAD, state));
                list.add(new InsnNode(Opcodes.ICONST_0));
                m.instructions.insertBefore(call, list);
                call.desc = NEW_CLONE;
                changes++;
            } else if (flags.contains(CLONE_STACK) && key.equals("getCloneItemStack" + OLD_CLONE)) {
                // The old call meant the middle-click copy, which carries no block entity data.
                m.instructions.insertBefore(call, new InsnNode(Opcodes.ICONST_0));
                call.desc = NEW_CLONE;
                changes++;
            } else if (flags.contains(FALL_ON) && key.equals("fallOn" + OLD_FALL)) {
                m.instructions.insertBefore(call, new InsnNode(Opcodes.F2D));
                call.desc = NEW_FALL;
                changes++;
            } else if (flags.contains(ENTITY_INSIDE) && key.equals("entityInside" + OLD_INSIDE)) {
                // Pass on the applier the host handed the bridge; a call from anywhere else gets the one that applies nothing.
                InsnList list = new InsnList();
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INSIDE_HELPER, "current", "()L" + APPLIER + ";", false));
                list.add(new InsnNode(Opcodes.ICONST_0));
                m.instructions.insertBefore(call, list);
                call.desc = NEW_INSIDE;
                changes++;
            }
        }
        return changes;
    }

    /**
     * {@code super.onRemove(state, level, pos, newState, moved)}: only a server level runs removal side effects now,
     * through {@code affectNeighborsAfterRemoval}; the block entity is already gone.
     */
    private static InsnList superRemove(MethodNode m, String owner) {
        int moved = m.maxLocals++;
        int newState = m.maxLocals++;
        int pos = m.maxLocals++;
        int level = m.maxLocals++;
        int state = m.maxLocals++;
        int self = m.maxLocals++;
        LabelNode skip = new LabelNode();
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ISTORE, moved));
        list.add(new VarInsnNode(Opcodes.ASTORE, newState));
        list.add(new VarInsnNode(Opcodes.ASTORE, pos));
        list.add(new VarInsnNode(Opcodes.ASTORE, level));
        list.add(new VarInsnNode(Opcodes.ASTORE, state));
        list.add(new VarInsnNode(Opcodes.ASTORE, self));
        list.add(new VarInsnNode(Opcodes.ALOAD, level));
        list.add(new TypeInsnNode(Opcodes.INSTANCEOF, SERVER_LEVEL_TYPE));
        list.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        list.add(new VarInsnNode(Opcodes.ALOAD, self));
        list.add(new VarInsnNode(Opcodes.ALOAD, state));
        list.add(new VarInsnNode(Opcodes.ALOAD, level));
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, SERVER_LEVEL_TYPE));
        list.add(new VarInsnNode(Opcodes.ALOAD, pos));
        list.add(new VarInsnNode(Opcodes.ILOAD, moved));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "affectNeighborsAfterRemoval", NEW_REMOVE, false));
        list.add(skip);
        return list;
    }

    /** {@code [block, level, pos, state]} to {@code state.getCloneItemStack(level, pos, false)}. */
    private static InsnList cloneThroughState(MethodNode m) {
        int state = m.maxLocals++;
        int pos = m.maxLocals++;
        int level = m.maxLocals++;
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ASTORE, state));
        list.add(new VarInsnNode(Opcodes.ASTORE, pos));
        list.add(new VarInsnNode(Opcodes.ASTORE, level));
        list.add(new InsnNode(Opcodes.POP));
        list.add(new VarInsnNode(Opcodes.ALOAD, state));
        list.add(new VarInsnNode(Opcodes.ALOAD, level));
        list.add(new VarInsnNode(Opcodes.ALOAD, pos));
        list.add(new InsnNode(Opcodes.ICONST_0));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState", "getCloneItemStack",
                "(" + READER + POS + "Z)" + STACK, false));
        return list;
    }

    /** The current neighborChanged runs the old override with the source position the orientation points from. */
    private static MethodNode neighborBridge(ClassNode cn, int oldAccess) {
        MethodNode bridge = new MethodNode(bridgeAccess(oldAccess), "neighborChanged", NEW_NEIGHBOR, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 4; slot++) bridge.visitVarInsn(Opcodes.ALOAD, slot);
        // Without an orientation the update names no source, so the block's own position stands in.
        Label known = new Label();
        Label done = new Label();
        bridge.visitVarInsn(Opcodes.ALOAD, 5);
        bridge.visitJumpInsn(Opcodes.IFNONNULL, known);
        bridge.visitVarInsn(Opcodes.ALOAD, 3);
        bridge.visitJumpInsn(Opcodes.GOTO, done);
        bridge.visitLabel(known);
        bridge.visitVarInsn(Opcodes.ALOAD, 3);
        bridge.visitVarInsn(Opcodes.ALOAD, 5);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ORIENTATION_TYPE, "getFront", "()L" + DIRECTION_TYPE + ";", false);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DIRECTION_TYPE, "getOpposite", "()L" + DIRECTION_TYPE + ";", false);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POS_TYPE, "relative", "(L" + DIRECTION_TYPE + ";)" + POS, false);
        bridge.visitLabel(done);
        bridge.visitVarInsn(Opcodes.ILOAD, 6);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, "neighborChanged", OLD_NEIGHBOR, false);
        bridge.visitInsn(Opcodes.RETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        return bridge;
    }

    /** The current removal hook runs the old onRemove with the state now at the position, which replaced this one. */
    private static MethodNode removeBridge(ClassNode cn, int oldAccess) {
        MethodNode bridge = new MethodNode(bridgeAccess(oldAccess), "affectNeighborsAfterRemoval", NEW_REMOVE, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        bridge.visitVarInsn(Opcodes.ALOAD, 1);
        bridge.visitVarInsn(Opcodes.ALOAD, 2);
        bridge.visitVarInsn(Opcodes.ALOAD, 3);
        bridge.visitVarInsn(Opcodes.ALOAD, 2);
        bridge.visitVarInsn(Opcodes.ALOAD, 3);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL_TYPE, "getBlockState", "(" + POS + ")" + STATE, false);
        bridge.visitVarInsn(Opcodes.ILOAD, 4);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, "onRemove", OLD_REMOVE, false);
        bridge.visitInsn(Opcodes.RETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        return bridge;
    }

    /** The current clone call runs the old override, which never had block entity data to include. */
    private static MethodNode cloneBridge(ClassNode cn, int oldAccess, String oldDesc) {
        MethodNode bridge = new MethodNode(bridgeAccess(oldAccess), "getCloneItemStack", NEW_CLONE, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 3; slot++) bridge.visitVarInsn(Opcodes.ALOAD, slot);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, "getCloneItemStack", oldDesc, false);
        bridge.visitInsn(Opcodes.ARETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        return bridge;
    }

    /** The current fallOn passes the fall distance as a double; the old override takes a float. */
    private static MethodNode fallBridge(ClassNode cn, int oldAccess) {
        MethodNode bridge = new MethodNode(bridgeAccess(oldAccess) & ~Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC
                | Opcodes.ACC_SYNTHETIC, "fallOn", NEW_FALL, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 4; slot++) bridge.visitVarInsn(Opcodes.ALOAD, slot);
        bridge.visitVarInsn(Opcodes.DLOAD, 5);
        bridge.visitInsn(Opcodes.D2F);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, "fallOn", OLD_FALL, false);
        bridge.visitInsn(Opcodes.RETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        return bridge;
    }

    /** The current entityInside runs the old override, which knew nothing of effect appliers. */
    private static MethodNode insideBridge(ClassNode cn, int oldAccess) {
        MethodNode bridge = new MethodNode(bridgeAccess(oldAccess), "entityInside", NEW_INSIDE, null, null);
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        bridge.visitCode();
        bridge.visitTryCatchBlock(start, end, failed, null);
        // Keep the host's applier for the override's super call, and put back the outer one afterwards.
        bridge.visitVarInsn(Opcodes.ALOAD, 5);
        bridge.visitMethodInsn(Opcodes.INVOKESTATIC, INSIDE_HELPER, "enter", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        bridge.visitVarInsn(Opcodes.ASTORE, 7);
        bridge.visitLabel(start);
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 4; slot++) bridge.visitVarInsn(Opcodes.ALOAD, slot);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, "entityInside", OLD_INSIDE, false);
        bridge.visitLabel(end);
        bridge.visitVarInsn(Opcodes.ALOAD, 7);
        bridge.visitMethodInsn(Opcodes.INVOKESTATIC, INSIDE_HELPER, "exit", "(Ljava/lang/Object;)V", false);
        bridge.visitInsn(Opcodes.RETURN);
        bridge.visitLabel(failed);
        bridge.visitVarInsn(Opcodes.ASTORE, 8);
        bridge.visitVarInsn(Opcodes.ALOAD, 7);
        bridge.visitMethodInsn(Opcodes.INVOKESTATIC, INSIDE_HELPER, "exit", "(Ljava/lang/Object;)V", false);
        bridge.visitVarInsn(Opcodes.ALOAD, 8);
        bridge.visitInsn(Opcodes.ATHROW);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        return bridge;
    }

    /** A per-thread slot for the applier: enter returns the outer value, exit restores it, current falls back to NOOP. */
    static byte[] generateInsideHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        String local = "java/lang/ThreadLocal";
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, INSIDE_HELPER, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CURRENT", "L" + local + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, local);
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, local, "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, INSIDE_HELPER, "CURRENT", "L" + local + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor enter = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "enter",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        enter.visitCode();
        enter.visitFieldInsn(Opcodes.GETSTATIC, INSIDE_HELPER, "CURRENT", "L" + local + ";");
        enter.visitMethodInsn(Opcodes.INVOKEVIRTUAL, local, "get", "()Ljava/lang/Object;", false);
        enter.visitFieldInsn(Opcodes.GETSTATIC, INSIDE_HELPER, "CURRENT", "L" + local + ";");
        enter.visitVarInsn(Opcodes.ALOAD, 0);
        enter.visitMethodInsn(Opcodes.INVOKEVIRTUAL, local, "set", "(Ljava/lang/Object;)V", false);
        enter.visitInsn(Opcodes.ARETURN);
        enter.visitMaxs(0, 0);
        enter.visitEnd();
        // An outer value of null means nothing was running, so the slot is removed rather than left holding null.
        MethodVisitor exit = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "exit", "(Ljava/lang/Object;)V", null, null);
        Label outer = new Label();
        exit.visitCode();
        exit.visitFieldInsn(Opcodes.GETSTATIC, INSIDE_HELPER, "CURRENT", "L" + local + ";");
        exit.visitVarInsn(Opcodes.ALOAD, 0);
        exit.visitJumpInsn(Opcodes.IFNONNULL, outer);
        exit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, local, "remove", "()V", false);
        exit.visitInsn(Opcodes.RETURN);
        exit.visitLabel(outer);
        exit.visitVarInsn(Opcodes.ALOAD, 0);
        exit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, local, "set", "(Ljava/lang/Object;)V", false);
        exit.visitInsn(Opcodes.RETURN);
        exit.visitMaxs(0, 0);
        exit.visitEnd();
        MethodVisitor current = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "()L" + APPLIER + ";", null, null);
        Label none = new Label();
        current.visitCode();
        current.visitFieldInsn(Opcodes.GETSTATIC, INSIDE_HELPER, "CURRENT", "L" + local + ";");
        current.visitMethodInsn(Opcodes.INVOKEVIRTUAL, local, "get", "()Ljava/lang/Object;", false);
        current.visitInsn(Opcodes.DUP);
        current.visitJumpInsn(Opcodes.IFNULL, none);
        current.visitTypeInsn(Opcodes.CHECKCAST, APPLIER);
        current.visitInsn(Opcodes.ARETURN);
        current.visitLabel(none);
        current.visitInsn(Opcodes.POP);
        current.visitFieldInsn(Opcodes.GETSTATIC, APPLIER, "NOOP", "L" + APPLIER + ";");
        current.visitInsn(Opcodes.ARETURN);
        current.visitMaxs(0, 0);
        current.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static int bridgeAccess(int oldAccess) {
        int visibility = oldAccess & Opcodes.ACC_PUBLIC;
        return (visibility != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED) | Opcodes.ACC_SYNTHETIC;
    }
}
