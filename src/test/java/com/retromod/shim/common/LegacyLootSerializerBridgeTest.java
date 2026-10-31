/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Fossils and Archaeology 9.3.4.0 (Forge 1.20.1) registers a loot function with
 * {@code new LootItemFunctionType(new CustomizeToDinoFunction.Serializer())}. 1.20.5 removed the Gson
 * serializers, so NeoForge 1.21.1 stopped at {@code NoClassDefFoundError:
 * LootItemConditionalFunction$Serializer} while the mod constructed.
 */
class LegacyLootSerializerBridgeTest {

    private static final String LOOT = "net/minecraft/world/level/storage/loot/";
    private static final String CONDITIONS = "[L" + LOOT + "predicates/LootItemCondition;";
    private static final String GSON = String.join("/", "com", "google", "gson") + "/";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A serializer subclass extends the generated base, and its type is built around a codec")
    void serializerAndTypeAreBridged() {
        RetromodTransformer transformer = bridged("1.21.1");

        ClassNode serializer = transform(transformer, serializerSubclass(), "mod/DinoFunction$Serializer");
        ClassNode types = transform(transformer, typeRegistration(), "mod/ModLootTypes");

        assertEquals(LegacyLootSerializerBridge.FUNCTION_SERIALIZER, serializer.superName);
        assertEquals(List.of(LegacyLootSerializerBridge.TYPES + ".functionType"), calls(types));
    }

    @Test
    @DisplayName("super(conditions[]) passes a list on 1.21.1 and one optional holder on 26.3")
    void conditionsFollowTheHost() {
        RetromodTransformer transformer = bridged("1.21.1");
        List<String> listCalls = calls(transform(transformer, conditionalFunction(), "mod/DinoFunction"));
        assertTrue(listCalls.contains("java/util/Arrays.asList"), listCalls.toString());
        assertTrue(listCalls.contains(LegacyLootSerializerBridge.CONDITIONAL_FUNCTION + ".<init>(Ljava/util/List;)V"),
                listCalls.toString());

        transformer = bridged("26.3");
        List<String> holderCalls = calls(transform(transformer, conditionalFunction(), "mod/DinoFunction"));
        assertTrue(holderCalls.contains(LegacyLootSerializerBridge.SUPPORT + ".conditionHolder"), holderCalls.toString());
    }

    @Test
    @DisplayName("A 1.20.4 host keeps its own serializers")
    void olderHostsAreLeftAlone() {
        RetromodTransformer transformer = bridged("1.20.4");
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyLootSerializerBridge.TYPES));
    }

    @Test
    @DisplayName("The generated serializer types and factory verify")
    void generatedClassesVerify() {
        for (byte[] generated : List.of(LegacyLootSerializerBridge.generateSerializer(),
                LegacyLootSerializerBridge.generateFunctionSerializer(), LegacyLootSerializerBridge.generateTypes())) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static RetromodTransformer bridged(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyLootSerializerBridge.register(transformer);
        return transformer;
    }

    /** {@code class Serializer extends LootItemConditionalFunction.Serializer<DinoFunction>} with the old overrides. */
    private static byte[] serializerSubclass() {
        String base = LegacyLootSerializerBridge.OLD_FUNCTION_SERIALIZER;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/DinoFunction$Serializer", null, base, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, base, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC, "deserialize", "(L" + GSON + "JsonObject;L" + GSON
                + "JsonDeserializationContext;" + CONDITIONS + ")L" + LegacyLootSerializerBridge.CONDITIONAL_FUNCTION + ";", null, null);
        read.visitCode();
        read.visitInsn(ACONST_NULL);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Object TYPE = new LootItemFunctionType(new DinoFunction.Serializer());} */
    private static byte[] typeRegistration() {
        String type = LegacyLootSerializerBridge.FUNCTION_TYPE;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/ModLootTypes", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "(L" + LegacyLootSerializerBridge.OLD_SERIALIZER + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, type);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "(L" + LegacyLootSerializerBridge.OLD_SERIALIZER + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code DinoFunction(LootItemCondition[] conditions) { super(conditions); }} */
    private static byte[] conditionalFunction() {
        String base = LegacyLootSerializerBridge.CONDITIONAL_FUNCTION;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "mod/DinoFunction", null, base, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + CONDITIONS + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, base, "<init>", "(" + CONDITIONS + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode transform(RetromodTransformer transformer, byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, name)).accept(node, 0);
        return node;
    }

    /** Each call as owner.name, and owner.name+desc for constructors. */
    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    calls.add(call.owner + "." + call.name + (call.name.equals("<init>") ? call.desc : ""));
                }
            }
        }
        return calls;
    }
}
