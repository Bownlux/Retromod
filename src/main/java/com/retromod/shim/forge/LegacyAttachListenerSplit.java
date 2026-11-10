/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gives an old {@code AttachCapabilitiesEvent<T>} listener one listener per holder kind Forge 26.2 fires for T. */
public final class LegacyAttachListenerSplit {

    public static final String FLAG = "forge-attach-listener-split";
    static final String ATTACH = "net/minecraftforge/event/AttachCapabilitiesEvent";
    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String NEW_SUBSCRIBE = ForgeSubscribeAnnotations.SUBSCRIBE7;

    /** Each holder class Forge 1.20.1 attached to, by its Mojang name, and the event Forge 26.2 fires for it. */
    static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("net/minecraft/world/entity/Entity", "Entities");
        KINDS.put("net/minecraft/world/item/ItemStack", "ItemStacks");
        KINDS.put("net/minecraft/world/level/block/entity/BlockEntity", "BlockEntities");
        KINDS.put("net/minecraft/world/level/Level", "Levels");
        KINDS.put("net/minecraft/world/level/chunk/LevelChunk", "LevelChunks");
    }

    private LegacyAttachListenerSplit() {}

    /** The class with each subscribed attach listener answered by one listener per matching kind, or the same array. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || new String(classBytes, StandardCharsets.ISO_8859_1).indexOf(ATTACH) < 0) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        boolean changed = false;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!takesAttachEvent(method)) continue;
            AnnotationNode subscribe = takeSubscribe(method);
            if (subscribe == null) continue;
            // The original keeps its body and its base-typed parameter, so getObject and addCapability still link.
            changed = true;
            for (String kind : kindsFor(method.signature)) {
                added.add(listener(node.name, isInterface, method, subscribe, kind));
            }
        }
        if (!changed) return classBytes;
        node.methods.addAll(added);
        // The new methods only pass their argument on, so the original frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean takesAttachEvent(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        return arguments.length == 1 && Type.getReturnType(method.desc).getSort() == Type.VOID
                && arguments[0].getSort() == Type.OBJECT && arguments[0].getInternalName().equals(ATTACH);
    }

    /** The kinds a listener heard on Forge 1.20.1: its holder's, all five for raw or {@code ?}, none for another type. */
    static List<String> kindsFor(String signature) {
        // EventBus 6 compared the event's holder class with the listener's type argument by identity.
        String argument = typeArgument(signature);
        if (argument == null) return List.copyOf(KINDS.values());
        String kind = KINDS.get(argument);
        return kind == null ? List.of() : List.of(kind);
    }

    /** The first parameter's type argument: a class name, "" for one that is no class, or null for raw or {@code ?}. */
    static String typeArgument(String signature) {
        if (signature == null) return null;
        String prefix = "(L" + ATTACH;
        int start = signature.indexOf('(');
        if (start < 0 || !signature.startsWith(prefix, start)) return "";
        int at = start + prefix.length();
        if (signature.charAt(at) != '<') return null;
        char first = signature.charAt(at + 1);
        if (first == '*') return null;
        // A bounded wildcard, type variable or array never equalled a holder class.
        if (first != 'L') return "";
        int end = at + 2;
        while (end < signature.length() && "<;.".indexOf(signature.charAt(end)) < 0) end++;
        // A nested class of a parameterized type is no holder class either.
        return end < signature.length() && signature.charAt(end) != '.' ? signature.substring(at + 2, end) : "";
    }

    /** Removes the method's subscribe annotation and returns it, or null when it has none. */
    private static AnnotationNode takeSubscribe(MethodNode method) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(NEW_SUBSCRIBE) || annotation.desc.equals(OLD_SUBSCRIBE)) {
                    annotations.remove(annotation);
                    return annotation;
                }
            }
        }
        return null;
    }

    /** A listener for one kind's event that calls the original method with it. */
    private static MethodNode listener(String owner, boolean isInterface, MethodNode original, AnnotationNode subscribe,
            String kind) {
        boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
        int access = original.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
        MethodNode listener = new MethodNode(access, "retromod$" + original.name + "$" + kind,
                "(L" + ATTACH + "$" + kind + ";)V", null, null);
        // The attach event is not cancellable, so receiveCanceled goes, and an old priority becomes EventBus 7's byte.
        AnnotationNode copy = new AnnotationNode(NEW_SUBSCRIBE);
        copy.values = ForgeSubscribeAnnotations.normalizedValues(subscribe.values);
        listener.visibleAnnotations = new ArrayList<>(List.of(copy));
        if (!isStatic) listener.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        listener.instructions.add(new VarInsnNode(Opcodes.ALOAD, isStatic ? 0 : 1));
        int opcode = isStatic ? Opcodes.INVOKESTATIC
                : (original.access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL
                : isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        listener.instructions.add(new MethodInsnNode(opcode, owner, original.name, original.desc, isInterface));
        listener.instructions.add(new InsnNode(Opcodes.RETURN));
        return listener;
    }
}
