/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Moves the argument indices of a @ModifyArg or @ModifyArgs handler when the call it modifies gained parameters. */
final class MixinArgumentIndexRepair {

    static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    static final String MODIFY_ARGS = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
    private static final String ARGS = "org/spongepowered/asm/mixin/injection/invoke/arg/Args";
    private static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/";

    /**
     * The index the annotation takes, the constants a @ModifyArgs body passes to Args, and the slots of
     * {@code @Local(argsOnly = true, index = n)} sugar, each with its new value.
     */
    record Plan(AnnotationNode injector, Integer newIndex, Map<AbstractInsnNode, Integer> constants,
            Map<AnnotationNode, Integer> locals) {
        void apply(MethodNode handler) {
            if (newIndex != null) setIndex(injector, newIndex);
            constants.forEach((insn, value) -> handler.instructions.set(insn, constant(value)));
            locals.forEach(MixinArgumentIndexRepair::setIndex);
        }

        /** The same plan with the handler's argument sugar moved for the method it injects into. */
        Plan withLocals(Map<AnnotationNode, Integer> moved) {
            return new Plan(injector, newIndex, constants, moved);
        }
    }

    /**
     * Where each {@code @Local(argsOnly = true)} sugar reads once the method the handler injects into went from
     * {@code oldDesc} to {@code newDesc}: an explicit slot moves with its argument. Null when it cannot be proven,
     * such as an ordinal the new parameters could take, or a slot that is not an old argument.
     */
    static Map<AnnotationNode, Integer> movedLocals(MethodNode handler, String oldDesc, String newDesc, boolean isStatic) {
        int[] positions = uniquePositions(oldDesc, newDesc);
        if (positions == null) return null;
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        Map<AnnotationNode, Integer> moved = new LinkedHashMap<>();
        for (int parameter = 1; parameter < handlerArgs.length; parameter++) {
            AnnotationNode local = argsOnlyLocal(handler, parameter);
            if (local == null) continue;
            Integer slot = intValue(local, "index");
            if (slot == null) {
                // A local found by type and ordinal keeps its meaning unless a new parameter has its type.
                for (int i = 0; i < newArgs.length; i++) {
                    if (newArgs[i].equals(handlerArgs[parameter]) && !contains(positions, i)) return null;
                }
                continue;
            }
            int oldArg = argumentAtSlot(oldArgs, slot, isStatic);
            if (oldArg < 0 || !oldArgs[oldArg].equals(handlerArgs[parameter])) return null;
            moved.put(local, slotOf(newArgs, positions[oldArg], isStatic));
        }
        return moved;
    }

    private MixinArgumentIndexRepair() {}

    static boolean handles(String injectorDesc) {
        return MODIFY_ARG.equals(injectorDesc) || MODIFY_ARGS.equals(injectorDesc);
    }

    /** The repair for a call target that went from {@code oldDesc} to {@code newDesc}, or null when it is not proven. */
    static Plan plan(MethodNode handler, AnnotationNode injector, String oldDesc, String newDesc) {
        int[] positions = uniquePositions(oldDesc, newDesc);
        if (positions == null) return null;
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        // The handler takes its one argument, or Args, followed only by MixinExtras sugar.
        if (handlerArgs.length == 0 || !onlySugarAfterFirst(handler, handlerArgs.length)) return null;
        return MODIFY_ARG.equals(injector.desc)
                ? modifyArg(handler, injector, Type.getArgumentTypes(oldDesc), positions)
                : modifyArgs(handler, injector, handlerArgs, positions);
    }

    private static Plan modifyArg(MethodNode handler, AnnotationNode injector, Type[] oldArgs, int[] positions) {
        Type modified = Type.getReturnType(handler.desc);
        if (!Type.getArgumentTypes(handler.desc)[0].equals(modified)) return null;
        Integer index = index(injector);
        if (index == null) {
            // Without an index Mixin picks the one argument of the handler's type; the new parameters may add another.
            index = -1;
            for (int i = 0; i < oldArgs.length; i++) {
                if (!oldArgs[i].equals(modified)) continue;
                if (index >= 0) return null;
                index = i;
            }
            if (index < 0) return null;
        }
        if (index < 0 || index >= oldArgs.length || !oldArgs[index].equals(modified)) return null;
        return new Plan(injector, positions[index], Map.of(), Map.of());
    }

    private static Plan modifyArgs(MethodNode handler, AnnotationNode injector, Type[] handlerArgs, int[] positions) {
        if (!handlerArgs[0].getInternalName().equals(ARGS) || (handler.access & Opcodes.ACC_ABSTRACT) != 0) return null;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze("mixin", handler);
        } catch (Throwable unanalyzable) {
            return null;
        }
        Map<AbstractInsnNode, Integer> constants = new LinkedHashMap<>();
        for (AbstractInsnNode insn : handler.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            // Args passed on to other code could be read at any index, so that handler is left alone.
            if (!call.owner.equals(ARGS) && call.desc.contains("L" + ARGS + ";")) return null;
            if (!call.owner.equals(ARGS) || call.name.equals("size")) continue;
            if (!call.name.equals("get") && !call.name.equals("set")) return null;
            Frame<SourceValue> frame = frames[handler.instructions.indexOf(call)];
            if (frame == null) continue;
            int indexSlot = frame.getStackSize() - Type.getArgumentTypes(call.desc).length;
            SourceValue value = frame.getStack(indexSlot);
            if (value.insns.size() != 1) return null;
            AbstractInsnNode source = value.insns.iterator().next();
            Integer old = constantValue(source);
            if (old == null || old < 0 || old >= positions.length) return null;
            Integer seen = constants.put(source, positions[old]);
            if (seen != null && !seen.equals(positions[old])) return null;
        }
        return new Plan(injector, null, constants, Map.of());
    }

    /** Where each old argument sits among the new ones, when the old ones appear in order in exactly one way. */
    static int[] uniquePositions(String oldDesc, String newDesc) {
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        if (newArgs.length <= oldArgs.length || !Type.getReturnType(oldDesc).equals(Type.getReturnType(newDesc))) return null;
        List<int[]> found = new ArrayList<>(2);
        align(oldArgs, newArgs, 0, 0, new int[oldArgs.length], found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static void align(Type[] oldArgs, Type[] newArgs, int oldAt, int newAt, int[] positions, List<int[]> found) {
        if (found.size() > 1) return;
        if (oldAt == oldArgs.length) {
            found.add(positions.clone());
            return;
        }
        for (int i = newAt; i <= newArgs.length - (oldArgs.length - oldAt); i++) {
            if (!newArgs[i].equals(oldArgs[oldAt])) continue;
            positions[oldAt] = i;
            align(oldArgs, newArgs, oldAt + 1, i + 1, positions, found);
        }
    }

    private static boolean onlySugarAfterFirst(MethodNode handler, int count) {
        for (int i = 1; i < count; i++) {
            if (!hasSugar(handler.invisibleParameterAnnotations, i) && !hasSugar(handler.visibleParameterAnnotations, i)) return false;
        }
        return true;
    }

    private static boolean hasSugar(List<AnnotationNode>[] annotations, int parameter) {
        if (annotations == null || parameter >= annotations.length || annotations[parameter] == null) return false;
        return annotations[parameter].stream().anyMatch(a -> a.desc != null && a.desc.startsWith(SUGAR));
    }

    private static Integer index(AnnotationNode injector) {
        return intValue(injector, "index");
    }

    private static Integer intValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof Integer value) return value;
        }
        return null;
    }

    /** The parameter's {@code @Local} sugar when it reads the target's arguments only. */
    private static AnnotationNode argsOnlyLocal(MethodNode handler, int parameter) {
        AnnotationNode found = argsOnlyLocal(handler.invisibleParameterAnnotations, parameter);
        return found != null ? found : argsOnlyLocal(handler.visibleParameterAnnotations, parameter);
    }

    private static AnnotationNode argsOnlyLocal(List<AnnotationNode>[] annotations, int parameter) {
        if (annotations == null || parameter >= annotations.length || annotations[parameter] == null) return null;
        for (AnnotationNode annotation : annotations[parameter]) {
            if (!(SUGAR + "Local;").equals(annotation.desc) || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("argsOnly".equals(annotation.values.get(i)) && Boolean.TRUE.equals(annotation.values.get(i + 1))) return annotation;
            }
        }
        return null;
    }

    private static boolean contains(int[] positions, int value) {
        for (int position : positions) if (position == value) return true;
        return false;
    }

    /** The argument a local slot holds, or -1 when the slot is not the start of an argument. */
    private static int argumentAtSlot(Type[] args, int slot, boolean isStatic) {
        int at = isStatic ? 0 : 1;
        for (int i = 0; i < args.length; i++) {
            if (at == slot) return i;
            at += args[i].getSize();
        }
        return -1;
    }

    private static int slotOf(Type[] args, int argument, boolean isStatic) {
        int at = isStatic ? 0 : 1;
        for (int i = 0; i < argument; i++) at += args[i].getSize();
        return at;
    }

    private static void setIndex(AnnotationNode injector, Integer value) {
        if (injector.values == null) injector.values = new ArrayList<>();
        for (int i = 0; i + 1 < injector.values.size(); i += 2) {
            if ("index".equals(injector.values.get(i))) {
                injector.values.set(i + 1, value);
                return;
            }
        }
        injector.values.add("index");
        injector.values.add(value);
    }

    private static Integer constantValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (insn instanceof IntInsnNode push && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) return push.operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    private static AbstractInsnNode constant(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
        return value <= Byte.MAX_VALUE ? new IntInsnNode(Opcodes.BIPUSH, value) : new IntInsnNode(Opcodes.SIPUSH, value);
    }
}
