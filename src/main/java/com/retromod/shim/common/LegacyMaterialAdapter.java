/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.objectweb.asm.Opcodes.*;

/**
 * Repairs block material queries from mods built before 1.20.
 *
 * <p>1.20 removed {@code Material} and {@code BlockState.getMaterial()}. The questions mods asked
 * of a material moved onto the block state itself: {@code state.getMaterial().blocksMotion()} is
 * {@code state.blocksMotion()}, {@code isSolid()} is {@code isSolid()}, {@code isLiquid()} is
 * {@code liquid()}, {@code isReplaceable()} is {@code canBeReplaced()}, and {@code isFlammable()}
 * is {@code ignitedByLava()}. {@code state.getMaterial() == Material.AIR} is {@code isAir()}.
 * Without this the first such query, often in a mob's tick, stops the server with
 * {@code NoSuchMethodError}.
 *
 * <p>Only those exact shapes are rewritten. A comparison with any other material constant, or a
 * material stored in a variable, has no faithful state query and is left to fail as before.
 */
public final class LegacyMaterialAdapter {

    static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String MATERIAL = "net/minecraft/world/level/material/Material";
    private static final String GET_MATERIAL_DESC = "()L" + MATERIAL + ";";

    /** Material query to the block state query that replaced it. */
    static final Map<String, String> QUERIES = Map.of(
        "blocksMotion", "blocksMotion",
        "isSolid", "isSolid",
        "isLiquid", "liquid",
        "isReplaceable", "canBeReplaced",
        "isFlammable", "ignitedByLava");

    private LegacyMaterialAdapter() {
    }

    /**
     * Runs before the remap, because the remap turns {@code Material.AIR} into a plain null that
     * no longer says which material was compared. Names are read through {@code methodName} and
     * {@code fieldName}, which turn a source SRG id into its Mojang name. The replacement calls
     * use Mojang names on the block state base, which the remap then gives their host names.
     *
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] apply(byte[] classBytes, UnaryOperator<String> methodName,
            UnaryOperator<String> fieldName) {
        if (classBytes == null) return null;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("com/retromod/") || node.name.startsWith("net/minecraft/")) {
            return classBytes;
        }
        Names names = new Names(methodName, fieldName);
        int changes = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!names.isGetMaterial(insn)) continue;
                if (rewrite(method.instructions, (MethodInsnNode) insn, names)) changes++;
            }
        }
        if (changes == 0) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** For bytecode that already uses Mojang names. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, UnaryOperator.identity(), UnaryOperator.identity());
    }

    private static boolean rewrite(InsnList code, MethodInsnNode getMaterial, Names names) {
        AbstractInsnNode next = nextReal(getMaterial);
        if (next instanceof MethodInsnNode query && query.getOpcode() == INVOKEVIRTUAL
                && query.owner.equals(MATERIAL) && query.desc.equals("()Z")
                && QUERIES.containsKey(names.method(query.name))) {
            code.set(getMaterial, stateQuery(QUERIES.get(names.method(query.name))));
            code.remove(query);
            return true;
        }
        if (next instanceof FieldInsnNode constant && constant.getOpcode() == GETSTATIC
                && constant.owner.equals(MATERIAL) && names.field(constant.name).equals("AIR")) {
            AbstractInsnNode compare = nextReal(constant);
            if (compare != null && (compare.getOpcode() == IF_ACMPEQ || compare.getOpcode() == IF_ACMPNE)) {
                JumpInsnNode jump = (JumpInsnNode) compare;
                code.set(getMaterial, stateQuery("isAir"));
                code.remove(constant);
                code.set(jump, new JumpInsnNode(jump.getOpcode() == IF_ACMPEQ ? IFNE : IFEQ, jump.label));
                return true;
            }
        }
        return false;
    }

    /** Every block state extends the base that declares these queries, so the call links. */
    private static MethodInsnNode stateQuery(String name) {
        return new MethodInsnNode(INVOKEVIRTUAL, STATE_BASE, name, "()Z", false);
    }

    /** Resolves a member name to Mojang, keeping it when the resolver does not know it. */
    private record Names(UnaryOperator<String> methods, UnaryOperator<String> fields) {
        String method(String name) {
            String mojang = methods.apply(name);
            return mojang != null ? mojang : name;
        }

        String field(String name) {
            String mojang = fields.apply(name);
            return mojang != null ? mojang : name;
        }

        boolean isGetMaterial(AbstractInsnNode insn) {
            return insn instanceof MethodInsnNode call && call.getOpcode() == INVOKEVIRTUAL
                    && call.desc.equals(GET_MATERIAL_DESC)
                    && (call.owner.equals(STATE_BASE)
                        || call.owner.equals("net/minecraft/world/level/block/state/BlockState"))
                    && method(call.name).equals("getMaterial");
        }
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }
}
