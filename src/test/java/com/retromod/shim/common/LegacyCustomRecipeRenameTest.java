/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE builds its special recipes' serializers with {@code SimpleCraftingRecipeSerializer}, which 1.21.11 renamed. */
class LegacyCustomRecipeRenameTest {

    private static final String OLD = LegacyRecipeSerializerShim.OLD_SIMPLE_SERIALIZER;
    private static final String RENAMED = LegacyRecipeSerializerShim.OLD_CUSTOM_SERIALIZER;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A 1.21.11 host, with only CustomRecipe.Serializer, gets the old name pointed at it")
    void renamesOnAHostWithOnlyTheNewName() {
        List<String> types = typesAfterRegistering(Set.of(RENAMED));
        assertTrue(types.contains(RENAMED) && types.contains(RENAMED + "$Factory"), types.toString());
        assertFalse(types.contains(OLD), types.toString());
    }

    @Test
    @DisplayName("A host that still has the old name, or neither, is left alone")
    void otherHostsAreLeftAlone() {
        assertTrue(typesAfterRegistering(Set.of(OLD, RENAMED)).contains(OLD));
        assertTrue(typesAfterRegistering(Set.of()).contains(OLD));
    }

    /** The types a ProjectE-style serializer construction names, on a host that ships {@code hostClasses}. */
    private static List<String> typesAfterRegistering(Set<String> hostClasses) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ClassLoader host = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String internal = name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
                return hostClasses.contains(internal) ? new ByteArrayInputStream(emptyClass(internal)) : null;
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(host);
        try {
            LegacyRecipeSerializerShim.registerCustomRecipeRename(transformer);
        } finally {
            thread.setContextClassLoader(previous);
        }
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(serializers(), "mod/Serializers.class")).accept(node, 0);
        List<String> types = new ArrayList<>();
        node.methods.forEach(method -> method.instructions.forEach(insn -> {
            if (insn instanceof TypeInsnNode type) types.add(type.desc);
            if (insn instanceof MethodInsnNode call) types.add(call.owner);
        }));
        node.methods.forEach(method -> types.add(method.desc));
        return types.stream().flatMap(t -> List.of(t.split("[();L]")).stream()).filter(t -> !t.isEmpty()).toList();
    }

    /** {@code new SimpleCraftingRecipeSerializer(factory)}, as a 1.21.1 mod writes it. */
    private static byte[] serializers() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Serializers", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "(L" + OLD + "$Factory;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", "(L" + OLD + "$Factory;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
