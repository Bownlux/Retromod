/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.shim.fabric.embedded.LegacyBlockRandomTickBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps modern block-state caches in sync with a verified legacy random-tick accessor. */
public final class LegacyBlockRandomTickCallAdapter {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String SETTER_DESC = "(Z)V";
    private static final String REFRESH_DESC = "(Ljava/lang/Object;)V";
    private static final Pattern SETTER_NAME = Pattern.compile(
            "^set(([A-Z])(.*?))(_\\$md.*)?$");
    private static final Set<String> BLOCK_TARGETS = Set.of(
            "net/minecraft/class_2248",
            "net/minecraft/world/level/block/Block",
            "net/minecraft/world/level/block/state/BlockBehaviour");
    private static final Set<String> RANDOM_TICK_FIELDS = Set.of(
            "randomTicks", "field_10641", "isRandomlyTicking");

    private LegacyBlockRandomTickCallAdapter() {}

    /**
     * Adds a state-cache refresh after calls through an accessor that is proven by its own
     * bytecode to target the legacy block random-tick field.
     */
    public static byte[] apply(byte[] classBytes, Function<String, byte[]> classLookup) {
        if (classBytes == null || classLookup == null) return classBytes;

        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !SETTER_DESC.equals(call.desc)
                        || alreadyRefreshes(call)) {
                    continue;
                }
                byte[] ownerBytes = classLookup.apply(call.owner);
                if (!isRandomTickAccessor(ownerBytes, call.name, call.desc)) continue;

                method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP2));
                InsnList refresh = new InsnList();
                refresh.add(new InsnNode(Opcodes.POP));
                refresh.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        LegacyBlockRandomTickBridge.INTERNAL_NAME,
                        "refreshStates",
                        REFRESH_DESC,
                        false));
                method.instructions.insert(call, refresh);
                changed = true;
            }
        }
        if (!changed) return classBytes;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static boolean isRandomTickAccessor(byte[] classBytes, String methodName, String descriptor) {
        if (classBytes == null || !SETTER_DESC.equals(descriptor)) return false;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(classBytes).accept(
                    node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if ((node.access & Opcodes.ACC_INTERFACE) == 0 || !targetsBlock(node)) return false;

            for (MethodNode method : node.methods) {
                if (!method.name.equals(methodName) || !method.desc.equals(descriptor)) continue;
                AnnotationNode accessor = annotation(method, ACCESSOR);
                if (accessor == null) return false;
                String field = stringValue(accessor, "value");
                if (field == null || field.isEmpty()) field = inferredField(method.name);
                return RANDOM_TICK_FIELDS.contains(field);
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean targetsBlock(ClassNode node) {
        AnnotationNode mixin = annotation(node, MIXIN);
        if (mixin == null || mixin.values == null) return false;
        java.util.ArrayList<String> declaredTargets = new java.util.ArrayList<>();
        for (int i = 0; i < mixin.values.size(); i += 2) {
            Object key = mixin.values.get(i);
            if (!(key instanceof String name)
                    || !(name.equals("value") || name.equals("targets"))) {
                continue;
            }
            Object rawTargets = mixin.values.get(i + 1);
            if (!(rawTargets instanceof List<?> targets)) return false;
            for (Object target : targets) {
                String internalName;
                if (name.equals("value") && target instanceof Type type
                        && type.getSort() == Type.OBJECT) {
                    internalName = type.getInternalName();
                } else if (name.equals("targets") && target instanceof String text) {
                    internalName = text.replace('.', '/');
                } else {
                    return false;
                }
                declaredTargets.add(internalName);
            }
        }
        return declaredTargets.size() == 1 && BLOCK_TARGETS.contains(declaredTargets.get(0));
    }

    private static String inferredField(String methodName) {
        Matcher matcher = SETTER_NAME.matcher(methodName);
        if (!matcher.matches()) return null;
        String namePart = matcher.group(1);
        String firstCharacter = matcher.group(2);
        String remainder = matcher.group(3);
        boolean allUpperCase = namePart.toUpperCase(Locale.ROOT).equals(namePart);
        return (allUpperCase ? firstCharacter : firstCharacter.toLowerCase(Locale.ROOT))
                + remainder;
    }

    private static AnnotationNode annotation(ClassNode node, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                node.visibleAnnotations != null ? node.visibleAnnotations : List.<AnnotationNode>of(),
                node.invisibleAnnotations != null ? node.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static String stringValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))
                    && annotation.values.get(i + 1) instanceof String value) {
                return value;
            }
        }
        return null;
    }

    private static boolean alreadyRefreshes(MethodInsnNode call) {
        AbstractInsnNode previous = meaningfulPrevious(call);
        AbstractInsnNode first = meaningfulNext(call);
        AbstractInsnNode second = meaningfulNext(first);
        return previous != null && previous.getOpcode() == Opcodes.DUP2
                && first != null && first.getOpcode() == Opcodes.POP
                && second instanceof MethodInsnNode refresh
                && refresh.getOpcode() == Opcodes.INVOKESTATIC
                && (LegacyBlockRandomTickBridge.INTERNAL_NAME.equals(refresh.owner)
                    || refresh.owner.endsWith("/" + LegacyBlockRandomTickBridge.INTERNAL_NAME))
                && "refreshStates".equals(refresh.name)
                && REFRESH_DESC.equals(refresh.desc);
    }

    private static AbstractInsnNode meaningfulPrevious(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode meaningfulNext(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }
}
