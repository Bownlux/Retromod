/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Repairs the 1.21.2 block and block item changes a 1.21.1 mod cannot see.
 *
 * <ul>
 *   <li>{@code updateShape} and {@code getOcclusionShape} changed their parameters. An override
 *   with the old descriptor still loads, but Minecraft never calls it, so connected blocks stop
 *   connecting and a super call to the old descriptor throws {@code NoSuchMethodError}. A bridge
 *   with the new descriptor forwards to the mod's method, and super calls use the new
 *   descriptor.</li>
 *   <li>{@code new BlockItem(block, properties)} keeps the block's description id, as it did up to
 *   1.21.1.</li>
 * </ul>
 *
 * <p>Runs only when {@link LegacyBlockApiSynthetic} is registered. The overrides are matched by
 * their exact vanilla descriptor, and only super calls owned by a Minecraft class are rewritten.
 */
public final class LegacyBlockApiAdapter {

    private LegacyBlockApiAdapter() {}

    private static final String BRIDGE = LegacyBlockApiSynthetic.INTERNAL;
    private static final String BLOCK = LegacyBlockApiSynthetic.BLOCK;
    private static final String ITEM_PROPERTIES = LegacyBlockApiSynthetic.ITEM_PROPERTIES;

    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String LEVEL_ACCESSOR = "net/minecraft/world/level/LevelAccessor";
    private static final String LEVEL_READER = "Lnet/minecraft/world/level/LevelReader;";
    private static final String TICK_ACCESS = "Lnet/minecraft/world/level/ScheduledTickAccess;";
    private static final String RANDOM = "Lnet/minecraft/util/RandomSource;";
    private static final String SHAPE = "Lnet/minecraft/world/phys/shapes/VoxelShape;";

    static final String UPDATE_SHAPE = "updateShape";
    static final String OLD_UPDATE_SHAPE =
            "(" + STATE + DIRECTION + STATE + "L" + LEVEL_ACCESSOR + ";" + POS + POS + ")" + STATE;
    static final String NEW_UPDATE_SHAPE =
            "(" + STATE + LEVEL_READER + TICK_ACCESS + POS + DIRECTION + POS + STATE + RANDOM + ")"
                    + STATE;

    static final String OCCLUSION_SHAPE = "getOcclusionShape";
    static final String OLD_OCCLUSION_SHAPE =
            "(" + STATE + "Lnet/minecraft/world/level/BlockGetter;" + POS + ")" + SHAPE;
    static final String NEW_OCCLUSION_SHAPE = "(" + STATE + ")" + SHAPE;

    /** Vanilla block items whose constructor is exactly {@code (Block, Item.Properties)}. */
    private static final Set<String> BLOCK_ITEMS = Set.of(
            "net/minecraft/world/item/BlockItem",
            "net/minecraft/world/item/BedItem",
            "net/minecraft/world/item/DoubleHighBlockItem",
            "net/minecraft/world/item/GameMasterBlockItem",
            "net/minecraft/world/item/PlaceOnWaterBlockItem",
            "net/minecraft/world/item/ScaffoldingBlockItem");
    private static final String BLOCK_ITEM_INIT = "(L" + BLOCK + ";L" + ITEM_PROPERTIES + ";)V";

    /**
     * @param classBytes the class to repair
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || cn.name.startsWith("com/retromod/")) {
            return classBytes;
        }

        Set<String> declared = new HashSet<>();
        for (MethodNode m : cn.methods) declared.add(m.name + m.desc);

        int changes = 0;
        List<MethodNode> bridges = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            changes += rewriteCalls(m);
            if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) continue;
            if (UPDATE_SHAPE.equals(m.name) && OLD_UPDATE_SHAPE.equals(m.desc)
                    && !declared.contains(UPDATE_SHAPE + NEW_UPDATE_SHAPE)) {
                bridges.add(updateShapeBridge(cn, m.access));
            } else if (OCCLUSION_SHAPE.equals(m.name) && OLD_OCCLUSION_SHAPE.equals(m.desc)
                    && !declared.contains(OCCLUSION_SHAPE + NEW_OCCLUSION_SHAPE)) {
                bridges.add(occlusionShapeBridge(cn, m.access));
            }
        }
        cn.methods.addAll(bridges);
        changes += bridges.size();
        if (changes == 0) return classBytes;

        try {
            SafeClassWriter cw = new SafeClassWriter(new ClassReader(classBytes),
                    ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    private static int rewriteCalls(MethodNode m) {
        if (m.instructions == null || m.instructions.size() == 0) return 0;
        int changes = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL) {
                continue;
            }
            if ("<init>".equals(call.name) && BLOCK_ITEM_INIT.equals(call.desc)
                    && BLOCK_ITEMS.contains(call.owner)) {
                m.instructions.insertBefore(call, keepBlockDescription());
                changes++;
            } else if (!call.owner.startsWith("net/minecraft/")) {
                continue;
            } else if (UPDATE_SHAPE.equals(call.name) && OLD_UPDATE_SHAPE.equals(call.desc)) {
                m.instructions.insertBefore(call, reorderUpdateShapeArguments(m));
                call.desc = NEW_UPDATE_SHAPE;
                changes++;
            } else if (OCCLUSION_SHAPE.equals(call.name) && OLD_OCCLUSION_SHAPE.equals(call.desc)) {
                InsnList dropLevelAndPos = new InsnList();
                dropLevelAndPos.add(new InsnNode(Opcodes.POP));
                dropLevelAndPos.add(new InsnNode(Opcodes.POP));
                m.instructions.insertBefore(call, dropLevelAndPos);
                call.desc = NEW_OCCLUSION_SHAPE;
                changes++;
            }
        }
        return changes;
    }

    /** {@code [block, props]} to {@code [block, bridge.blockItemProperties(block, props)]}. */
    private static InsnList keepBlockDescription() {
        InsnList list = new InsnList();
        list.add(new InsnNode(Opcodes.DUP2));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "blockItemProperties",
                LegacyBlockApiSynthetic.BLOCK_ITEM_PROPERTIES_DESC, false));
        list.add(new InsnNode(Opcodes.SWAP));
        list.add(new InsnNode(Opcodes.POP));
        return list;
    }

    /**
     * {@code [state, direction, neighborState, level, pos, neighborPos]} to
     * {@code [state, level, level, pos, direction, neighborPos, neighborState, level.getRandom()]}.
     */
    private static InsnList reorderUpdateShapeArguments(MethodNode m) {
        int direction = m.maxLocals;
        int neighborState = direction + 1;
        int level = direction + 2;
        int pos = direction + 3;
        int neighborPos = direction + 4;
        m.maxLocals += 5;

        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ASTORE, neighborPos));
        list.add(new VarInsnNode(Opcodes.ASTORE, pos));
        list.add(new VarInsnNode(Opcodes.ASTORE, level));
        list.add(new VarInsnNode(Opcodes.ASTORE, neighborState));
        list.add(new VarInsnNode(Opcodes.ASTORE, direction));
        list.add(new VarInsnNode(Opcodes.ALOAD, level));
        list.add(new VarInsnNode(Opcodes.ALOAD, level));
        list.add(new VarInsnNode(Opcodes.ALOAD, pos));
        list.add(new VarInsnNode(Opcodes.ALOAD, direction));
        list.add(new VarInsnNode(Opcodes.ALOAD, neighborPos));
        list.add(new VarInsnNode(Opcodes.ALOAD, neighborState));
        list.add(new VarInsnNode(Opcodes.ALOAD, level));
        list.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, LEVEL_ACCESSOR, "getRandom",
                "()" + RANDOM, true));
        return list;
    }

    /**
     * The new {@code updateShape} forwards to the mod's old override. Both level arguments are
     * {@code LevelAccessor}s in vanilla; if neither is, the vanilla implementation runs instead.
     */
    private static MethodNode updateShapeBridge(ClassNode cn, int oldAccess) {
        MethodNode bridge = new MethodNode(bridgeAccess(oldAccess), UPDATE_SHAPE, NEW_UPDATE_SHAPE,
                null, null);
        MethodVisitor mv = bridge;
        mv.visitCode();
        for (int levelSlot : new int[]{2, 3}) {
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, levelSlot);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, LEVEL_ACCESSOR);
            mv.visitJumpInsn(Opcodes.IFEQ, next);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1); // state
            mv.visitVarInsn(Opcodes.ALOAD, 5); // direction
            mv.visitVarInsn(Opcodes.ALOAD, 7); // neighbor state
            mv.visitVarInsn(Opcodes.ALOAD, levelSlot);
            mv.visitTypeInsn(Opcodes.CHECKCAST, LEVEL_ACCESSOR);
            mv.visitVarInsn(Opcodes.ALOAD, 4); // pos
            mv.visitVarInsn(Opcodes.ALOAD, 6); // neighbor pos
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, UPDATE_SHAPE, OLD_UPDATE_SHAPE, false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(next);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 8; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, cn.superName, UPDATE_SHAPE, NEW_UPDATE_SHAPE, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return bridge;
    }

    /**
     * 1.21.1 cached the occlusion shape by calling the old method with an empty getter at
     * {@code BlockPos.ZERO}. The bridge makes the same call.
     */
    private static MethodNode occlusionShapeBridge(ClassNode cn, int oldAccess) {
        MethodNode bridge = new MethodNode(bridgeAccess(oldAccess), OCCLUSION_SHAPE,
                NEW_OCCLUSION_SHAPE, null, null);
        MethodVisitor mv = bridge;
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/level/EmptyBlockGetter",
                "INSTANCE", "Lnet/minecraft/world/level/EmptyBlockGetter;");
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/BlockPos", "ZERO", POS);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, OCCLUSION_SHAPE, OLD_OCCLUSION_SHAPE,
                false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return bridge;
    }

    private static int bridgeAccess(int oldAccess) {
        int visibility = oldAccess & Opcodes.ACC_PUBLIC;
        return (visibility != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED) | Opcodes.ACC_SYNTHETIC;
    }
}
