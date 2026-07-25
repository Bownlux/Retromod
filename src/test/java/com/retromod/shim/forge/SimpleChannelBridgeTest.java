/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #156 (Wonderland, the MCreator 1.20.1 scaffold on a NeoForge host): the old bare class
 * redirects (NetworkRegistry -> PayloadRegistrar) sent {@code newSimpleChannel} at a method that
 * has never existed on NeoForge, a guaranteed {@code NoSuchMethodError} in {@code <clinit>}. The
 * SimpleChannel surface now routes onto the embedded {@code NetworkShim} (mod loads; packet
 * registrations are collected; cross-side delivery stays inert until the replay bridge lands,
 * tracked 1.3.0 Forge-to-NeoForge work).
 */
class SimpleChannelBridgeTest {

    private static final String SHIM = "com/retromod/shim/forge/embedded/NetworkShim";
    private static final String WRAPPER = SHIM + "$SimpleChannelWrapper";
    private static final String BUILDER = SHIM + "$MessageBuilder";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Forge_1_20_to_NeoForge_1_21.registerNetworkBridge(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("newSimpleChannel devirtualizes onto the shim with the Object-erased descriptor")
    void newSimpleChannelBridged() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/WonderMod", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "boot",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/util/function/Supplier;"
                        + "Ljava/util/function/Predicate;Ljava/util/function/Predicate;)"
                        + "Lnet/minecraftforge/network/simple/SimpleChannel;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2); mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/network/NetworkRegistry",
                "newSimpleChannel",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/util/function/Supplier;"
                        + "Ljava/util/function/Predicate;Ljava/util/function/Predicate;)"
                        + "Lnet/minecraftforge/network/simple/SimpleChannel;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/WonderMod");
        ClassNode cn = new ClassNode();
        assertDoesNotThrow(() -> new ClassReader(out).accept(cn, 0));
        boolean bridged = false;
        for (MethodNode m : cn.methods) {
            for (var i : m.instructions.toArray()) {
                if (i instanceof MethodInsnNode mi && mi.owner.equals(SHIM)
                        && mi.name.equals("newSimpleChannel")) {
                    bridged = true;
                    assertEquals("(Ljava/lang/Object;Ljava/util/function/Supplier;"
                            + "Ljava/util/function/Predicate;Ljava/util/function/Predicate;)"
                            + "Ljava/lang/Object;", mi.desc,
                            "the call must use the shim's erased descriptor");
                }
                assertFalse(i instanceof MethodInsnNode mi2
                                && mi2.owner.contains("PayloadRegistrar"),
                        "the old harmful PayloadRegistrar retarget must be gone");
            }
        }
        assertTrue(bridged, "newSimpleChannel must route to the embedded NetworkShim");
    }

    @Test
    @DisplayName("the MCreator messageBuilder chain retypes onto the shim wrapper/builder")
    void messageBuilderChainBridged() {
        // channel.messageBuilder(C.class, 0, NetworkDirection.PLAY_TO_SERVER)
        //        .encoder(..).decoder(..).consumerMainThread(..).add()
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/WonderNet", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "reg",
                "(Lnet/minecraftforge/network/simple/SimpleChannel;Ljava/lang/Class;"
                        + "Lnet/minecraftforge/network/NetworkDirection;"
                        + "Ljava/util/function/BiConsumer;Ljava/util/function/Function;)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraftforge/network/simple/SimpleChannel",
                "messageBuilder",
                "(Ljava/lang/Class;ILnet/minecraftforge/network/NetworkDirection;)"
                        + "Lnet/minecraftforge/network/simple/SimpleChannel$MessageBuilder;", false);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraftforge/network/simple/SimpleChannel$MessageBuilder", "encoder",
                "(Ljava/util/function/BiConsumer;)"
                        + "Lnet/minecraftforge/network/simple/SimpleChannel$MessageBuilder;", false);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraftforge/network/simple/SimpleChannel$MessageBuilder", "decoder",
                "(Ljava/util/function/Function;)"
                        + "Lnet/minecraftforge/network/simple/SimpleChannel$MessageBuilder;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraftforge/network/simple/SimpleChannel$MessageBuilder", "add", "()V",
                false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/WonderNet");
        ClassNode cn = new ClassNode();
        assertDoesNotThrow(() -> new ClassReader(out).accept(cn, 0));
        boolean builderCall = false, encoderOnShim = false, addOnShim = false;
        for (MethodNode m : cn.methods) {
            for (var i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode mi)) continue;
                if (mi.owner.equals(WRAPPER) && mi.name.equals("messageBuilder")) {
                    builderCall = true;
                    assertEquals("(Ljava/lang/Class;ILjava/lang/Object;)L" + BUILDER + ";", mi.desc,
                            "the direction-qualified overload must erase the NetworkDirection");
                }
                if (mi.owner.equals(BUILDER) && mi.name.equals("encoder")) encoderOnShim = true;
                if (mi.owner.equals(BUILDER) && mi.name.equals("add")) addOnShim = true;
            }
        }
        assertTrue(builderCall, "messageBuilder must land on the shim wrapper");
        assertTrue(encoderOnShim && addOnShim, "the fluent chain must retype onto the shim builder");
    }

    @Test
    @DisplayName("the shim inner classes are all listed for embedding")
    void innerClassesListed() {
        var listed = java.util.Set.of(new Forge_1_20_to_NeoForge_1_21().getShimClasses());
        for (String required : new String[]{
                "com.retromod.shim.forge.embedded.NetworkShim",
                "com.retromod.shim.forge.embedded.NetworkShim$SimpleChannelWrapper",
                "com.retromod.shim.forge.embedded.NetworkShim$MessageBuilder",
                "com.retromod.shim.forge.embedded.NetworkShim$PacketRegistration"}) {
            assertTrue(listed.contains(required), "must embed " + required);
        }
    }
}
