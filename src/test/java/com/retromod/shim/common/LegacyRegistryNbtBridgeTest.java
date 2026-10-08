/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyRegistryNbtBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 block entity's NBT overrides against the 1.20.5 provider-taking methods. */
class LegacyRegistryNbtBridgeTest {

    private static final String L_COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String L_LIST = "Lnet/minecraft/core/NonNullList;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void oldOverridesGetModernOverridesThatRunThem() {
        ClassNode node = node(apply(modBlockEntity(BLOCK_ENTITY), Map.of()));

        MethodNode load = method(node, "loadAdditional", NEW_NBT);
        MethodNode save = method(node, "saveAdditional", NEW_NBT);
        assertTrue(calls(load).contains("test/Crate.load" + OLD_LOAD), "loadAdditional must run the old load");
        assertTrue(calls(save).contains("test/Crate.saveAdditional" + OLD_LOAD),
                "the provider overload must run the old save");
    }

    @Test
    void superCallsThatReachMinecraftTakeTheProvider() {
        ClassNode node = node(apply(modBlockEntity(BLOCK_ENTITY), Map.of()));

        List<String> loadCalls = calls(method(node, "load", OLD_LOAD));
        assertTrue(loadCalls.contains(HELPER + ".provider()Lnet/minecraft/core/HolderLookup$Provider;"), loadCalls.toString());
        assertTrue(loadCalls.contains(BLOCK_ENTITY + ".loadAdditional" + NEW_NBT),
                "super.load(tag) must become super.loadAdditional(tag, provider): " + loadCalls);
    }

    @Test
    void aSuperCallToAModParentThatStillDeclaresTheMethodIsKept() {
        byte[] parent = modBlockEntity(BLOCK_ENTITY);
        byte[] child = modBlockEntity("test/Crate", "test/BigCrate");

        ClassNode node = node(apply(child, Map.of("test/Crate", parent)));

        assertTrue(calls(method(node, "load", OLD_LOAD)).contains("test/Crate.load" + OLD_LOAD),
                "the parent's own old load still answers the super call");
    }

    @Test
    void aPrivateHelperWithTheOldShapeGetsNoModernOverride() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Tank", null, BLOCK_ENTITY, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "load", OLD_LOAD, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(apply(cw.toByteArray(), Map.of()));

        assertTrue(node.methods.stream().noneMatch(m -> m.name.equals("loadAdditional")),
                "a private load(tag) is the mod's own helper and must not replace the inherited loadAdditional");
    }

    @Test
    void containerHelperCallsGoThroughTheHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, true);

        ClassNode node = node(transformer.transformClass(containerCaller(), "test/Inventory.class"));

        assertTrue(calls(method(node, "save", "(" + L_COMPOUND + L_LIST + ")V"))
                .contains(HELPER + ".saveAllItems(" + L_COMPOUND + L_LIST + ")" + L_COMPOUND));
    }

    @Test
    void oldWorldSavedDataSavesThroughTheProviderOverride() throws Exception {
        String desc = "(" + L_COMPOUND + ")" + L_COMPOUND;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/WorldState", null, SAVED_DATA, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "save", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(LegacyRegistryNbtBridge.apply(cw.toByteArray(),
                (child, ancestor) -> ancestor.equals(SAVED_DATA), name -> null));

        assertTrue(calls(method(node, "save", NEW_DATA_SAVE)).contains("test/WorldState.save" + desc),
                "the host's save(tag, provider) must run the old save(tag)");
        verify(node);
    }

    @Test
    void theOldLoaderLookupBuildsASavedDataFactory() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, false);
        registerSavedDataRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/WorldStateAccess", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "find",
                "(L" + STORAGE + ";Ljava/util/function/Function;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("examplemod");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STORAGE, "get", OLD_GET, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(transformer.transformClass(cw.toByteArray(), "test/WorldStateAccess.class"));

        List<String> calls = calls(method(node, "find", "(L" + STORAGE + ";Ljava/util/function/Function;)Ljava/lang/Object;"));
        assertTrue(calls.contains(HELPER + ".dataGet(L" + STORAGE + ";" + OLD_GET.substring(1)),
                "the old lookup must build a factory through the helper: " + calls);
    }

    @Test
    void theHelperAndTheBridgedClassVerify() throws Exception {
        verify(node(generateHelper(true)));
        verify(node(generateHelper(false)));
        verify(node(apply(modBlockEntity(BLOCK_ENTITY), Map.of())));
    }

    private static byte[] apply(byte[] bytes, Map<String, byte[]> modClasses) {
        return LegacyRegistryNbtBridge.apply(bytes, (child, ancestor) -> ancestor.equals(BLOCK_ENTITY),
                modClasses::get);
    }

    private static byte[] modBlockEntity(String parent) {
        return modBlockEntity(parent, "test/Crate");
    }

    /** {@code load(tag) { super.load(tag); }} and {@code saveAdditional(tag) { super.saveAdditional(tag); }}. */
    private static byte[] modBlockEntity(String parent, String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        for (String method : new String[]{"load", "saveAdditional"}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, OLD_LOAD, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, method, OLD_LOAD, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] containerCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Inventory", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "save",
                "(" + L_COMPOUND + L_LIST + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTAINER_HELPER, "saveAllItems",
                "(" + L_COMPOUND + L_LIST + ")" + L_COMPOUND, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            }
        }
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + name + desc));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
