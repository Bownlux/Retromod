/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes a Fabric mod's registry id reachable while its block is being constructed.
 *
 * <p>A Fabric mod registers with {@code Registry.register(registry, id, object)} and builds the
 * object inside the argument list, so by the time any Retromod code runs the block has already
 * failed in its own constructor. Arguments evaluate left to right though, which means the id is on
 * the stack before the object expression starts. This finds the instruction that produced the id and
 * stamps a copy of it into {@link FabricRegistryIdSynthetic}'s thread local right there, then clears
 * it once the registration returns.
 *
 * <p>The stamp is only inserted when the id has exactly ONE producing instruction. A merged producer
 * means the value came from more than one branch, and duplicating it there would stamp whichever the
 * analyzer happened to see rather than the one actually used.
 *
 * <p>Stamping where the {@code Properties} is CREATED is not enough on its own. A mod may build one
 * Properties into a shared static field long before any registration runs, then hand the same object
 * to several blocks. Hearth and Home does exactly that, and its ColumnBlock still arrived with no id.
 * So the Properties is also stamped where it is CONSUMED, immediately before the constructor that
 * receives it, which is always inside the registration window. The stamp returns the same object and
 * takes one argument of the type it returns, so it is stack neutral and safe to splice in.
 */
public final class FabricRegistryIdAdapter {

    private FabricRegistryIdAdapter() {}

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String BRIDGE = FabricRegistryIdSynthetic.INTERNAL;

    /** Encoded once; this runs on every class of every Fabric jar. */
    private static final byte[] REGISTER = "register".getBytes(StandardCharsets.UTF_8);

    /** One registration call worth rewriting, and the instruction that produced its id. */
    private record Stamp(MethodInsnNode call, AbstractInsnNode idProducer) {}

    /**
     * @param classBytes the class to repair
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || indexOf(classBytes, REGISTER) < 0) return classBytes;

        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }

        int stamped = 0;
        for (MethodNode m : cn.methods) {
            stamped += stampMethod(cn.name, m);
            stamped += stampConstructorArguments(m);
        }
        if (stamped == 0) return classBytes;

        try {
            SafeClassWriter cw = new SafeClassWriter(new ClassReader(classBytes),
                    ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    private static final String BLOCK_PROPS =
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String ITEM_PROPS = "net/minecraft/world/item/Item$Properties";

    /**
     * Stamps the id onto a {@code Properties} being handed to a constructor.
     *
     * <p>Only when it is the LAST argument, so it is on top of the stack and the call can be spliced
     * in without disturbing anything under it. That covers the shapes mods actually write, such as
     * {@code new Block(props)} and {@code new StairBlock(state, props)}.
     */
    private static int stampConstructorArguments(MethodNode m) {
        List<MethodInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(call.name)) continue;
            if (call.desc.endsWith("L" + BLOCK_PROPS + ";)V")
                    || call.desc.endsWith("L" + ITEM_PROPS + ";)V")) {
                targets.add(call);
            }
        }
        for (MethodInsnNode call : targets) {
            boolean block = call.desc.endsWith("L" + BLOCK_PROPS + ";)V");
            String type = block ? BLOCK_PROPS : ITEM_PROPS;
            m.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                    block ? "stampBlock" : "stampItem", "(L" + type + ";)L" + type + ";", false));
        }
        return targets.size();
    }

    private static int stampMethod(String owner, MethodNode m) {
        if (m.instructions == null || m.instructions.size() == 0) return 0;

        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && isRegistration(call)) calls.add(call);
        }
        if (calls.isEmpty()) return 0;

        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (Throwable unanalyzable) {
            return 0; // leave every call exactly as it was
        }

        // Decide first, mutate second: inserting shifts indices the frames are keyed on.
        List<Stamp> stamps = new ArrayList<>();
        for (MethodInsnNode call : calls) {
            int idx = m.instructions.indexOf(call);
            if (idx < 0 || idx >= frames.length) continue;
            Frame<SourceValue> f = frames[idx];
            if (f == null) continue; // unreachable
            // Stack at the call is [registry, id, object]; the id sits one below the top.
            int idSlot = f.getStackSize() - 2;
            if (idSlot < 0) continue;
            SourceValue id = f.getStack(idSlot);
            if (id == null || id.insns.size() != 1) continue;
            stamps.add(new Stamp(call, id.insns.iterator().next()));
        }
        if (stamps.isEmpty()) return 0;

        for (Stamp s : stamps) {
            m.instructions.insert(s.idProducer(), new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BRIDGE, "push", "(L" + IDENTIFIER + ";)V", false));
            m.instructions.insert(s.idProducer(), new InsnNode(Opcodes.DUP));
            m.instructions.insert(s.call(), new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BRIDGE, "pop", "()V", false));
        }
        return stamps.size();
    }

    /**
     * Whether this is a registration whose second argument is the entry's id.
     *
     * <p>The {@code ResourceKey} overload is deliberately not matched: its key is already complete,
     * so a mod using it does not hit the missing-id failure in the first place.
     */
    private static boolean isRegistration(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC || !"register".equals(call.name)) return false;
        boolean knownOwner = REGISTRY.equals(call.owner) || call.owner.endsWith("/WorldgenTypeBridge");
        if (!knownOwner) return false;
        return call.desc.contains(IDENTIFIER) || call.desc.contains(RESOURCE_LOCATION);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
