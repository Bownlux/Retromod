/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/**
 * Keeps an older block's right-click override working on 1.20.5 and newer.
 *
 * <p>1.20.5 split {@code Block.use(state, level, pos, player, hand, hit)} into
 * {@code useWithoutItem(state, level, pos, player, hit)} and {@code useItemOn(...)}. An old
 * override was then never called, so the block lost its interaction, and its
 * {@code super.use(...)} call failed with {@code NoSuchMethodError}. The old method stays, and the
 * class gains a {@code useWithoutItem} that calls it with the main hand. The game asks
 * {@code useWithoutItem} for the main hand after the held item passes, which is when the old method
 * ran for most blocks. A {@code super.use(...)} that reaches a Minecraft class now calls that
 * class's {@code useWithoutItem}, so a bed or door subclass keeps its parent's behavior.
 *
 * <p>The old method is no longer asked about the off hand. A class that already declares
 * {@code useWithoutItem} keeps its own and only gets the super-call repair. Both intermediary and
 * Mojang names are matched, because Fabric mods stay intermediary on hosts before 26.1.
 */
public final class LegacyBlockUseAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /** One namespace's spelling of the old and new methods. */
    record Names(String oldName, String oldDesc, String newName, String newDesc,
                 String hand, String mainHand, String behaviour) {
        String handDesc() {
            return "L" + hand + ";";
        }
    }

    static final Names MOJANG = names("use", "useWithoutItem",
            "net/minecraft/world/level/block/state/BlockState", "net/minecraft/world/level/Level",
            "net/minecraft/core/BlockPos", "net/minecraft/world/entity/player/Player",
            "net/minecraft/world/InteractionHand", "net/minecraft/world/phys/BlockHitResult",
            "net/minecraft/world/InteractionResult", "MAIN_HAND",
            "net/minecraft/world/level/block/state/BlockBehaviour");

    static final Names INTERMEDIARY = names("method_9534", "method_55766",
            "net/minecraft/class_2680", "net/minecraft/class_1937", "net/minecraft/class_2338",
            "net/minecraft/class_1657", "net/minecraft/class_1268", "net/minecraft/class_3965",
            "net/minecraft/class_1269", "field_5808", "net/minecraft/class_4970");

    private static volatile List<Names> hostNamespaces;

    private LegacyBlockUseAdapter() {}

    private static Names names(String oldName, String newName, String state, String level, String pos,
                               String player, String hand, String hit, String result, String mainHand,
                               String behaviour) {
        String head = "(L" + state + ";L" + level + ";L" + pos + ";L" + player + ";";
        String tail = "L" + hit + ";)L" + result + ";";
        return new Names(oldName, head + "L" + hand + ";" + tail, newName, head + tail,
                hand, mainHand, behaviour);
    }

    /** The namespaces whose host has {@code useWithoutItem}, so old overrides need the repair. */
    static List<Names> hostNamespaces() {
        List<Names> known = hostNamespaces;
        if (known != null) return known;
        Boolean mojang = hostHasNewMethod(MOJANG);
        Boolean intermediary = hostHasNewMethod(INTERMEDIARY);
        List<Names> needed;
        if (mojang == null && intermediary == null) {
            needed = offlineNeedsRepair(RetromodVersion.TARGET_MC_VERSION)
                    ? List.of(MOJANG, INTERMEDIARY) : List.of();
        } else if (Boolean.TRUE.equals(mojang) && Boolean.TRUE.equals(intermediary)) {
            needed = List.of(MOJANG, INTERMEDIARY);
        } else if (Boolean.TRUE.equals(mojang)) {
            needed = List.of(MOJANG);
        } else if (Boolean.TRUE.equals(intermediary)) {
            needed = List.of(INTERMEDIARY);
        } else {
            needed = List.of();
        }
        hostNamespaces = needed;
        return needed;
    }

    /** Null when the host's {@code BlockBehaviour} cannot be read in this namespace. */
    private static Boolean hostHasNewMethod(Names names) {
        ClassNode behaviour = ClassResourceInspector.read(names.behaviour());
        if (behaviour == null) return null;
        return behaviour.methods.stream()
                .anyMatch(m -> m.name.equals(names.newName()) && m.desc.equals(names.newDesc()));
    }

    /** Offline, where the host is not readable, every target from 1.20.5 on has the split. */
    static boolean offlineNeedsRepair(String targetVersion) {
        return targetVersion != null && RetromodVersion.compareMcVersions(targetVersion, "1.20.5") >= 0;
    }

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!text.contains(MOJANG.oldDesc()) && !text.contains(INTERMEDIARY.oldDesc())) return classBytes;
        List<Names> namespaces = hostNamespaces();
        if (namespaces.isEmpty()) return classBytes;
        return apply(classBytes, namespaces, RetromodTransformer::readCurrentJarClassForAdapter);
    }

    /**
     * @param jarClass returns a class from the mod's own jar, or null when it is not there
     */
    static byte[] apply(byte[] classBytes, List<Names> namespaces, Function<String, byte[]> jarClass) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) {
            return classBytes;
        }
        boolean changed = false;
        for (Names names : namespaces) {
            for (MethodNode method : node.methods) {
                changed |= repairSuperCalls(method, names, jarClass);
            }
            MethodNode old = declared(node, names.oldName(), names.oldDesc());
            if (old != null && (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0
                    && declared(node, names.newName(), names.newDesc()) == null) {
                node.methods.add(newMethodCallingOld(node, names));
                changed = true;
                LOGGER.debug("Routed {}.useWithoutItem to its pre-1.20.5 use override", node.name);
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Rewrites {@code super.use(state, level, pos, player, hand, hit)} into
     * {@code super.useWithoutItem(state, level, pos, player, hit)} when no mod class on the way
     * declares the old method. The hand is dropped from under the hit result through a new local.
     */
    private static boolean repairSuperCalls(MethodNode method, Names names, Function<String, byte[]> jarClass) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.name.equals(names.oldName()) || !call.desc.equals(names.oldDesc())
                    || modClassDeclaresOld(call.owner, names, jarClass)) {
                continue;
            }
            int hit = method.maxLocals;
            method.maxLocals += 1;
            InsnList dropHand = new InsnList();
            dropHand.add(new VarInsnNode(Opcodes.ASTORE, hit));
            dropHand.add(new InsnNode(Opcodes.POP));
            dropHand.add(new VarInsnNode(Opcodes.ALOAD, hit));
            method.instructions.insertBefore(call, dropHand);
            call.name = names.newName();
            call.desc = names.newDesc();
            changed = true;
        }
        return changed;
    }

    /** Whether {@code owner} is a mod class, or has one above it, that still declares the old method. */
    private static boolean modClassDeclaresOld(String owner, Names names, Function<String, byte[]> jarClass) {
        String current = owner;
        while (current != null && !current.startsWith("net/minecraft/") && !current.startsWith("java/")) {
            byte[] bytes = jarClass.apply(current);
            // A parent outside this jar is another mod's class, so its own transform decides.
            if (bytes == null) return true;
            ClassNode parent = new ClassNode();
            new ClassReader(bytes).accept(parent, ClassReader.SKIP_CODE);
            if (declared(parent, names.oldName(), names.oldDesc()) != null) return true;
            current = parent.superName;
        }
        return false;
    }

    private static MethodNode declared(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    /** {@code useWithoutItem(state, level, pos, player, hit)}: the old method with the main hand. */
    private static MethodNode newMethodCallingOld(ClassNode node, Names names) {
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, names.newName(), names.newDesc(), null, null);
        InsnList code = bridge.instructions;
        for (int local = 0; local <= 4; local++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, local));
        }
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, names.hand(), names.mainHand(), names.handDesc()));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, names.oldName(), names.oldDesc(), false));
        code.add(new InsnNode(Opcodes.ARETURN));
        return bridge;
    }
}
