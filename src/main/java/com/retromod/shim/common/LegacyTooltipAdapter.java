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

/** Keeps old item tooltip overrides working on newer Minecraft. */
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

    // Before 1.20.5 the override took the Level where 1.20.5 passes a TooltipContext.
    private static final String CONTEXT_CLASS = "net/minecraft/world/item/Item$TooltipContext";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    static final String LEVEL_DESC = "(" + ITEM_STACK + LEVEL + "Ljava/util/List;" + FLAG + ")V";

    /** Enables {@link #applyLevelShape}; set on hosts from 1.20.5, whose tooltip takes a context. */
    public static final String LEVEL_FLAG = "legacy-level-tooltips";

    /** Set when the host's context can return its level, which NeoForge adds and vanilla lacks. */
    public static final String CONTEXT_LEVEL_FLAG = "legacy-level-tooltips-context-level";

    /** Makes Minecraft call a class's 1.21.4 tooltip override again. */
    public static byte[] apply(byte[] classBytes) {
        // Runs only on a host where a 1.21.4 to 1.21.5 shim registered LegacyTooltipSynthetic.
        // 1.21.5 changed appendHoverText from a List<Component> sink to a TooltipDisplay and a
        // Consumer<Component>. An old override still loads, but Minecraft never calls it, so its
        // lines disappear, and its super call to the old descriptor throws NoSuchMethodError.
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
            // Only the exact old descriptor matches, which a mod built for 1.21.5 or newer lacks.
            boolean oldOverride = overridable && APPEND.equals(m.name) && OLD_DESC.equals(m.desc);
            // A bridge makes the old override run. If one of its super calls could still reach
            // Item under the old descriptor, running it would crash on hover, which is worse than
            // the missing lines, so such a class keeps its 1.3.1 behavior.
            if (oldOverride && !superCallsAreSafe(m, OLD_DESC, jarClasses)) continue;
            // Super calls move to the new descriptor with TooltipDisplay.DEFAULT, and a new
            // override collects the old method's lines and forwards them.
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

    /** Makes Minecraft call a class's pre-1.20.5 tooltip override, which took a {@code Level}, again. */
    public static byte[] applyLevelShape(byte[] classBytes, boolean contextHasLevel) {
        // This gives the override the 1.20.5 shape. On a 1.21.5 or newer host, apply then
        // carries it the rest of the way.
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
            boolean levelOverride = overridable && APPEND.equals(m.name) && LEVEL_DESC.equals(m.desc);
            // Same rule as apply: a super call that might land outside the jar leaves it alone.
            if (levelOverride && !superCallsAreSafe(m, LEVEL_DESC, jarClasses)) continue;
            // Super calls build a context from their level, and a new 1.20.5 override hands the
            // old method the context's level.
            changes += retargetLevelSuperCalls(m, jarClasses);
            if (levelOverride && !declared.contains(APPEND + OLD_DESC)) {
                bridges.add(contextBridge(cn, m.access, contextHasLevel));
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

    /** Makes each super call to the Level method pass {@code TooltipContext.of(level)} instead. */
    private static int retargetLevelSuperCalls(MethodNode m,
            java.util.function.Function<String, byte[]> jarClasses) {
        if (m.instructions == null || m.instructions.size() == 0) return 0;
        int changes = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !APPEND.equals(call.name) || !LEVEL_DESC.equals(call.desc)
                    || resolveSuperCall(call.owner, LEVEL_DESC, jarClasses) != SuperTarget.MINECRAFT) {
                continue;
            }
            // [this, stack, level, list, flag] becomes [this, stack, of(level), list, flag]: park
            // list and flag in new locals, wrap the level, then load them back.
            int flag = m.maxLocals;
            int list = flag + 1;
            m.maxLocals += 2;
            InsnList args = new InsnList();
            args.add(new VarInsnNode(Opcodes.ASTORE, flag));
            args.add(new VarInsnNode(Opcodes.ASTORE, list));
            args.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "of",
                    "(" + LEVEL + ")" + CONTEXT, true));
            args.add(new VarInsnNode(Opcodes.ALOAD, list));
            args.add(new VarInsnNode(Opcodes.ALOAD, flag));
            m.instructions.insertBefore(call, args);
            call.desc = OLD_DESC;
            changes++;
        }
        return changes;
    }

    /** The 1.20.5 override: call the old method with the context's level, or null without one. */
    private static MethodNode contextBridge(ClassNode cn, int oldAccess, boolean contextHasLevel) {
        int visibility = (oldAccess & Opcodes.ACC_PUBLIC) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
        MethodNode bridge = new MethodNode(visibility | Opcodes.ACC_SYNTHETIC, APPEND, OLD_DESC,
                null, null);
        MethodVisitor mv = bridge;
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1); // stack
        // NeoForge's context returns its level. Vanilla's does not, so on Fabric and Forge the old
        // method gets null, which it already had to accept: Minecraft passed no level when it
        // built a tooltip without a player.
        if (contextHasLevel) {
            mv.visitVarInsn(Opcodes.ALOAD, 2); // context
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONTEXT_CLASS, "level", "()" + LEVEL, true);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 3); // lines
        mv.visitVarInsn(Opcodes.ALOAD, 4); // flag
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cn.name, APPEND, LEVEL_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return bridge;
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
                    || resolveSuperCall(call.owner, OLD_DESC, jarClasses) != SuperTarget.MINECRAFT) {
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

    /** Follows {@code owner}'s superclass chain through the jar to find where a super call lands. */
    static SuperTarget resolveSuperCall(String owner, String desc,
            java.util.function.Function<String, byte[]> jarClasses) {
        // javac names the direct superclass as the owner, so a mod's own base class sits between
        // the call and Minecraft whenever the mod has one.
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
                if (APPEND.equals(method.name) && desc.equals(method.desc)) {
                    return SuperTarget.MOD_OVERRIDE;
                }
            }
            current = node.superName;
        }
        return SuperTarget.UNKNOWN;
    }

    /** Whether every super call to {@code desc} in {@code m} lands somewhere the jar can prove. */
    private static boolean superCallsAreSafe(MethodNode m, String desc,
            java.util.function.Function<String, byte[]> jarClasses) {
        if (m.instructions == null) return true;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && APPEND.equals(call.name) && desc.equals(call.desc)
                    && resolveSuperCall(call.owner, desc, jarClasses) == SuperTarget.UNKNOWN) {
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
