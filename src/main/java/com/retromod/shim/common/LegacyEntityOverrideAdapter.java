/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Keeps an old entity's fall damage and damage overrides in the game after Minecraft reshaped them. */
public final class LegacyEntityOverrideAdapter {

    public static final String FALL_FLAG = "entity.fall-damage-double";
    public static final String HURT_FLAG = "entity.hurt-server";

    static final String ENTITY = LegacyVanillaMemberBridge.ENTITY;
    static final String SOURCE = "Lnet/minecraft/world/damagesource/DamageSource;";
    static final String OLD_FALL = "(FF" + SOURCE + ")Z";
    static final String NEW_FALL = "(DF" + SOURCE + ")Z";
    static final String OLD_HURT = "(" + SOURCE + "F)Z";
    // A Fabric mod's old hurt override arrives under this name: the 26.x name table follows its intermediary id there.
    static final String FABRIC_HURT_NAME = Legacy1212MemberBridge.HURT_CLIENT_NAME;
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    // Host classes that leave hurtServer abstract: Entity itself and the loaders' multipart entity bases.
    static final List<String> NO_HURT_SERVER = List.of(ENTITY, "net/neoforged/neoforge/entity/PartEntity",
            "net/minecraftforge/entity/PartEntity");
    private static final int MAX_DEPTH = 16;
    static final String HURT_SERVER = "(L" + SERVER_LEVEL + ";" + SOURCE + "F)Z";
    static final String GUARD = LegacyRemovedMemberBridge.ENTITY_MEMBERS;
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    // The old hurt body, kept private under this name while a guarded hurt takes its place.
    static final String OLD_HURT_BODY = "retromod$legacyHurt";
    // hurtServer arrived in 1.21.2; the fall distance became a double by 1.21.11.
    static final String FIRST_HURT_SERVER_VERSION = "1.21.2";
    static final String FIRST_DOUBLE_FALL_VERSION = "1.21.11";

    private LegacyEntityOverrideAdapter() {}

    /**
     * Returns the class unchanged unless it calls or overrides the old fall damage, or extends Entity without
     * hurtServer, in a mod built before each change.
     */
    public static byte[] apply(byte[] bytes, boolean fall, boolean hurt, String modSourceVersion) {
        if (modSourceVersion == null) return bytes;
        return apply(bytes, fall && before(modSourceVersion, FIRST_DOUBLE_FALL_VERSION),
                hurt && before(modSourceVersion, FIRST_HURT_SERVER_VERSION), RetromodTransformer::currentJarClassInheritsFrom,
                RetromodTransformer::readCurrentJarClassForAdapter);
    }

    private static boolean before(String version, String change) {
        return RetromodVersion.compareMcVersions(version, change) < 0;
    }

    static byte[] apply(byte[] bytes, boolean fall, boolean hurt, BiPredicate<String, String> inheritsFrom,
            Function<String, byte[]> jarClass) {
        if (!fall && !hurt) return bytes;
        ClassReader reader = new ClassReader(bytes);
        if (reader.getClassName().startsWith("net/minecraft/")) return bytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return bytes;
        // Mixin merges a mixin's members into its target, so a member added there would replace the host's own.
        boolean mixin = isMixin(node);
        boolean changed = false;
        if (fall) {
            for (MethodNode method : node.methods) changed |= widenFallCalls(method, inheritsFrom);
            if (!mixin) changed |= addFallBridge(node, inheritsFrom);
        }
        if (hurt && !mixin) changed |= addHurtServer(node, jarClass);
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    // An old causeFallDamage(float, float, source) call, a super call included, takes the fall distance as a double.
    private static boolean widenFallCalls(MethodNode method, BiPredicate<String, String> inheritsFrom) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || !call.name.equals("causeFallDamage") || !call.desc.equals(OLD_FALL)
                    || (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL)
                    || !isEntity(call.owner, inheritsFrom)) continue;
            // The source and multiplier wait in two fresh locals while the distance under them widens.
            int slot = method.maxLocals;
            InsnList widen = new InsnList();
            widen.add(new VarInsnNode(Opcodes.ASTORE, slot));
            widen.add(new VarInsnNode(Opcodes.FSTORE, slot + 1));
            widen.add(new InsnNode(Opcodes.F2D));
            widen.add(new VarInsnNode(Opcodes.FLOAD, slot + 1));
            widen.add(new VarInsnNode(Opcodes.ALOAD, slot));
            method.instructions.insertBefore(call, widen);
            call.desc = NEW_FALL;
            method.maxLocals += 2;
            changed = true;
        }
        // The double distance takes one stack slot more than the float did, wherever the call sits.
        if (changed) method.maxStack += 1;
        return changed;
    }

    /** A class overriding the old form gets the new one, which runs its own override and nothing else. */
    private static boolean addFallBridge(ClassNode node, BiPredicate<String, String> inheritsFrom) {
        if (find(node, "causeFallDamage", OLD_FALL) == null || find(node, "causeFallDamage", NEW_FALL) != null
                || !isEntity(node.superName, inheritsFrom)) return false;
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, "causeFallDamage", NEW_FALL, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.DLOAD, 1));
        bridge.instructions.add(new InsnNode(Opcodes.D2F));
        bridge.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        // invokespecial on the class itself runs exactly this override, so a subclass's own bridge is never re-entered.
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, "causeFallDamage", OLD_FALL, false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        bridge.maxStack = 4;
        bridge.maxLocals = 5;
        node.methods.add(bridge);
        return true;
    }

    /**
     * Entity left hurtServer abstract in 1.21.2, so a class built on Entity or a loader's PartEntity fails when it is
     * damaged. Each such class that overrides the old hurt gets a hurtServer that runs it, and the first class above
     * the host one that answers as Entity's old hurt did. The override's super call comes back to hurtServer
     * through the host, and while the override runs that entry answers as Entity's old hurt did too.
     */
    private static boolean addHurtServer(ClassNode node, Function<String, byte[]> jarClass) {
        // A chain that leaves the mod through a library class is unknown, so the class is left alone.
        String hostClass = nearestHostClass(node.superName, jarClass);
        if (find(node, "hurtServer", HURT_SERVER) != null || hostClass == null || !NO_HURT_SERVER.contains(hostClass)) {
            return false;
        }
        MethodNode override = find(node, "hurt", OLD_HURT);
        if (override == null) override = find(node, FABRIC_HURT_NAME, OLD_HURT);
        if (override == null && !isHostClass(node.superName)) return false;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "hurtServer", HURT_SERVER, null, null);
        InsnList code = method.instructions;
        method.maxStack = 3;
        method.maxLocals = 4;
        node.methods.add(method);
        if (override == null) {
            addBaseHurt(code, node.name);
            return true;
        }
        // The override marks itself while it runs, however it was entered, so it runs once per hit.
        String name = override.name;
        if ((override.access & Opcodes.ACC_ABSTRACT) == 0) guardOverride(node, override);
        LabelNode run = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "isHurting", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, run));
        addBaseHurt(code, node.name);
        code.add(run);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, name, OLD_HURT, false));
        code.add(new InsnNode(Opcodes.IRETURN));
        return true;
    }

    /** Entity's old hurt: marks the entity hurt, so its motion syncs, unless the source cannot touch it, and answers false. */
    private static void addBaseHurt(InsnList code, String owner) {
        LabelNode answer = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "isInvulnerableToBase", "(" + SOURCE + ")Z", false));
        code.add(new JumpInsnNode(Opcodes.IFNE, answer));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "markHurt", "()V", false));
        code.add(answer);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
    }

    /** Moves the old hurt body to a private method and puts a method of the same name in its place that marks the entity while it runs. */
    private static void guardOverride(ClassNode node, MethodNode override) {
        int visibility = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        MethodNode wrapper = new MethodNode(override.access & (visibility | Opcodes.ACC_FINAL), override.name, OLD_HURT,
                override.signature, override.exceptions.toArray(new String[0]));
        override.name = OLD_HURT_BODY;
        override.access = (override.access & ~(visibility | Opcodes.ACC_FINAL)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        String source = SOURCE.substring(1, SOURCE.length() - 1);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode rethrow = new LabelNode();
        InsnList code = wrapper.instructions;
        // entered is false when an outer call already marked the entity; only the outer call clears the mark.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "enterHurt", "(Ljava/lang/Object;)Z", false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, OLD_HURT_BODY, OLD_HURT, false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(end);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "exitHurt", "(Ljava/lang/Object;)V", false));
        code.add(done);
        code.add(new FrameNode(Opcodes.F_FULL, 5, new Object[] {node.name, source, Opcodes.FLOAT, Opcodes.INTEGER,
                Opcodes.INTEGER}, 0, new Object[0]));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));
        // An exception from the override still clears the mark.
        code.add(handler);
        code.add(new FrameNode(Opcodes.F_FULL, 4, new Object[] {node.name, source, Opcodes.FLOAT, Opcodes.INTEGER},
                1, new Object[] {"java/lang/Throwable"}));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFEQ, rethrow));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "exitHurt", "(Ljava/lang/Object;)V", false));
        code.add(rethrow);
        code.add(new FrameNode(Opcodes.F_FULL, 5, new Object[] {node.name, source, Opcodes.FLOAT, Opcodes.INTEGER,
                "java/lang/Throwable"}, 0, new Object[0]));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks = new ArrayList<>(List.of(new TryCatchBlockNode(start, end, handler, null)));
        wrapper.maxStack = 3;
        wrapper.maxLocals = 5;
        node.methods.add(wrapper);
    }

    /** The first host class up the superclass chain, read through the mod's own classes; null when the chain is unknown. */
    static String nearestHostClass(String superName, Function<String, byte[]> jarClass) {
        String current = superName;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (isHostClass(current)) return current;
            byte[] bytes = jarClass.apply(current);
            if (bytes == null) return null;
            current = new ClassReader(bytes).getSuperName();
        }
        return null;
    }

    private static boolean isHostClass(String name) {
        return name.startsWith("net/minecraft/") || name.startsWith("net/neoforged/") || name.startsWith("net/minecraftforge/")
                || name.startsWith("java/");
    }

    private static boolean isMixin(ClassNode node) {
        for (List<AnnotationNode> annotations : List.of(
                node.invisibleAnnotations == null ? List.<AnnotationNode>of() : node.invisibleAnnotations,
                node.visibleAnnotations == null ? List.<AnnotationNode>of() : node.visibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN.equals(annotation.desc)) return true;
            }
        }
        return false;
    }

    private static boolean isEntity(String owner, BiPredicate<String, String> inheritsFrom) {
        return owner != null && (LegacyVanillaMemberBridge.ENTITY_OWNERS.contains(owner) || inheritsFrom.test(owner, ENTITY));
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc) && (method.access & Opcodes.ACC_STATIC) == 0) return method;
        }
        return null;
    }
}
