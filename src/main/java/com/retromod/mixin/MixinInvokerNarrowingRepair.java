/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retypes an accessor-interface {@code @Invoker} whose target method narrowed a parameter type.
 *
 * <p>26.1 narrowed several level parameters from {@code Level} to {@code ServerLevel}, such as
 * {@code FlowingFluid.canConvertToSource}. An invoker still declared with {@code Level} names a
 * method the host no longer has, so the whole accessor fails to apply. Mixin builds an invoker's
 * target from the invoker's own descriptor and reads only the member name from the refmap
 * ({@code InvokerInfo.initTarget} and {@code TargetSelector.parseName}), so retyping the invoker
 * is enough for Mixin to find the method, and the refmap entry can stay as it is.
 *
 * <p>No bridge with the old descriptor is added. Any method on an accessor interface that is
 * not an accessor turns it into a plain interface Mixin, which the mod can no longer load or
 * cast to. A caller still built against the old descriptor fails with
 * {@code NoSuchMethodError} when it runs. Before this repair the same call failed earlier,
 * because the target class never implemented the accessor at all.
 *
 * <p>Only instance invokers on an interface Mixin with one target are retyped, and only when the
 * indexed host proves exactly one same-named method whose parameters equal or narrow the old
 * ones, with the same return type. Anything else is left unchanged.
 */
final class MixinInvokerNarrowingRepair {

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String[] INVOKER_PREFIXES = {"call", "invoke"};

    private MixinInvokerNarrowingRepair() {}

    /** Retypes every provable invoker in {@code node}. Returns whether anything changed. */
    static boolean apply(ClassNode node, FuzzyMethodResolver host) {
        if (host == null || !host.isIndexed() || (node.access & Opcodes.ACC_INTERFACE) == 0) {
            return false;
        }
        String owner = soleTarget(node);
        if (owner == null || !host.hasClass(owner)) return false;

        boolean retyped = false;
        for (MethodNode method : node.methods) {
            AnnotationNode invoker = invoker(method);
            if (invoker == null || (method.access & Opcodes.ACC_STATIC) != 0
                    || (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                continue;
            }
            String target = targetName(method, invoker);
            if (target == null || host.hasMethod(owner, target, method.desc)) continue;
            String narrowed = uniqueNarrowedDescriptor(host, owner, target, method.desc);
            if (narrowed == null || declares(node, method.name, narrowed)) continue;

            method.desc = narrowed;
            // A generic signature still names the old parameter types.
            method.signature = null;
            retyped = true;
        }
        return retyped;
    }

    /**
     * The one host descriptor with the old arity and return type whose parameters each equal
     * or narrow the old ones, with at least one narrowed. Null when there is none or several.
     */
    private static String uniqueNarrowedDescriptor(FuzzyMethodResolver host, String owner,
            String name, String oldDesc) {
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type oldReturn = Type.getReturnType(oldDesc);
        Map<String, FuzzyMethodResolver.MethodInfo> matches = new LinkedHashMap<>();
        for (FuzzyMethodResolver.MethodInfo candidate : host.getMethodsInHierarchy(owner)) {
            if (!name.equals(candidate.name())
                    || (candidate.access() & Opcodes.ACC_STATIC) != 0
                    || !oldReturn.equals(Type.getReturnType(candidate.descriptor()))) {
                continue;
            }
            if (narrows(host, oldArgs, Type.getArgumentTypes(candidate.descriptor()))) {
                matches.putIfAbsent(candidate.descriptor(), candidate);
            }
        }
        return matches.size() == 1 ? matches.keySet().iterator().next() : null;
    }

    private static boolean narrows(FuzzyMethodResolver host, Type[] oldArgs, Type[] newArgs) {
        if (oldArgs.length != newArgs.length) return false;
        boolean narrowedAny = false;
        for (int i = 0; i < oldArgs.length; i++) {
            if (oldArgs[i].equals(newArgs[i])) continue;
            if (oldArgs[i].getSort() != Type.OBJECT || newArgs[i].getSort() != Type.OBJECT
                    || !host.isSubtypeOf(newArgs[i].getInternalName(),
                            oldArgs[i].getInternalName())) {
                return false;
            }
            narrowedAny = true;
        }
        return narrowedAny;
    }

    /** The target member name: the annotation value, or Mixin's inflection of the method name. */
    private static String targetName(MethodNode method, AnnotationNode invoker) {
        String value = annotationString(invoker, "value");
        if (value != null && !value.isEmpty()) {
            // An owner-qualified or descriptor-qualified selector is left to other repairs.
            return value.indexOf('(') >= 0 || value.indexOf(';') >= 0 ? null : value;
        }
        for (String prefix : INVOKER_PREFIXES) {
            String name = method.name;
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                String rest = name.substring(prefix.length());
                boolean allCaps = rest.equals(rest.toUpperCase(java.util.Locale.ROOT));
                return allCaps ? rest : Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
    }

    private static AnnotationNode invoker(MethodNode method) {
        for (List<AnnotationNode> list : annotationLists(method.visibleAnnotations,
                method.invisibleAnnotations)) {
            for (AnnotationNode annotation : list) {
                if (INVOKER_DESC.equals(annotation.desc) && !Boolean.FALSE.equals(
                        annotationValue(annotation, "remap"))) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static String soleTarget(ClassNode node) {
        for (List<AnnotationNode> list : annotationLists(node.visibleAnnotations,
                node.invisibleAnnotations)) {
            for (AnnotationNode annotation : list) {
                if (!MIXIN_DESC.equals(annotation.desc)) continue;
                if (Boolean.FALSE.equals(annotationValue(annotation, "remap"))) return null;
                List<String> targets = new ArrayList<>();
                if (annotationValue(annotation, "value") instanceof List<?> types) {
                    for (Object type : types) {
                        if (type instanceof Type t) targets.add(t.getInternalName());
                    }
                }
                if (annotationValue(annotation, "targets") instanceof List<?> names) {
                    for (Object name : names) {
                        if (name instanceof String s) targets.add(s.replace('.', '/'));
                    }
                }
                return targets.size() == 1 ? targets.get(0) : null;
            }
        }
        return null;
    }

    private static List<List<AnnotationNode>> annotationLists(List<AnnotationNode> visible,
            List<AnnotationNode> invisible) {
        List<List<AnnotationNode>> lists = new ArrayList<>(2);
        if (visible != null) lists.add(visible);
        if (invisible != null) lists.add(invisible);
        return lists;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static String annotationString(AnnotationNode annotation, String key) {
        return annotationValue(annotation, key) instanceof String s ? s : null;
    }
}
