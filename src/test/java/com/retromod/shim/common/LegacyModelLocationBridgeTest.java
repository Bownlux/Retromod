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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.21.1 mod's model locations and model lookups reach the rebuilt helpers on a 26.x host. */
class LegacyModelLocationBridgeTest {

    private static final String MRL = LegacyModelLocationBridge.OLD_LOCATION;
    private static final String MANAGER = LegacyModelLocationBridge.MODEL_MANAGER;
    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String BAKED = "Lnet/minecraft/client/resources/model/BakedModel;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("locations are built by the helpers and getModel and getMissingModel read block state models")
    void locationsAndLookupsAreRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyModelLocationBridge.registerLocations(t);
        LegacyModelLocationBridge.registerLookups(t);

        byte[] out = t.transformClass(oldModelCode(), "test/model/OldModels");
        List<String> calls = calls(out);

        String locations = LegacyModelLocationBridge.LOCATIONS;
        String lookups = LegacyModelLocationBridge.LOOKUPS;
        assertTrue(calls.contains(locations + ".of(" + ID + "Ljava/lang/String;)" + ID), calls.toString());
        assertTrue(calls.contains(locations + ".inventory(" + ID + ")" + ID), calls.toString());
        assertTrue(calls.contains(locations + ".id(" + ID + ")" + ID), calls.toString());
        String blockModel = LegacyModelLocationBridge.BLOCK_STATE_MODEL;
        assertTrue(calls.contains(lookups + ".getModel(L" + MANAGER + ";" + ID + ")" + blockModel), calls.toString());
        assertTrue(calls.contains(lookups + ".getMissingModel(L" + MANAGER + ";)" + blockModel), calls.toString());
        assertFalse(calls.stream().anyMatch(call -> call.startsWith(MRL + ".") || call.startsWith(MANAGER + ".")),
                "no call may stay on the old location or the removed lookups: " + calls);
        assertFalse(names(out).contains(MRL), "the old location type must not survive anywhere in the class");
    }

    @Test
    @DisplayName("a Fabric mod's id() and variant(), named by their intermediary components, reach the helpers")
    void fabricRecordComponentsAreRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyModelLocationBridge.registerLocations(t);

        byte[] out = t.transformClass(fabricComponentCalls(), "test/model/FabricModels");
        List<String> calls = calls(out);

        String locations = LegacyModelLocationBridge.LOCATIONS;
        assertTrue(calls.contains(locations + ".id(" + ID + ")" + ID), calls.toString());
        assertTrue(calls.contains(locations + ".getVariant(" + ID + ")Ljava/lang/String;"), calls.toString());
        assertFalse(calls.stream().anyMatch(call -> call.contains("comp_287")),
                "no component call may reach Identifier, which has none: " + calls);
    }

    @Test
    @DisplayName("the bridge registers only where the old location is gone and the model set has missingModel")
    void hostGate() {
        ClassNode modern = new ClassNode();
        modern.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "missingModel", "()" + LegacyModelLocationBridge.BLOCK_STATE_MODEL,
                null, null));
        assertTrue(LegacyModelLocationBridge.hostMatches(null, modern, true));
        assertFalse(LegacyModelLocationBridge.hostMatches(new ClassNode(), modern, true), "a host with the old location keeps it");
        assertFalse(LegacyModelLocationBridge.hostMatches(null, null, true), "1.21.11 has no BlockStateModelSet");
        assertFalse(LegacyModelLocationBridge.hostMatches(null, new ClassNode(), true));
    }

    /** What a NeoForge 1.21.1 model helper does: build locations, read the id, look models up. */
    private static byte[] oldModelCode() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/model/OldModels", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(L" + MANAGER + ";" + ID + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, MRL);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("facing=north");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MRL, "<init>", "(" + ID + "Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MANAGER, "getModel", "(L" + MRL + ";)" + BAKED, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MRL, "inventory", "(" + ID + ")L" + MRL + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MRL, "id", "()" + ID, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MANAGER, "getMissingModel", "()" + BAKED, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 3);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A Fabric 1.21.1 mod after the remap: the location's class has its Mojang name, its components keep theirs. */
    private static byte[] fabricComponentCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/model/FabricModels", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + MRL + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MRL, "comp_2875", "()" + ID, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MRL, "comp_2876", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static List<String> names(byte[] bytes) {
        List<String> names = new ArrayList<>(calls(bytes));
        for (MethodNode method : node(bytes).methods) {
            names.add(method.desc);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type) names.add(type.desc);
            }
        }
        return names;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
