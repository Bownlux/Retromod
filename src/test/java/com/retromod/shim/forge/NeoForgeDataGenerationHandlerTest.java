/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A translated mod's data generation subscriber is no longer subscribed, so its class never loads in a normal run. */
class NeoForgeDataGenerationHandlerTest {

    @Test
    @DisplayName("A subscriber with only a GatherDataEvent handler loses the handler and its subscriber annotation")
    void dataOnlySubscriberIsNotSubscribed() {
        ClassNode out = node(NeoForgeAutoSubscriberGuard.apply(subscriber("onGatherData", "Lnet/neoforged/neoforge/data/event/GatherDataEvent;")));
        assertNull(out.visibleAnnotations, "no @EventBusSubscriber left for NeoForge to load");
        assertTrue(out.methods.stream().noneMatch(m -> m.name.equals("onGatherData")));
    }

    @Test
    @DisplayName("Other handlers keep the class subscribed")
    void otherHandlersStay() {
        ClassNode out = node(NeoForgeAutoSubscriberGuard.apply(subscriber("onSetup", "Lnet/neoforged/fml/event/lifecycle/FMLCommonSetupEvent;")));
        assertEquals(List.of("Lnet/neoforged/fml/common/EventBusSubscriber;"), out.visibleAnnotations.stream().map(a -> a.desc).toList());
    }

    @Test
    @DisplayName("Forge: a subscriber with only a GatherDataEvent handler loses the handler and Mod.EventBusSubscriber")
    void forgeDataOnlySubscriberIsNotSubscribed() {
        for (String subscribe : List.of(FORGE_SUBSCRIBE_6, FORGE_SUBSCRIBE_7)) {
            ClassNode out = node(NeoForgeAutoSubscriberGuard.apply(subscriber(FORGE_SUBSCRIBER, subscribe,
                    "onGatherData", "Lnet/minecraftforge/data/event/GatherDataEvent;")));
            assertNull(out.visibleAnnotations, "no Mod.EventBusSubscriber left for Forge to load, with " + subscribe);
            assertTrue(out.methods.stream().noneMatch(m -> m.name.equals("onGatherData")));
        }
    }

    @Test
    @DisplayName("Forge: other handlers keep the class subscribed, and a class with no data handler is unchanged")
    void forgeOtherHandlersStay() {
        byte[] setup = subscriber(FORGE_SUBSCRIBER, FORGE_SUBSCRIBE_6, "onSetup",
                "Lnet/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent;");
        assertSame(setup, NeoForgeAutoSubscriberGuard.apply(setup));
    }

    @Test
    @DisplayName("A data handler another class of the mod calls or references is kept")
    void handlerReferencedFromAnotherClassStays() {
        byte[] handler = subscriber("Lnet/neoforged/fml/common/EventBusSubscriber;", "Lnet/neoforged/bus/api/SubscribeEvent;",
                "onGatherData", "Lnet/neoforged/neoforge/data/event/GatherDataEvent;");
        for (boolean methodReference : new boolean[] {false, true}) {
            var references = NeoForgeAutoSubscriberGuard.methodsReferencedAcrossClasses(
                    List.of(handler, caller("onGatherData", "Lnet/neoforged/neoforge/data/event/GatherDataEvent;", methodReference)));
            ClassNode out = node(NeoForgeAutoSubscriberGuard.apply(handler, name -> false, references::contains));
            assertTrue(out.methods.stream().anyMatch(m -> m.name.equals("onGatherData")),
                    methodReference ? "a method reference keeps it" : "a direct call keeps it");
        }
        var none = NeoForgeAutoSubscriberGuard.methodsReferencedAcrossClasses(List.of(handler));
        ClassNode out = node(NeoForgeAutoSubscriberGuard.apply(handler, name -> false, none::contains));
        assertTrue(out.methods.stream().noneMatch(m -> m.name.equals("onGatherData")));
    }

    /** {@code class mod.DataSetup}, which calls {@code Handler.name(null)} or makes a Consumer of it. */
    private static byte[] caller(String name, String event, boolean methodReference) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/DataSetup", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wire", "()V", null, null);
        mv.visitCode();
        if (methodReference) {
            Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;", false);
            mv.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory,
                    Type.getType("(Ljava/lang/Object;)V"),
                    new Handle(Opcodes.H_INVOKESTATIC, "mod/Handler", name, "(" + event + ")V", false),
                    Type.getType("(" + event + ")V"));
            mv.visitInsn(Opcodes.POP);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "mod/Handler", name, "(" + event + ")V", false);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String FORGE_SUBSCRIBER = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
    private static final String FORGE_SUBSCRIBE_6 = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String FORGE_SUBSCRIBE_7 = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";

    /** {@code @EventBusSubscriber class mod.Handler { @SubscribeEvent static void name(event) {} }}. */
    private static byte[] subscriber(String name, String event) {
        return subscriber("Lnet/neoforged/fml/common/EventBusSubscriber;", "Lnet/neoforged/bus/api/SubscribeEvent;", name, event);
    }

    /** A subscriber class with the given annotations and one static handler. */
    private static byte[] subscriber(String annotation, String subscribe, String name, String event) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Handler", null, "java/lang/Object", null);
        cw.visitAnnotation(annotation, true).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(" + event + ")V", null, null);
        mv.visitAnnotation(subscribe, true).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
