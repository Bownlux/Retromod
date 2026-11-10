/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

/** Reads {@code server.getWorldData().worldGenOptions()} from the server's world gen settings, where 26.1 moved it. */
public final class LegacyWorldGenOptionsAdapter {

    public static final String FLAG = "legacy-world-gen-options";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String WORLD_DATA = "net/minecraft/world/level/storage/WorldData";
    static final String SETTINGS = "net/minecraft/world/level/levelgen/WorldGenSettings";
    static final String OPTIONS_DESC = "()Lnet/minecraft/world/level/levelgen/WorldOptions;";
    private static final byte[] NEEDLE = "worldGenOptions".getBytes(StandardCharsets.UTF_8);

    private LegacyWorldGenOptionsAdapter() {}

    /** Turns the adapter on where the host's level data lost the options and its server keeps the settings. */
    public static void register(RetromodTransformer t) {
        ClassNode data = ClassResourceInspector.read(WORLD_DATA);
        ClassNode server = ClassResourceInspector.read(SERVER);
        if (hostMovedOptions(data, server)) t.enablePostProcess(FLAG);
    }

    /** Whether the options left the level data for the server; offline, 26.1 and newer moved them. */
    static boolean hostMovedOptions(ClassNode data, ClassNode server) {
        if (data == null || server == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        }
        return !declares(data, "worldGenOptions", OPTIONS_DESC)
                && declares(server, "getWorldGenSettings", "()L" + SETTINGS + ";");
    }

    /** Rewrites each server read followed at once by the options read; a level data held elsewhere is left as it is. */
    public static byte[] apply(byte[] bytes) {
        if (bytes == null || !contains(bytes, NEEDLE)) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode read) || !isWorldDataRead(read)) continue;
                AbstractInsnNode next = insn.getNext();
                if (!(next instanceof MethodInsnNode options) || !isOptionsRead(options)) continue;
                // Both calls keep their stack shape: one server in, one value out.
                read.name = "getWorldGenSettings";
                read.desc = "()L" + SETTINGS + ";";
                options.setOpcode(Opcodes.INVOKEVIRTUAL);
                options.owner = SETTINGS;
                options.name = "options";
                options.itf = false;
                changed = true;
            }
        }
        if (!changed) return bytes;
        // Only call targets changed, so the class keeps its own frames and stack sizes.
        ClassWriter out = new ClassWriter(0);
        node.accept(out);
        return out.toByteArray();
    }

    private static boolean isWorldDataRead(MethodInsnNode call) {
        // The server or one of its game subclasses, never a mod's own class of the same shape.
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("getWorldData")
                && call.desc.equals("()L" + WORLD_DATA + ";") && call.owner.startsWith("net/minecraft/")
                && call.owner.endsWith("Server");
    }

    private static boolean isOptionsRead(MethodInsnNode call) {
        return call.owner.equals(WORLD_DATA) && call.name.equals("worldGenOptions") && call.desc.equals(OPTIONS_DESC);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
