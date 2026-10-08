/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge's SRG names a record accessor after its component field, so a 1.20.1 Forge mod calls
 * {@code TagKey.location()} as {@code f_203868_()}. A Mojang-named host must resolve that id
 * through the field table.
 */
class SrgRecordAccessorRemapTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void theBundledDictionaryNamesTheTagKeyLocationComponent() {
        assertEquals("location", SrgToMojangMapper.getInstance().getFieldMap().get("f_203868_"),
                "TagKey's location component must be in the SRG field table");
    }

    @Test
    void anAccessorCalledByItsFieldIdGetsTheComponentName() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(Map.of(), Map.of("f_203868_", "location"));

        List<String> calls = calledNames(transformer.transformClass(
                caller("f_203868_", "()Lnet/minecraft/resources/ResourceLocation;"),
                "test/RecordCaller.class"));

        assertEquals(List.of("location"), calls, "the accessor call must use the Mojang name");
    }

    @Test
    void aMethodWithArgumentsIsNotTreatedAsAnAccessor() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(Map.of(), Map.of("f_203868_", "location"));

        List<String> calls = calledNames(transformer.transformClass(
                caller("f_203868_", "(I)Lnet/minecraft/resources/ResourceLocation;"),
                "test/RecordCaller.class"));

        assertEquals(List.of("f_203868_"), calls,
                "only a no-argument call can be a record accessor");
    }

    @Test
    void anSrgHostKeepsTheFieldIdItNamesTheAccessorBy() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(Map.of(), Map.of("f_203868_", "location"));
        transformer.registerTargetSrgNameMappings(Map.of(), Map.of(TargetSrgMapper.memberKey(
                "net/minecraft/tags/TagKey", "location", "Lnet/minecraft/resources/ResourceLocation;"),
                "f_203868_"));

        List<String> calls = calledNames(transformer.transformClass(
                caller("f_203868_", "()Lnet/minecraft/resources/ResourceLocation;"),
                "test/RecordCaller.class"));

        assertEquals(List.of("f_203868_"), calls, "Forge 1.20.1 names TagKey.location() f_203868_");
        assertEquals("f_203868_", transformer.remapQualifiedMethodName("net/minecraft/tags/TagKey",
                        "f_203868_", "()Lnet/minecraft/resources/ResourceLocation;"),
                "a mixin selector on an SRG host must keep the accessor's field id");
    }

    private static byte[] caller(String name, String descriptor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/RecordCaller", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use",
                "(Lnet/minecraft/tags/TagKey;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (descriptor.startsWith("(I")) mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/tags/TagKey", name,
                descriptor, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calledNames(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> names = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.stream()
                .filter(m -> m.name.equals("use")).findFirst().orElseThrow()
                .instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraft/tags/TagKey")) {
                names.add(call.name);
            }
        }
        return names;
    }
}
