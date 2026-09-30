/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps pre-1.21.5 item tooltips working.
 *
 * <p>1.21.5 changed {@code Item.appendHoverText} from a {@code List<Component>} sink to a
 * {@code TooltipDisplay} and a {@code Consumer<Component>}. An old override still loads, but
 * Minecraft never calls it, so every extra tooltip line a mod added disappears, and its super call
 * to the old descriptor throws {@code NoSuchMethodError}. This adds an override with the new
 * descriptor that collects the old method's lines and forwards them, and retargets super calls to
 * the new descriptor with {@code TooltipDisplay.DEFAULT}.
 *
 * <p>Runs only on a host where a 1.21.4 to 1.21.5 shim registered {@link LegacyTooltipSynthetic}.
 * Overrides are matched by their exact old descriptor, which a mod built for 1.21.5 or newer does
 * not have. A super call is retargeted only when the jar proves it lands on Minecraft's class; one
 * that passes through a class outside the jar leaves that override unbridged.
 */
public final class LegacyTooltipAdapter {

    private LegacyTooltipAdapter() {}

    private static final String ITEM_STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String CONTEXT = "Lnet/minecraft/world/item/Item$TooltipContext;";
    private static final String FLAG = "Lnet/minecraft/world/item/TooltipFlag;";
    private static final String DISPLAY_CLASS = "net/minecraft/world/item/component/TooltipDisplay";

    static final String APPEND = "appendHoverText";
    static final String OLD_DESC =
            "(" + ITEM_STACK + CONTEXT + "Ljava/util/List;" + FLAG + ")V";
    static final String NEW_DESC =
            "(" + ITEM_STACK + CONTEXT + "L" + DISPLAY_CLASS + ";Ljava/util/function/Consumer;"
                    + FLAG + ")V";

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

        java.util.function.Function<String, byte[]> jarClasses =
                com.retromod.core.RetromodTransformer::readCurrentJarClassForAdapter;
        int changes = 0;
        List<MethodNode> bridges = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            boolean overridable = (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
            boolean oldOverride = overridable && APPEND.equals(m.name) && OLD_DESC.equals(m.desc);
            // A bridge makes the old override run. If one of its super calls could still reach
            // Item under the old descriptor, running it would crash on hover, which is worse than
            // the missing lines, so such a class keeps its 1.3.1 behaviour.
            if (oldOverride && !superCallsAreSafe(m, jarClasses)) continue;
            changes += retargetSuperCalls(m, jarClasses);
            if (oldOverride && !declared.contains(APPEND + NEW_DESC)) {
                bridges.add(bridge(cn, m.access));
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

    /**
     * {@code [this, stack, context, list, flag]} to
     * {@code [this, stack, context, TooltipDisplay.DEFAULT, sink(list), flag]}.
     */
    private static int retargetSuperCalls(MethodNode m,
            java.util.function.Function<String, byte[]> jarClasses) {
        if (m.instructions == null || m.instructions.size() == 0) return 0;
        int changes = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !APPEND.equals(call.name) || !OLD_DESC.equals(call.desc)
                    || resolveSuperCall(call.owner, jarClasses) != SuperTarget.MINECRAFT) {
                continue;
            }
            int flag = m.maxLocals;
            int list = flag + 1;
            m.maxLocals += 2;
            InsnList args = new InsnList();
            args.add(new VarInsnNode(Opcodes.ASTORE, flag));
            args.add(new VarInsnNode(Opcodes.ASTORE, list));
            args.add(new FieldInsnNode(Opcodes.GETSTATIC, DISPLAY_CLASS, "DEFAULT",
                    "L" + DISPLAY_CLASS + ";"));
            args.add(new VarInsnNode(Opcodes.ALOAD, list));
            args.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyTooltipSynthetic.INTERNAL,
                    "sink", LegacyTooltipSynthetic.SINK_DESC, false));
            args.add(new VarInsnNode(Opcodes.ALOAD, flag));
            m.instructions.insertBefore(call, args);
            call.desc = NEW_DESC;
            changes++;
        }
        return changes;
    }

    /** Where an old-descriptor super call would land. */
    enum SuperTarget {
        /** Minecraft's own class, which only has the new descriptor: retarget the call. */
        MINECRAFT,
        /** A mod class that declares the old method itself: the call is fine as written. */
        MOD_OVERRIDE,
        /** A class outside this jar: cannot tell. */
        UNKNOWN
    }

    /**
     * Follows {@code owner}'s superclass chain through the jar being transformed. {@code javac}
     * names the direct superclass as the owner, so a mod's own base class sits between the call
     * and Minecraft whenever the mod has one.
     */
    static SuperTarget resolveSuperCall(String owner,
            java.util.function.Function<String, byte[]> jarClasses) {
        Set<String> seen = new HashSet<>();
        String current = owner;
        while (current != null && seen.add(current)) {
            if (current.startsWith("net/minecraft/")) return SuperTarget.MINECRAFT;
            byte[] bytes = jarClasses == null ? null : jarClasses.apply(current);
            if (bytes == null) return SuperTarget.UNKNOWN;
            ClassNode node = new ClassNode();
            try {
                new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            } catch (Throwable unreadable) {
                return SuperTarget.UNKNOWN;
            }
            for (MethodNode method : node.methods) {
                if (APPEND.equals(method.name) && OLD_DESC.equals(method.desc)) {
                    return SuperTarget.MOD_OVERRIDE;
                }
            }
            current = node.superName;
        }
        return SuperTarget.UNKNOWN;
    }

    private static boolean superCallsAreSafe(MethodNode m,
            java.util.function.Function<String, byte[]> jarClasses) {
        if (m.instructions == null) return true;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && APPEND.equals(call.name) && OLD_DESC.equals(call.desc)
                    && resolveSuperCall(call.owner, jarClasses) == SuperTarget.UNKNOWN) {
                return false;
            }
        }
        return true;
    }

    /** The 1.21.5 override: collect the old method's lines, then hand each one to the consumer. */
    private static MethodNode bridge(ClassNode cn, int oldAccess) {
        int visibility = (oldAccess & Opcodes.ACC_PUBLIC) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
        MethodNode bridge = new MethodNode(visibility | Opcodes.ACC_SYNTHETIC, APPEND, NEW_DESC,
                null, null);
        MethodVisitor mv = bridge;
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1); // stack
        mv.visitVarInsn(Opcodes.ALOAD, 2); // context
        mv.visitVarInsn(Opcodes.ALOAD, 6); // lines
        mv.visitVarInsn(Opcodes.ALOAD, 5); // flag
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, APPEND, OLD_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 4); // consumer
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "forEach",
                "(Ljava/util/function/Consumer;)V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return bridge;
    }
}
