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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/** Restores the removed client command parser behind a legacy Mixin invoker. */
final class MixinCommandParserBridge {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String CLIENT_PACKET_LISTENER =
            "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String CLIENT_SUGGESTIONS =
            "net/minecraft/client/multiplayer/ClientSuggestionProvider";
    private static final String COMMAND_DISPATCHER = "com/mojang/brigadier/CommandDispatcher";
    private static final String PARSE_RESULTS = "com/mojang/brigadier/ParseResults";
    private static final String OLD_PARSE_DESC =
            "(Ljava/lang/String;)L" + PARSE_RESULTS + ";";
    private static final String GET_COMMANDS_DESC = "()L" + COMMAND_DISPATCHER + ";";
    private static final String GET_SUGGESTIONS_DESC = "()L" + CLIENT_SUGGESTIONS + ";";
    private static final String DISPATCHER_PARSE_DESC =
            "(Ljava/lang/String;Ljava/lang/Object;)L" + PARSE_RESULTS + ";";

    private MixinCommandParserBridge() {}

    /**
     * Builds the old {@code parseCommand(String)} behavior from exact current host methods.
     * The bridge stays synthetic so Mixin continues to classify this interface as an accessor
     * mixin even after its {@code @Invoker} becomes a concrete default method.
     */
    static boolean apply(ClassNode classNode, FuzzyMethodResolver host) {
        if (host == null || !host.isIndexed()
                || (classNode.access & Opcodes.ACC_INTERFACE) == 0
                || !List.of(CLIENT_PACKET_LISTENER).equals(mixinTargets(classNode))
                || host.hasMethod(CLIENT_PACKET_LISTENER, "parseCommand", OLD_PARSE_DESC)
                || !host.hasClass(CLIENT_SUGGESTIONS)
                || !hasPublicInstanceMethod(host, "getCommands", GET_COMMANDS_DESC)
                || !hasPublicInstanceMethod(host, "getSuggestionsProvider", GET_SUGGESTIONS_DESC)) {
            return false;
        }

        MethodNode invoker = null;
        for (MethodNode method : classNode.methods) {
            if (!OLD_PARSE_DESC.equals(method.desc)
                    || (method.access & Opcodes.ACC_ABSTRACT) == 0
                    || (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                            != Opcodes.ACC_PUBLIC
                    || host.hasMethod(CLIENT_PACKET_LISTENER, method.name, method.desc)) {
                continue;
            }
            AnnotationNode annotation = findAnnotation(method, INVOKER);
            if (annotation == null || !"parseCommand".equals(invokerTarget(method, annotation))) {
                continue;
            }
            if (invoker != null) return false;
            invoker = method;
        }
        if (invoker == null) return false;

        removeAnnotation(invoker, INVOKER);
        invoker.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        invoker.access |= Opcodes.ACC_SYNTHETIC;
        invoker.instructions.clear();
        invoker.tryCatchBlocks.clear();
        if (invoker.localVariables != null) invoker.localVariables.clear();

        invoker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invoker.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, CLIENT_PACKET_LISTENER));
        invoker.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                CLIENT_PACKET_LISTENER, "getCommands", GET_COMMANDS_DESC, false));
        invoker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        invoker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invoker.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, CLIENT_PACKET_LISTENER));
        invoker.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                CLIENT_PACKET_LISTENER, "getSuggestionsProvider", GET_SUGGESTIONS_DESC, false));
        invoker.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                COMMAND_DISPATCHER, "parse", DISPATCHER_PARSE_DESC, false));
        invoker.instructions.add(new InsnNode(Opcodes.ARETURN));
        invoker.maxStack = 3;
        invoker.maxLocals = 2;
        return true;
    }

    private static boolean hasPublicInstanceMethod(
            FuzzyMethodResolver host, String name, String descriptor) {
        for (FuzzyMethodResolver.MethodInfo method
                : host.getDeclaredMethods(CLIENT_PACKET_LISTENER)) {
            if (name.equals(method.name()) && descriptor.equals(method.descriptor())
                    && (method.access() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                            == Opcodes.ACC_PUBLIC) {
                return true;
            }
        }
        return false;
    }

    private static String invokerTarget(MethodNode method, AnnotationNode annotation) {
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("value".equals(annotation.values.get(i))
                        && annotation.values.get(i + 1) instanceof String value
                        && !value.isBlank()) {
                    return value;
                }
            }
        }
        for (String prefix : List.of("invoke", "call")) {
            if (method.name.startsWith(prefix) && method.name.length() > prefix.length()) {
                String suffix = method.name.substring(prefix.length());
                return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
            }
        }
        return null;
    }

    private static List<String> mixinTargets(ClassNode classNode) {
        List<String> targets = new ArrayList<>();
        for (List<AnnotationNode> annotations : List.of(
                classNode.visibleAnnotations != null
                        ? classNode.visibleAnnotations : List.<AnnotationNode>of(),
                classNode.invisibleAnnotations != null
                        ? classNode.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    Object value = annotation.values.get(i + 1);
                    if ("value".equals(annotation.values.get(i)) && value instanceof List<?> list) {
                        for (Object entry : list) {
                            if (entry instanceof Type type) targets.add(type.getInternalName());
                        }
                    } else if ("targets".equals(annotation.values.get(i))
                            && value instanceof List<?> list) {
                        for (Object entry : list) targets.add(String.valueOf(entry).replace('.', '/'));
                    }
                }
            }
        }
        return targets;
    }

    private static AnnotationNode findAnnotation(MethodNode method, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null
                        ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null
                        ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static void removeAnnotation(MethodNode method, String descriptor) {
        if (method.visibleAnnotations != null) {
            method.visibleAnnotations.removeIf(annotation -> descriptor.equals(annotation.desc));
        }
        if (method.invisibleAnnotations != null) {
            method.invisibleAnnotations.removeIf(annotation -> descriptor.equals(annotation.desc));
        }
    }
}
