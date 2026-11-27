/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #400, #394: SoniCraft and Villager Recruits (Forge 1.20.1) read loot tables through {@code server.getLootData()},
 * which 1.20.5 removed. The stand-in served only hosts from 1.21.11; NeoForge 1.21.1 gets it in ResourceLocation.
 */
class LegacyLootDataBridgeTest {

    private static final String SERVER = LegacyLootDataBridge.SERVER;
    private static final String MANAGER = LegacyLootDataBridge.OLD_MANAGER;
    private static final String DATA = LegacyLootDataBridge.DATA;
    private static final String TABLE = "Lnet/minecraft/world/level/storage/loot/LootTable;";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.21.1 target reads the table through the stand-in, which names ResourceLocation")
    void neoForge1211ReadsThroughTheStandIn() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyLootDataBridge.register(transformer);

        String standIn = new String(transformer.getSyntheticClasses().get(DATA), StandardCharsets.ISO_8859_1);
        assertTrue(standIn.contains("net/minecraft/resources/ResourceLocation"));
        assertFalse(standIn.contains("net/minecraft/resources/Identifier"));
        List<String> calls = calls(transformer.transformClass(lootReader("net/minecraft/resources/ResourceLocation"), "mod/Loot"));
        assertTrue(calls.contains(DATA + ".of(L" + SERVER + ";)L" + DATA + ";"), calls.toString());
        assertTrue(calls.contains(DATA + ".getLootTable(Lnet/minecraft/resources/ResourceLocation;)" + TABLE), calls.toString());
    }

    @Test
    @DisplayName("hosts from 1.21.11 keep the Identifier stand-in, and 1.20.4 keeps its own manager")
    void otherHosts() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyLootDataBridge.register(transformer);
        assertTrue(new String(transformer.getSyntheticClasses().get(DATA), StandardCharsets.ISO_8859_1)
                .contains("net/minecraft/resources/Identifier"));

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        LegacyLootDataBridge.register(transformer);
        assertNull(transformer.getSyntheticClasses().get(DATA));
    }

    @Test
    @DisplayName("on 1.21.1 the stand-in answers getElement, getKeys and new LootDataManager(), and reads a key's id with location()")
    void lookupsAndPlaceholderOn1211() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyLootDataBridge.register(transformer);
        String type = "Lnet/minecraft/world/level/storage/loot/LootDataType;";
        String id = "Lnet/minecraft/resources/ResourceLocation;";

        // DynamicTrees reads a sapling's table, curios lists the tables, PuzzlesLib keeps an empty manager in its event.
        List<String> calls = calls(transformer.transformClass(managerUser(type, id), "mod/LootUser"));
        assertTrue(calls.contains(DATA + ".getElement(" + type + id + ")Ljava/lang/Object;"), calls.toString());
        assertTrue(calls.contains(DATA + ".getKeys(" + type + ")Ljava/util/Collection;"), calls.toString());
        assertTrue(calls.contains(DATA + ".<init>()V"), calls.toString());

        ClassNode standIn = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(DATA)).accept(standIn, 0);
        for (String method : List.of("<init>()V", "getElement(" + type + id + ")Ljava/lang/Object;", "getKeys(" + type + ")Ljava/util/Collection;")) {
            assertTrue(standIn.methods.stream().anyMatch(m -> (m.name + m.desc).equals(method)), "the stand-in declares " + method);
        }
        String text = new String(transformer.getSyntheticClasses().get(DATA), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("location"), "a key's id is location() before 1.21.11");
        assertFalse(text.contains("identifier"), "1.21.1 has no identifier()");

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyLootDataBridge.register(transformer);
        assertTrue(new String(transformer.getSyntheticClasses().get(DATA), StandardCharsets.ISO_8859_1).contains("identifier"));
    }

    /** A 1.20.1 mod's {@code new LootDataManager()}, {@code getElement(type, id)} and {@code getKeys(type)}. */
    private static byte[] managerUser(String type, String id) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/LootUser", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use", "(L" + MANAGER + ";" + type + id + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, MANAGER, "getElement", "(" + type + id + ")Ljava/lang/Object;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, MANAGER, "getKeys", "(" + type + ")Ljava/util/Collection;", false);
        mv.visitInsn(POP);
        mv.visitTypeInsn(NEW, MANAGER);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, MANAGER, "<init>", "()V", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code server.getLootData().getLootTable(id)}, as both mods read a chest's or a mob's table. */
    private static byte[] lootReader(String id) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Loot", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "table", "(L" + SERVER + ";L" + id + ";)" + TABLE, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, SERVER, "getLootData", "()L" + MANAGER + ";", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, MANAGER, "getLootTable", "(L" + id + ";)" + TABLE, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
