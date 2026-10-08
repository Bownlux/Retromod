/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link RetromodTransformer#registerDroppedInterface} and
 * {@link RetromodTransformer#registerInterfaceReplacement} to one class header.
 *
 * <p>The generic signature is rewritten with the same rule rather than discarded. A class such as
 * {@code Serializer<T extends Recipe<?>> implements RecipeSerializer<T>} declares its type
 * variables there, and its method signatures still name {@code T}, so dropping the class signature
 * would make reflection on those methods fail.
 */
final class ImplementedInterfaceRewrite {

    private ImplementedInterfaceRewrite() {}

    /** Whether any interface of the header has a registered rewrite. */
    static boolean applies(String[] interfaces, Map<String, String> rewrites) {
        return interfaces != null && !rewrites.isEmpty()
                && Arrays.stream(interfaces).anyMatch(rewrites::containsKey);
    }

    /** The rewritten {@code implements} list: replaced, dropped, never the class itself, no repeats. */
    static String[] interfaces(String className, String[] interfaces, Map<String, String> rewrites) {
        return Arrays.stream(interfaces)
                .map(iface -> rewrites.getOrDefault(iface, iface))
                .filter(iface -> !iface.isEmpty() && !iface.equals(className))
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * The class signature with each rewritten interface replaced by its raw replacement or removed.
     * Type parameters, the superclass, and untouched interfaces keep their generic form. A signature
     * ASM cannot parse is dropped, because a stale one disagrees with the rewritten header.
     */
    static String signature(String signature, Map<String, String> rewrites) {
        if (signature == null) return null;
        SignatureWriter head = new SignatureWriter();
        List<SignatureWriter> superInterfaces = new ArrayList<>();
        try {
            new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitFormalTypeParameter(String name) {
                    head.visitFormalTypeParameter(name);
                }

                @Override
                public SignatureVisitor visitClassBound() {
                    return head.visitClassBound();
                }

                @Override
                public SignatureVisitor visitInterfaceBound() {
                    return head.visitInterfaceBound();
                }

                @Override
                public SignatureVisitor visitSuperclass() {
                    return head.visitSuperclass();
                }

                @Override
                public SignatureVisitor visitInterface() {
                    SignatureWriter superInterface = new SignatureWriter();
                    superInterfaces.add(superInterface);
                    return superInterface;
                }
            });
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException malformed) {
            return null;
        }

        StringBuilder rewritten = new StringBuilder(head.toString());
        for (SignatureWriter superInterface : superInterfaces) {
            String type = superInterface.toString();
            String replacement = rewrites.get(rawName(type));
            if (replacement == null) {
                rewritten.append(type);
            } else if (!replacement.isEmpty()) {
                rewritten.append('L').append(replacement).append(';');
            }
        }
        return rewritten.toString();
    }

    /** {@code Lpkg/Name<...>;} to {@code pkg/Name}. */
    private static String rawName(String classTypeSignature) {
        int end = classTypeSignature.indexOf('<');
        if (end < 0) end = classTypeSignature.indexOf(';');
        return classTypeSignature.substring(1, end);
    }
}
