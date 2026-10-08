/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Forge's {@code LivingEvent.LivingTickEvent} fired for living entities only. NeoForge replaced it
 * with {@code EntityTickEvent.Pre}, which fires for every entity, and
 * {@link ForgeNeoForgeApiBridge} maps the class onto it. A migrated listener still reads
 * {@code event.getEntity()} as a {@code LivingEntity}, so the call is retyped to NeoForge's
 * {@code Entity} getter with a cast, and each such listener first returns early for an entity that
 * is not living, exactly the set Forge never delivered.
 */
public final class LegacyLivingTickAdapter {

    static final String TICK = "net/neoforged/neoforge/event/tick/EntityTickEvent$Pre";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String LIVING_GETTER = "()L" + LIVING + ";";

    private LegacyLivingTickAdapter() {}

    public static byte[] apply(byte[] classBytes) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!constants.contains(TICK) || !constants.contains(LIVING_GETTER)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            boolean readsLiving = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(TICK)
                        && call.name.equals("getEntity") && call.desc.equals(LIVING_GETTER)) {
                    call.desc = "()L" + ENTITY + ";";
                    method.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, LIVING));
                    readsLiving = true;
                    changed = true;
                }
            }
            if (readsLiving && isTickListener(method)) guard(node, method);
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Any {@code void (EntityTickEvent.Pre)} method, annotated or not: Forge mods also listen
     * through {@code addListener(this::onTick)} or a lambda, and those reach NeoForge as plain
     * methods. Only a migrated Forge listener reads the living-entity getter, so a NeoForge mod's
     * own tick listener is never guarded.
     */
    private static boolean isTickListener(MethodNode method) {
        Type[] parameters = Type.getArgumentTypes(method.desc);
        return parameters.length == 1 && parameters[0].getInternalName().equals(TICK)
                && Type.getReturnType(method.desc) == Type.VOID_TYPE
                && (method.access & Opcodes.ACC_ABSTRACT) == 0;
    }

    private static void guard(ClassNode owner, MethodNode method) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        LabelNode living = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, isStatic ? 0 : 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TICK, "getEntity", "()L" + ENTITY + ";", false));
        guard.add(new TypeInsnNode(Opcodes.INSTANCEOF, LIVING));
        guard.add(new JumpInsnNode(Opcodes.IFNE, living));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(living);
        if (!startsWithFrame(method)) {
            List<Object> locals = new ArrayList<>();
            if (!isStatic) locals.add(owner.name);
            locals.add(TICK);
            guard.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]));
        }
        method.instructions.insert(guard);
    }

    private static boolean startsWithFrame(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode) return true;
            if (insn.getOpcode() >= 0) return false;
        }
        return false;
    }
}
