/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Lets a mod that defines a world type load on 1.16 and newer, which replaced {@code WorldType}
 * with dimension and world presets.
 *
 * <p>The mod's subclass extends a generated stand-in instead, so its constructor and the static
 * field that holds it still work. Its callbacks build 1.15 chunk generators and biome providers
 * that no longer exist, and nothing calls them, so their bodies are replaced with a throw; the
 * verifier then never loads the missing types. A method that stores the world type into a
 * Minecraft field, as a mod did to make its type the server default, gives up quietly when that
 * field or a call around it is gone.
 *
 * <p>The world type is not offered on the world creation screen and the server does not use it.
 */
public final class LegacyWorldTypeBridge implements Opcodes {

    static final String OLD = "net/minecraft/world/WorldType";
    public static final String BASE = "com/retromod/generated/LegacyWorldType";
    static final String FORGE_EXTENSION = "net/minecraftforge/common/extensions/IForgeWorldType";

    private LegacyWorldTypeBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerGeneratedLegacyBase(OLD, BASE, "java/lang/Object", "()V");
        transformer.registerDroppedInterface(FORGE_EXTENSION);
    }

    /** Retires a world type subclass's callbacks and guards stores of a world type. */
    public static byte[] apply(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        if (BASE.equals(node.superName)) {
            for (MethodNode method : node.methods) {
                if (isCallback(method)) {
                    throwInstead(method);
                    changed = true;
                }
            }
        }
        for (MethodNode method : node.methods) {
            if (storesWorldTypeIntoMinecraft(method)) {
                returnOnLinkageError(method);
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isCallback(MethodNode method) {
        return !method.name.startsWith("<") && (method.access & (ACC_STATIC | ACC_ABSTRACT | ACC_NATIVE)) == 0;
    }

    private static void throwInstead(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.instructions.add(new TypeInsnNode(NEW, "java/lang/UnsupportedOperationException"));
        method.instructions.add(new InsnNode(DUP));
        method.instructions.add(new LdcInsnNode("World types were removed in Minecraft 1.16"));
        method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/UnsupportedOperationException",
                "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new InsnNode(ATHROW));
    }

    private static boolean storesWorldTypeIntoMinecraft(MethodNode method) {
        if (!method.desc.endsWith(")V") || method.instructions.size() == 0) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == PUTFIELD
                    && field.desc.equals("L" + BASE + ";") && field.owner.startsWith("net/minecraft/")) {
                return true;
            }
        }
        return false;
    }

    private static void returnOnLinkageError(MethodNode method) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.insert(start);
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(POP));
        method.instructions.add(new InsnNode(RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/LinkageError"));
    }
}
