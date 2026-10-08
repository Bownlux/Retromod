/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/**
 * Finds the Minecraft class a mod's member access really reaches.
 *
 * <p>Javac names the static type in the bytecode, usually the mod's own subclass. The legacy
 * entity and level adapters may only touch members inherited from Minecraft. A field or method
 * that the mod or a library declares under the same name and type is not the one 1.19 or 1.20
 * changed, and rewriting it breaks a call that linked fine.
 */
final class LegacyMemberOwners {

    private static final int MAX_DEPTH = 16;

    private LegacyMemberOwners() {
    }

    /**
     * The first Minecraft class in {@code owner}'s superclass chain, or null when the mod
     * declares the member itself or the chain leaves the jar through a class that is not
     * Minecraft's, such as a library base class.
     */
    static String minecraftBase(String owner, String name, String desc, boolean method,
            Function<String, byte[]> modClasses, ClassNode self) {
        String current = owner;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current.startsWith("net/minecraft/")) {
                return current;
            }
            ClassNode type = current.equals(self.name) ? self : readModClass(modClasses, current);
            if (type == null || declares(type, name, desc, method)) {
                return null;
            }
            current = type.superName;
        }
        return null;
    }

    /**
     * Whether the host's {@code minecraftClass} is {@code target} or extends it, or null when the
     * host's class files cannot be read, as in an offline transform.
     */
    static Boolean hostExtends(String minecraftClass, String target) {
        String current = minecraftClass;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current.equals(target)) {
                return Boolean.TRUE;
            }
            if (!current.startsWith("net/minecraft/")) {
                return Boolean.FALSE;
            }
            ClassNode host = ClassResourceInspector.read(current);
            if (host == null) {
                return null;
            }
            current = host.superName;
        }
        return Boolean.FALSE;
    }

    /**
     * Whether a Minecraft class is an entity. Goals, navigation, and other AI helpers live in
     * the same package and have their own {@code level} fields, so the package alone decides
     * only when the host cannot be read.
     */
    static boolean isEntity(String minecraftClass) {
        Boolean known = hostExtends(minecraftClass, LegacyRandomSourceAdapter.ENTITY);
        if (known != null) {
            return known;
        }
        return (minecraftClass.startsWith("net/minecraft/world/entity/")
                && !minecraftClass.startsWith("net/minecraft/world/entity/ai/"))
                || minecraftClass.startsWith("net/minecraft/client/player/")
                || minecraftClass.startsWith("net/minecraft/server/level/ServerPlayer");
    }

    /** Whether a Minecraft class is a level, judged as in {@link #isEntity}. */
    static boolean isLevel(String minecraftClass) {
        Boolean known = hostExtends(minecraftClass, LegacyRandomSourceAdapter.LEVEL);
        if (known != null) {
            return known;
        }
        return minecraftClass.equals(LegacyRandomSourceAdapter.LEVEL)
                || minecraftClass.equals("net/minecraft/server/level/ServerLevel")
                || minecraftClass.equals("net/minecraft/client/multiplayer/ClientLevel");
    }

    private static boolean declares(ClassNode type, String name, String desc, boolean method) {
        if (method) {
            for (MethodNode candidate : type.methods) {
                if (candidate.name.equals(name) && candidate.desc.equals(desc)) {
                    return true;
                }
            }
            return false;
        }
        for (FieldNode candidate : type.fields) {
            if (candidate.name.equals(name) && candidate.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode readModClass(Function<String, byte[]> modClasses, String name) {
        byte[] bytes = modClasses == null ? null : modClasses.apply(name);
        if (bytes == null) {
            return null;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
