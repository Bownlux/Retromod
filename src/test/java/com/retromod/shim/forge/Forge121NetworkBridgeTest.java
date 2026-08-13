/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Forge121NetworkBridgeTest implements Opcodes {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void reset() {
        transformer.clearRedirectsForTesting();
        Forge_1_20_to_NeoForge_1_21.registerForgeOfficialNetworkBridge(transformer);
    }

    @Test
    @DisplayName("#204: event-object bridge is excluded from Forge 26.2")
    void bridgeIsLimitedToItsVerifiedForgeSurface() {
        assertTrue(Forge_1_20_to_NeoForge_1_21
                .supportsForgeOfficialNetworkBridge("1.20.6"));
        assertTrue(Forge_1_20_to_NeoForge_1_21
                .supportsForgeOfficialNetworkBridge("1.21.11"));
        assertFalse(Forge_1_20_to_NeoForge_1_21
                .supportsForgeOfficialNetworkBridge("1.20.1"));
        assertFalse(Forge_1_20_to_NeoForge_1_21
                .supportsForgeOfficialNetworkBridge("26.2"));
    }

    @Test
    @DisplayName("#204: nested Forge channel builder becomes the official-name bridge")
    void repairsLegacyChannelBuilderChain() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "fixture/CofhChannel", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "open",
                "(Lnet/minecraft/resources/ResourceLocation;)V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESTATIC,
                "net/minecraftforge/network/NetworkRegistry$ChannelBuilder", "named",
                "(Lnet/minecraft/resources/ResourceLocation;)"
                        + "Lnet/minecraftforge/network/NetworkRegistry$ChannelBuilder;", false);
        method.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraftforge/network/NetworkRegistry$ChannelBuilder", "eventNetworkChannel",
                "()Lnet/minecraftforge/network/event/EventNetworkChannel;", false);
        method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraftforge/network/event/EventNetworkChannel", "registerObject",
                "(Ljava/lang/Object;)V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();

        ClassNode node = read(transformer.transformClass(writer.toByteArray(), "fixture/CofhChannel"));
        MethodNode transformed = node.methods.stream()
                .filter(candidate -> candidate.name.equals("open"))
                .findFirst().orElseThrow();
        AtomicBoolean named = new AtomicBoolean();
        AtomicBoolean eventChannel = new AtomicBoolean();
        AtomicBoolean discardedNewReturn = new AtomicBoolean();
        for (AbstractInsnNode instruction : transformed.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.contains("LegacyForgeChannelBuilder") && call.name.equals("named")
                        && call.desc.startsWith("(Ljava/lang/Object;)")) {
                    named.set(true);
                }
                if (call.owner.equals("net/minecraftforge/network/EventNetworkChannel")
                        && call.name.equals("registerObject")
                        && call.desc.endsWith("Lnet/minecraftforge/network/EventNetworkChannel;")) {
                    eventChannel.set(true);
                    discardedNewReturn.set(call.getNext() != null && call.getNext().getOpcode() == POP);
                }
            }
        }
        assertTrue(named.get());
        assertTrue(eventChannel.get());
        assertTrue(discardedNewReturn.get());
    }

    @Test
    @DisplayName("#204: Forge 1.20.1 simple-channel descriptor reaches the bridge")
    void repairsLegacySimpleChannelDescriptor() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "fixture/SimpleChannel", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "open",
                "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESTATIC,
                "net/minecraftforge/network/NetworkRegistry$ChannelBuilder", "named",
                "(Lnet/minecraft/resources/ResourceLocation;)"
                        + "Lnet/minecraftforge/network/NetworkRegistry$ChannelBuilder;", false);
        method.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraftforge/network/NetworkRegistry$ChannelBuilder", "simpleChannel",
                "()Lnet/minecraftforge/network/simple/SimpleChannel;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();

        ClassNode node = read(transformer.transformClass(
                writer.toByteArray(), "fixture/SimpleChannel"));
        MethodNode transformed = node.methods.stream()
                .filter(candidate -> candidate.name.equals("open"))
                .findFirst().orElseThrow();

        assertTrue(java.util.Arrays.stream(transformed.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.owner.contains("LegacyForgeChannelBuilder")
                        && call.name.equals("simpleChannel")
                        && call.desc.equals("()Ljava/lang/Object;")));
        assertFalse(java.util.Arrays.stream(transformed.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.desc.contains(
                        "net/minecraftforge/network/simple/SimpleChannel")));
    }

    @Test
    @DisplayName("#204: old payload event context uses the current custom-payload context")
    void repairsLegacyPayloadContext() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "fixture/CofhPayload", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "onPayload",
                "(Lnet/minecraftforge/network/NetworkEvent$ClientCustomPayloadEvent;)V",
                null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraftforge/network/NetworkEvent$ClientCustomPayloadEvent", "getSource",
                "()Ljava/util/function/Supplier;", false);
        method.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get",
                "()Ljava/lang/Object;", true);
        method.visitTypeInsn(CHECKCAST, "net/minecraftforge/network/NetworkEvent$Context");
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();
        writer.visitEnd();

        ClassNode node = read(transformer.transformClass(writer.toByteArray(), "fixture/CofhPayload"));
        MethodNode transformed = node.methods.stream()
                .filter(candidate -> candidate.name.equals("onPayload"))
                .findFirst().orElseThrow();
        assertTrue(transformed.desc.contains(
                "net/minecraftforge/event/network/CustomPayloadEvent"), transformed.desc);
        boolean sourceAdapter = false;
        boolean currentContext = false;
        boolean oldContext = false;
        for (AbstractInsnNode instruction : transformed.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.contains("LegacyForgeNetworkEventAdapter")
                    && call.name.equals("getSource")) {
                sourceAdapter = true;
            }
            if (instruction instanceof TypeInsnNode type) {
                currentContext |= type.desc.equals(
                        "net/minecraftforge/event/network/CustomPayloadEvent$Context");
                oldContext |= type.desc.equals(
                        "net/minecraftforge/network/NetworkEvent$Context");
            }
        }
        assertTrue(sourceAdapter);
        assertTrue(currentContext);
        assertFalse(oldContext);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
