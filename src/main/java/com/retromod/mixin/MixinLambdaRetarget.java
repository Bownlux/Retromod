/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Moves an {@code @Inject} into the one lambda of its target method that now makes the call it injects at. */
final class MixinLambdaRetarget {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String RETURNABLE = CALLBACK + "Returnable";

    private MixinLambdaRetarget() {}

    /**
     * True when the mixin changed. 26.1 made {@code WorldLoader.load} asynchronous and moved its body into
     * {@code lambda$load$0}, so Moonlight's injection before {@code RegistryLayer.createRegistryAccess()}, which builds
     * its generated data pack, found nothing and soft-failed. Only a handler that reads none of the old method's
     * arguments is moved, since the lambda takes different ones; it keeps its callback and its {@code @Local} captures.
     */
    static boolean apply(ClassNode mixin, Function<String, ClassNode> hostWithCode) {
        String target = singleTarget(mixin);
        if (target == null) return false;
        ClassNode host = null;
        boolean changed = false;
        for (MethodNode handler : mixin.methods) {
            AnnotationNode inject = annotation(handler.visibleAnnotations, INJECT);
            if (inject == null) continue;
            String method = singleString(value(inject, "method"));
            MethodInsnNode call = invokeTarget(inject);
            if (method == null || call == null || method.contains("(") || method.contains("*")) continue;
            if (!plainInjection(inject)) continue;
            // Checked before the host is read: the handler takes the target's arguments and never uses them.
            if (!ignoresLeadingArguments(handler)) continue;
            if (host == null) host = hostWithCode.apply(target);
            if (host == null) return changed;
            MethodNode lambda = retarget(host, method, call, handler);
            if (lambda == null) continue;
            moveHandler(handler, inject, host, method, lambda);
            changed = true;
            LOGGER.debug("Moved {}.{} into {}.{}, which now makes the call it injects at", mixin.name, handler.name,
                    target, lambda.name);
        }
        return changed;
    }

    /** The lambda to move to: the original lost the call, exactly one of its lambdas makes it, and the shapes line up. */
    private static MethodNode retarget(ClassNode host, String name, MethodInsnNode call, MethodNode handler) {
        MethodNode original = null;
        for (MethodNode method : host.methods) {
            if (!method.name.equals(name)) continue;
            if (original != null || calls(method, call)) return null;
            original = method;
        }
        if (original == null) return null;
        MethodNode lambda = null;
        for (MethodNode method : host.methods) {
            if (!method.name.startsWith("lambda$" + name + "$") || !calls(method, call)) continue;
            if (lambda != null) return null;
            lambda = method;
        }
        if (lambda == null) return null;
        // The handler's static-ness follows its target, and a void target needs a CallbackInfo either way.
        boolean staticBoth = (original.access & Opcodes.ACC_STATIC) != 0 && (lambda.access & Opcodes.ACC_STATIC) != 0
                && (handler.access & Opcodes.ACC_STATIC) != 0;
        boolean sameVoid = (Type.getReturnType(original.desc).getSort() == Type.VOID)
                == (Type.getReturnType(lambda.desc).getSort() == Type.VOID);
        if (!staticBoth || !sameVoid) return null;
        Type[] old = Type.getArgumentTypes(original.desc);
        Type[] params = Type.getArgumentTypes(handler.desc);
        if (params.length <= old.length) return null;
        for (int i = 0; i < old.length; i++) {
            if (!params[i].equals(old[i])) return null;
        }
        String callback = params[old.length].getInternalName();
        if (!callback.equals(CALLBACK) && !callback.equals(RETURNABLE)) return null;
        return readsArguments(handler, argumentSize(old)) ? null : lambda;
    }

    /** Rewrites the selector, descriptor, locals and parameter annotations for the lambda's arguments. */
    private static void moveHandler(MethodNode handler, AnnotationNode inject, ClassNode host, String name, MethodNode lambda) {
        Type[] old = Type.getArgumentTypes(firstNamed(host, name).desc);
        Type[] params = Type.getArgumentTypes(handler.desc);
        Type[] lambdaArgs = Type.getArgumentTypes(lambda.desc);
        int boundary = argumentSize(old);
        int shift = argumentSize(lambdaArgs) - boundary;
        List<Type> moved = new ArrayList<>(List.of(lambdaArgs));
        for (int i = old.length; i < params.length; i++) moved.add(params[i]);
        handler.desc = Type.getMethodDescriptor(Type.getReturnType(handler.desc), moved.toArray(new Type[0]));
        // The generic signature still lists the old arguments, and MixinExtras reads sugar types from it.
        handler.signature = null;
        // Every slot from the callback on moves by the change in argument size.
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof VarInsnNode var && var.var >= boundary) var.var += shift;
            if (insn instanceof IincInsnNode inc && inc.var >= boundary) inc.var += shift;
        }
        if (handler.localVariables != null) {
            handler.localVariables.removeIf(local -> local.index < boundary);
            for (LocalVariableNode local : handler.localVariables) local.index += shift;
        }
        handler.maxLocals += shift;
        handler.visibleParameterAnnotations = shiftAnnotations(handler.visibleParameterAnnotations, old.length, lambdaArgs.length);
        handler.invisibleParameterAnnotations = shiftAnnotations(handler.invisibleParameterAnnotations, old.length, lambdaArgs.length);
        handler.visibleAnnotableParameterCount = 0;
        handler.invisibleAnnotableParameterCount = 0;
        handler.parameters = null;
        setValue(inject, "method", List.of(lambda.name));
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[] shiftAnnotations(List<AnnotationNode>[] annotations, int oldCount, int newCount) {
        if (annotations == null) return null;
        List<AnnotationNode>[] shifted = new List[annotations.length - oldCount + newCount];
        for (int i = oldCount; i < annotations.length; i++) shifted[i - oldCount + newCount] = annotations[i];
        return shifted;
    }

    /**
     * Whether the injection keeps its meaning in a lambda: it cannot cancel, since cancelling the lambda would not
     * cancel the method, and it picks its call with no slice or later ordinal, which counted calls in the old method.
     */
    private static boolean plainInjection(AnnotationNode inject) {
        if (Boolean.TRUE.equals(value(inject, "cancellable")) || value(inject, "slice") != null) return false;
        Object at = value(inject, "at");
        if (at instanceof List<?> list && list.size() == 1) at = list.get(0);
        return !(at instanceof AnnotationNode node && value(node, "ordinal") instanceof Integer ordinal && ordinal > 0);
    }

    /** Whether the handler takes arguments before its callback and reads none of them. */
    private static boolean ignoresLeadingArguments(MethodNode handler) {
        if ((handler.access & Opcodes.ACC_STATIC) == 0) return false;
        Type[] params = Type.getArgumentTypes(handler.desc);
        int size = 0;
        for (Type param : params) {
            if (param.getSort() == Type.OBJECT && (param.getInternalName().equals(CALLBACK)
                    || param.getInternalName().equals(RETURNABLE))) {
                return size > 0 && !readsArguments(handler, size);
            }
            size += param.getSize();
        }
        return false;
    }

    /** Whether the handler reads or writes any slot that held the old method's arguments. */
    private static boolean readsArguments(MethodNode handler, int size) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof VarInsnNode var && var.var < size) return true;
            if (insn instanceof IincInsnNode inc && inc.var < size) return true;
        }
        return false;
    }

    private static boolean calls(MethodNode method, MethodInsnNode call) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode made && made.owner.equals(call.owner) && made.name.equals(call.name)
                    && made.desc.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode firstNamed(ClassNode host, String name) {
        return host.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static int argumentSize(Type[] types) {
        int size = 0;
        for (Type type : types) size += type.getSize();
        return size;
    }

    /** The {@code INVOKE} target of the injector's one {@code @At}, as owner, name and descriptor. */
    private static MethodInsnNode invokeTarget(AnnotationNode inject) {
        Object at = value(inject, "at");
        if (at instanceof List<?> list && list.size() == 1) at = list.get(0);
        if (!(at instanceof AnnotationNode node) || !"INVOKE".equals(value(node, "value"))) return null;
        if (!(value(node, "target") instanceof String target) || !target.startsWith("L")) return null;
        int ownerEnd = target.indexOf(';');
        int descStart = target.indexOf('(');
        if (ownerEnd < 0 || descStart < ownerEnd) return null;
        return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, target.substring(1, ownerEnd),
                target.substring(ownerEnd + 1, descStart), target.substring(descStart));
    }

    private static String singleTarget(ClassNode mixin) {
        AnnotationNode annotation = annotation(mixin.invisibleAnnotations, MIXIN);
        if (annotation == null) annotation = annotation(mixin.visibleAnnotations, MIXIN);
        if (annotation == null) return null;
        List<String> targets = new ArrayList<>();
        if (value(annotation, "value") instanceof List<?> types) {
            for (Object type : types) if (type instanceof Type t) targets.add(t.getInternalName());
        }
        if (value(annotation, "targets") instanceof List<?> names) {
            for (Object name : names) if (name instanceof String text) targets.add(text.replace('.', '/'));
        }
        return targets.size() == 1 ? targets.get(0) : null;
    }

    private static String singleString(Object value) {
        if (value instanceof String text) return text;
        return value instanceof List<?> list && list.size() == 1 && list.get(0) instanceof String text ? text : null;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return null;
        for (AnnotationNode node : annotations) if (desc.equals(node.desc)) return node;
        return null;
    }

    private static Object value(AnnotationNode node, String key) {
        if (node.values == null) return null;
        for (int i = 0; i + 1 < node.values.size(); i += 2) {
            if (key.equals(node.values.get(i))) return node.values.get(i + 1);
        }
        return null;
    }

    private static void setValue(AnnotationNode node, String key, Object value) {
        for (int i = 0; i + 1 < node.values.size(); i += 2) {
            if (key.equals(node.values.get(i))) {
                node.values.set(i + 1, value);
                return;
            }
        }
    }
}
