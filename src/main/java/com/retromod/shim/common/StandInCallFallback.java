/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Predicate;

/** Drops a game call whose signature still names a Retromod stand-in, which no host method can have. */
public final class StandInCallFallback {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod/StandInCallFallback");
    private static final String[] HOST_PREFIXES = {"net/minecraft/", "com/mojang/", "net/neoforged/",
            "net/minecraftforge/", "net/fabricmc/"};

    private StandInCallFallback() {}

    /** The class with each such call replaced by popped arguments and a default or supplied result; {@code notHost} names classes that are not the game's. */
    public static byte[] apply(byte[] classBytes, Map<String, String> standIns, Map<String, String> results,
            Predicate<String> notHost) {
        if (classBytes == null || standIns.isEmpty() || !mentionsAny(classBytes, standIns)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        int dropped = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && cannotLink(call, standIns, notHost)) {
                    method.instructions.insertBefore(call, replacement(call, standIns, results));
                    method.instructions.remove(call);
                    LOGGER.debug("Dropped {}.{}{} in {}", call.owner, call.name, call.desc, node.name);
                    dropped++;
                }
            }
        }
        if (dropped == 0) return classBytes;
        LOGGER.info("Dropped {} game call(s) in {} that take or return a Retromod stand-in, which no game method has",
                dropped, node.name);
        // The stack keeps its height and types, so the existing frames and maximums stay valid.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A game method or interface call, not a constructor, that names a stand-in in its descriptor. */
    static boolean cannotLink(MethodInsnNode call, Map<String, String> standIns, Predicate<String> notHost) {
        if (call.name.startsWith("<") || call.getOpcode() == Opcodes.INVOKEDYNAMIC || !isHostOwner(call.owner)) return false;
        // The owner lookup can read a jar, so it runs only for a call that names a stand-in.
        return namesStandIn(Type.getMethodType(call.desc), standIns) && !notHost.test(call.owner);
    }

    private static boolean namesStandIn(Type method, Map<String, String> standIns) {
        if (isStandIn(method.getReturnType(), standIns)) return true;
        for (Type argument : method.getArgumentTypes()) {
            if (isStandIn(argument, standIns)) return true;
        }
        return false;
    }

    /** Pops the arguments and receiver, then pushes the stand-in's shared instance, zero, or null. */
    static InsnList replacement(MethodInsnNode call, Map<String, String> standIns, Map<String, String> results) {
        InsnList out = new InsnList();
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int i = arguments.length - 1; i >= 0; i--) {
            out.add(new InsnNode(arguments[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        boolean hasReceiver = call.getOpcode() != Opcodes.INVOKESTATIC;
        Type result = Type.getReturnType(call.desc);
        // A call returning a stand-in gets its shared instance, so the mod's next call on it still runs.
        String supplier = result.getSort() == Type.OBJECT ? standIns.get(result.getInternalName()) : null;
        if (supplier == null && result.getSort() == Type.OBJECT) supplier = results.get(result.getInternalName());
        if (supplier != null && !supplier.isEmpty()) {
            int dot = supplier.indexOf('.');
            int paren = supplier.indexOf('(');
            String desc = supplier.substring(paren);
            // A supplier taking an Object is handed the receiver, or null for a static call.
            boolean takesOwner = desc.startsWith("(Ljava/lang/Object;)");
            if (hasReceiver && !takesOwner) out.add(new InsnNode(Opcodes.POP));
            if (!hasReceiver && takesOwner) out.add(new InsnNode(Opcodes.ACONST_NULL));
            out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, supplier.substring(0, dot), supplier.substring(dot + 1, paren),
                    desc, false));
            return out;
        }
        if (hasReceiver) out.add(new InsnNode(Opcodes.POP));
        switch (result.getSort()) {
            case Type.VOID -> { }
            case Type.LONG -> out.add(new InsnNode(Opcodes.LCONST_0));
            case Type.FLOAT -> out.add(new InsnNode(Opcodes.FCONST_0));
            case Type.DOUBLE -> out.add(new InsnNode(Opcodes.DCONST_0));
            case Type.OBJECT, Type.ARRAY -> out.add(new InsnNode(Opcodes.ACONST_NULL));
            default -> out.add(new InsnNode(Opcodes.ICONST_0));
        }
        return out;
    }

    private static boolean isStandIn(Type type, Map<String, String> standIns) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        return element.getSort() == Type.OBJECT && standIns.containsKey(element.getInternalName());
    }

    private static boolean isHostOwner(String owner) {
        for (String prefix : HOST_PREFIXES) {
            if (owner.startsWith(prefix)) return true;
        }
        return false;
    }

    // A cheap constant-pool check, so classes that never name a stand-in are not parsed.
    private static boolean mentionsAny(byte[] classBytes, Map<String, String> standIns) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (String type : standIns.keySet()) {
            if (text.contains(type)) return true;
        }
        return false;
    }
}
