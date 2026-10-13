/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Does on a NeoForge dedicated server what Forge's dist cleaner did for a Forge mod's
 * {@code @OnlyIn(Dist.CLIENT)} members: removes them.
 *
 * <p>Forge deleted those members on the server, and its event bus only listed public methods.
 * NeoForge has no {@code @OnlyIn} and lists every method of a subscriber class, which resolves
 * each parameter type. A client-setup handler such as an MCreator dimension's sky-effects hook
 * then failed the whole mod's subscriber registration on the server with a missing
 * {@code net.minecraft.client} class, usually through the handler's private lambda. The marked
 * members are removed, and so is any private synthetic method, such as that lambda, that nothing
 * left in the class still uses. A class marked as a whole is left alone; nothing loads it on a
 * server unless the mod reaches it from common code, which Forge did not allow either.
 */
public final class ForgeDistCleaner {

    private static final String ONLY_IN = "Lnet/minecraftforge/api/distmarker/OnlyIn;";
    private static final String DIST = "Lnet/minecraftforge/api/distmarker/Dist;";

    private ForgeDistCleaner() {}

    /** The class without its client-only members, or the same array when it has none. */
    public static byte[] stripClientOnly(byte[] classBytes) {
        if (classBytes == null
                || new String(classBytes, StandardCharsets.ISO_8859_1).indexOf(ONLY_IN.substring(1, ONLY_IN.length() - 1)) < 0) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = node.methods.removeIf(method -> clientOnly(method.visibleAnnotations)
                || clientOnly(method.invisibleAnnotations));
        changed |= node.fields.removeIf(field -> clientOnly(field.visibleAnnotations)
                || clientOnly(field.invisibleAnnotations));
        if (!changed) return classBytes;
        removeOrphanedSynthetics(node);
        ClassWriter writer = new ClassWriter(0); // whole members removed; remaining code is unchanged
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean clientOnly(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (!ONLY_IN.equals(annotation.desc) || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String[] dist
                        && DIST.equals(dist[0]) && "CLIENT".equals(dist[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Removes private synthetic methods, such as lambda bodies, that no remaining code refers to. */
    private static void removeOrphanedSynthetics(ClassNode node) {
        boolean removed = true;
        while (removed) {
            Set<String> used = new HashSet<>();
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                        used.add(call.name + call.desc);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle && handle.getOwner().equals(node.name)) {
                                used.add(handle.getName() + handle.getDesc());
                            }
                        }
                    }
                }
            }
            removed = node.methods.removeIf(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0
                    && (method.access & Opcodes.ACC_PRIVATE) != 0
                    && !used.contains(method.name + method.desc));
        }
    }
}
