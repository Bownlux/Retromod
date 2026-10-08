/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

/**
 * Forge's {@code Lazy} was a functional interface, so a Forge mod could write
 * {@code Lazy<Item> item = () -> new Item(...)}. NeoForge's {@code Lazy} is a final class, so after
 * {@link ForgeNeoForgeApiBridge} moves the type, that lambda fails at its first use with
 * {@code LambdaConversionException: Lazy is not an interface}.
 *
 * <p>Each such lambda is built as a {@code Supplier} instead and wrapped with {@code Lazy.of}.
 * NeoForge's wrapper caches the first value, where the Forge lambda computed it on every call.
 * Runs only where the host's {@code Lazy} is a class with that factory.
 */
public final class LegacyLazyLambdaAdapter {

    static final String LAZY = "net/neoforged/neoforge/common/util/Lazy";
    private static final String SUPPLIER = "java/util/function/Supplier";
    private static final String OF_DESC = "(L" + SUPPLIER + ";)L" + LAZY + ";";
    private static final String LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory";

    private static volatile Boolean hostLazyIsClass;

    private LegacyLazyLambdaAdapter() {}

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null
                || !new String(classBytes, StandardCharsets.ISO_8859_1).contains(LAZY)
                || !hostLazyIsClass()) {
            return classBytes;
        }
        return rewrite(classBytes);
    }

    /** Rewrites the lambdas without probing the host, for transform-shape tests. */
    static byte[] rewrite(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof InvokeDynamicInsnNode indy && buildsLazyLambda(indy)) {
                    indy.desc = indy.desc.substring(0, indy.desc.lastIndexOf(')') + 1)
                            + "L" + SUPPLIER + ";";
                    method.instructions.insert(indy,
                            new MethodInsnNode(Opcodes.INVOKESTATIC, LAZY, "of", OF_DESC, false));
                    changed = true;
                }
            }
        }
        if (!changed) return classBytes;
        // The value left on the stack is still a Lazy, so the existing frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean buildsLazyLambda(InvokeDynamicInsnNode indy) {
        Handle bootstrap = indy.bsm;
        return bootstrap != null && LAMBDA_FACTORY.equals(bootstrap.getOwner())
                && Type.getReturnType(indy.desc).getDescriptor().equals("L" + LAZY + ";");
    }

    private static boolean hostLazyIsClass() {
        Boolean known = hostLazyIsClass;
        if (known != null) return known;
        ClassNode lazy = ClassResourceInspector.read(LAZY);
        boolean isClass = lazy != null && (lazy.access & Opcodes.ACC_INTERFACE) == 0
                && lazy.methods.stream().anyMatch(m -> m.name.equals("of") && m.desc.equals(OF_DESC)
                        && (m.access & Opcodes.ACC_STATIC) != 0);
        hostLazyIsClass = isClass;
        return isClass;
    }
}
