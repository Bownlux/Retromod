/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.neoforge.embedded.LegacyHandlerNbtTestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #366: ProjectE saves its machines with {@code ItemStackHandler.serializeNBT}, which NeoForge 1.21.6
 * replaced with ValueIO, so saving a ProjectE machine on 1.21.6 to 26.2 threw {@code NoSuchMethodError}.
 */
class LegacyHandlerNbtBridgeTest {

    private static final String SUPPORT = LegacyHandlerNbtBridge.SUPPORT;
    private static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
    private static final String COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String STACK_HANDLER = "mod/StackHandler";
    private static final String OWN_HANDLER = "mod/OwnHandler";
    private static final String MACHINE = "mod/Machine";

    private RetromodTransformer transformer;
    private final Map<String, byte[]> jar = new HashMap<>();

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHandlerNbtBridge.registerCalls(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Saves on the handlers and on a mod's own subclass reach the helper")
    void oldSaveCallsReachTheHelper() {
        jar.put(STACK_HANDLER, handler(STACK_HANDLER, false));
        jar.put(MACHINE, machine());

        List<String> calls = calls(transform(MACHINE), "save");
        for (String expected : List.of("serializeNBT", "deserializeNBT", "writeToNBT", "readFromNBT",
                "energySerializeNBT", "energyDeserializeNBT")) {
            assertTrue(calls.stream().anyMatch(call -> call.equals(INVOKESTATIC + " " + SUPPORT + "." + expected)),
                    expected + " was not sent to the helper: " + calls);
        }
        assertTrue(calls.stream().noneMatch(call -> call.contains("NBT") && !call.contains(SUPPORT)),
                "an old save call is left: " + calls);
    }

    @Test
    @DisplayName("An override's super call goes to the twin that only saves, so the override does not run again")
    void overrideSuperCallSkipsTheOverride() {
        jar.put(OWN_HANDLER, handler(OWN_HANDLER, true));
        List<String> calls = calls(transform(OWN_HANDLER), "serializeNBT");
        assertEquals(List.of(INVOKESTATIC + " " + SUPPORT + ".super$serializeNBT"), calls);
    }

    @Test
    @DisplayName("The helper finds a mod handler's own override, and none on a plain subclass")
    void helperFindsOnlyRealOverrides() {
        assertNotNull(LegacyHandlerNbtTestAccess.override(new LegacyHandlerNbtTestAccess.OwnHandler(), "serializeNBT", 1));
        assertNull(LegacyHandlerNbtTestAccess.override(new LegacyHandlerNbtTestAccess.PlainHandler(), "serializeNBT", 1));
    }

    @Test
    @DisplayName("The helper is one class, since the embedder copies only that class into each mod")
    void helperHasNoNestedClasses() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(SUPPORT + ".class")) {
            assertNotNull(in, "the helper is missing from the build");
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            assertTrue(node.innerClasses.stream().noneMatch(inner -> inner.name.startsWith(SUPPORT + "$")),
                    "a nested class would be left out of the mod and fail with IllegalAccessError");
        }
    }

    /** ProjectE's machine: it saves and loads its handlers, a tank, and an energy storage. */
    private static byte[] machine() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, MACHINE, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "save", "(L" + STACK_HANDLER + ";L"
                + LegacyHandlerNbtBridge.ITEM_HANDLER + ";L" + LegacyHandlerNbtBridge.FLUID_TANK + ";L"
                + LegacyHandlerNbtBridge.ENERGY_STORAGE + ";" + PROVIDER + COMPOUND + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, STACK_HANDLER, "serializeNBT", "(" + PROVIDER + ")" + COMPOUND, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyHandlerNbtBridge.ITEM_HANDLER, "deserializeNBT", "(" + PROVIDER + COMPOUND + ")V", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyHandlerNbtBridge.FLUID_TANK, "writeToNBT", "(" + PROVIDER + COMPOUND + ")" + COMPOUND, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyHandlerNbtBridge.FLUID_TANK, "readFromNBT",
                "(" + PROVIDER + COMPOUND + ")L" + LegacyHandlerNbtBridge.FLUID_TANK + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyHandlerNbtBridge.ENERGY_STORAGE, "serializeNBT", "(" + PROVIDER + ")Lnet/minecraft/nbt/Tag;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyHandlerNbtBridge.ENERGY_STORAGE, "deserializeNBT", "(" + PROVIDER + "Lnet/minecraft/nbt/Tag;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod's handler subclass; with {@code overrides}, it adds to serializeNBT and calls super. */
    private static byte[] handler(String name, boolean overrides) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, LegacyHandlerNbtBridge.ITEM_HANDLER, null);
        if (overrides) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "serializeNBT", "(" + PROVIDER + ")" + COMPOUND, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, LegacyHandlerNbtBridge.ITEM_HANDLER, "serializeNBT", "(" + PROVIDER + ")" + COMPOUND, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] transform(String name) {
        try (var scope = transformer.pushJarClassBytesProvider(jar::get)) {
            return transformer.transformClass(jar.get(name), name + ".class");
        }
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name);
            }
        }
        return calls;
    }
}
