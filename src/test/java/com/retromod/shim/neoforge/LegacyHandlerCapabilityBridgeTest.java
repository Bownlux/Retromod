/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #366: ProjectE PE1.1.0 (NeoForge 1.21.1) registers and looks up item handlers through
 * {@code Capabilities.ItemHandler}, which NeoForge 26.1.2 removed, and stopped at construction on
 * NeoForge 26.2 with {@code NoClassDefFoundError}.
 */
class LegacyHandlerCapabilityBridgeTest {

    private static final String CAPS = "net/neoforged/neoforge/capabilities/";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Old capabilities, their registration, and entity and level lookups reach the bridge")
    void oldCapabilityUseIsBridged() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHandlerCapabilityBridge.register(transformer, true);

        ClassNode machine = read(transformer.transformClass(machine(), "mod/Machine"));

        List<String> owners = fieldOwners(machine);
        assertTrue(owners.contains(LegacyHandlerCapabilityBridge.ITEM_CAPS), owners.toString());
        List<String> calls = calls(machine);
        assertTrue(calls.contains(LegacyHandlerCapabilityBridge.CALLS + ".registerBlockEntity"), calls.toString());
        assertTrue(calls.contains(LegacyHandlerCapabilityBridge.CALLS + ".entityCapability"), calls.toString());
        assertTrue(calls.contains(LegacyHandlerCapabilityBridge.CALLS + ".levelCapability"), calls.toString());
    }

    @Test
    @DisplayName("A host that still has the handler capabilities is left alone")
    void olderHostsAreLeftAlone() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHandlerCapabilityBridge.register(transformer, false);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyHandlerCapabilityBridge.CALLS));
    }

    @Test
    @DisplayName("The generated classes verify")
    void generatedClassesVerify() {
        for (byte[] generated : List.of(LegacyHandlerCapabilityBridge.generateCalls(),
                LegacyHandlerCapabilityBridge.generateAdapter(LegacyHandlerCapabilityBridge.ITEM_ADAPTER, "item"),
                LegacyHandlerCapabilityBridge.generateAdapter(LegacyHandlerCapabilityBridge.FLUID_ADAPTER, "fluid"),
                LegacyHandlerCapabilityBridge.generateAdapter(LegacyHandlerCapabilityBridge.ENERGY_ADAPTER, "energy"),
                LegacyHandlerCapabilityBridge.generateCapabilities(LegacyHandlerCapabilityBridge.ENERGY_CAPS, "energy",
                        List.of("BLOCK", "ENTITY", "ITEM")),
                LegacyHandlerCapabilityBridge.generateCapabilities(LegacyHandlerCapabilityBridge.ITEM_CAPS, "item",
                        List.of("BLOCK", "ENTITY", "ENTITY_AUTOMATION", "ITEM")))) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    @Test
    @DisplayName("The old energy capability is bridged and its adapter is a current energy handler")
    void oldEnergyCapabilityIsBridged() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHandlerCapabilityBridge.register(transformer, true);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Generator", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "cap", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, CAPS + "Capabilities$EnergyStorage", "BLOCK", "L" + CAPS + "BlockCapability;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        List<String> owners = fieldOwners(read(transformer.transformClass(cw.toByteArray(), "mod/Generator")));
        assertEquals(List.of(LegacyHandlerCapabilityBridge.ENERGY_CAPS), owners);

        ClassNode adapter = read(transformer.getSyntheticClasses().get(LegacyHandlerCapabilityBridge.ENERGY_ADAPTER));
        assertEquals(List.of("net/neoforged/neoforge/transfer/energy/EnergyHandler"), adapter.interfaces);
        List<String> methods = new ArrayList<>();
        for (MethodNode method : adapter.methods) methods.add(method.name + method.desc);
        String transaction = "Lnet/neoforged/neoforge/transfer/transaction/TransactionContext;";
        for (String expected : List.of("getAmountAsLong()J", "getCapacityAsLong()J", "insert(I" + transaction + ")I",
                "extract(I" + transaction + ")I")) {
            assertTrue(methods.contains(expected), "the energy adapter lacks " + expected + ": " + methods);
        }
    }

    /** A block entity registration, a player's item handler, and a neighbour's, as ProjectE writes them. */
    private static byte[] machine() {
        String blockCap = "L" + CAPS + "BlockCapability;";
        String entityCap = "L" + CAPS + "EntityCapability;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Machine", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(L" + CAPS + "RegisterCapabilitiesEvent;"
                + "Lnet/minecraft/world/level/block/entity/BlockEntityType;L" + CAPS + "ICapabilityProvider;"
                + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, CAPS + "Capabilities$ItemHandler", "BLOCK", blockCap);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, CAPS + "RegisterCapabilitiesEvent", "registerBlockEntity",
                "(" + blockCap + "Lnet/minecraft/world/level/block/entity/BlockEntityType;L" + CAPS + "ICapabilityProvider;)V", false);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitFieldInsn(GETSTATIC, CAPS + "Capabilities$ItemHandler", "ENTITY", entityCap);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/entity/player/Player", "getCapability", "(" + entityCap + ")Ljava/lang/Object;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitFieldInsn(GETSTATIC, CAPS + "Capabilities$ItemHandler", "BLOCK", blockCap);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/Level", "getCapability",
                "(" + blockCap + "Lnet/minecraft/core/BlockPos;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static List<String> fieldOwners(ClassNode node) {
        List<String> owners = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) owners.add(field.owner);
            }
        }
        return owners;
    }
}
