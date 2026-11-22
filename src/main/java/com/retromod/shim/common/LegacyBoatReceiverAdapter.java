/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls an inherited method on a boat through AbstractBoat, since 1.21.2 chest boats stopped being Boats.
 * Old code that keeps a chest boat or a boat in one Boat variable otherwise fails verification.
 */
public final class LegacyBoatReceiverAdapter {

    public static final String FLAG = "legacy-boat-receiver";

    // Boat's 1.21.2 to 1.21.11 name and its 26.x name, where both hold Mojang names.
    private static final List<String> BOAT_NAMES = List.of("net/minecraft/world/entity/vehicle/boat/Boat",
            "net/minecraft/world/entity/vehicle/Boat");
    private static final byte[] MARKER = "/Boat".getBytes(StandardCharsets.ISO_8859_1);

    // Whether a public method is inherited by Boat from AbstractBoat or above, keyed by "name desc".
    private static final Map<String, Boolean> INHERITED = new ConcurrentHashMap<>();

    private LegacyBoatReceiverAdapter() {}

    /** Turns the adapter on when the host's Boat extends an AbstractBoat that chest boats share. */
    public static void register(RetromodTransformer t) {
        if (hostBoat() != null) t.enablePostProcess(FLAG);
    }

    /** The host's Boat class when it has the 1.21.2 shape, or null. */
    static ClassNode hostBoat() {
        for (String name : BOAT_NAMES) {
            ClassNode boat = ClassResourceInspector.read(name);
            if (boat != null && boat.superName != null && boat.superName.endsWith("/AbstractBoat")) return boat;
        }
        return null;
    }

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, MARKER)) return classBytes;
        ClassNode boat = hostBoat();
        if (boat == null) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        int changed = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals(boat.name) || !inheritedPublic(boat, call.name, call.desc)) {
                    continue;
                }
                // Same method, resolved from a type every boat shares, so a chest boat in the variable still verifies.
                call.owner = boat.superName;
                changed++;
            }
        }
        if (changed == 0) return classBytes;
        // Only owners changed, so the class keeps its stack map frames.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether Boat does not declare the method itself and a public one is found above it. */
    static boolean inheritedPublic(ClassNode boat, String name, String desc) {
        return INHERITED.computeIfAbsent(boat.name + "." + name + desc, key -> {
            if (declares(boat, name, desc) != null) return false;
            String owner = boat.superName;
            for (int depth = 0; owner != null && depth < 8; depth++) {
                ClassNode type = ClassResourceInspector.read(owner);
                if (type == null) return false;
                MethodNode found = declares(type, name, desc);
                if (found != null) return (found.access & Opcodes.ACC_PUBLIC) != 0 && (found.access & Opcodes.ACC_STATIC) == 0;
                owner = type.superName;
            }
            return false;
        });
    }

    private static MethodNode declares(ClassNode type, String name, String desc) {
        for (MethodNode method : type.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }
}
