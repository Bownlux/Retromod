/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Mythic Fantasies, a 1.19.2 Forge mod, on NeoForge 26.3: it added a cleric trade and two wandering
 * trader trades through events 26.x removed, so the trades were gone and, before that, every event
 * subscription of the mod failed.
 */
class LegacyTradeBridgeTest {

    private static final String HANDLER = "mod/ModTrades";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the old events, listing, and BasicItemListing become generated classes")
    void oldTradeTypesAreRedirected() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyTradeBridge.registerRedirects(transformer);

        ClassNode node = read(transformer.transformClass(forgeHandler(), HANDLER + ".class"));

        MethodNode handler = node.methods.stream().filter(m -> m.name.equals("registerTrades")).findFirst().orElseThrow();
        assertEquals("(L" + LegacyTradeBridge.VILLAGER_EVENT + ";)V", handler.desc);
        assertTrue(calls(handler).contains(LegacyTradeBridge.BASIC_LISTING + ".<init>"),
                "BasicItemListing must be the generated listing the store can read");
    }

    @Test
    @DisplayName("a class with a trade handler starts the store when it initializes; other classes are untouched")
    void handlerClassStartsTheStore() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyTradeBridge.registerRedirects(transformer);
        byte[] redirected = transformer.transformClass(forgeHandler(), HANDLER + ".class");

        ClassNode node = read(LegacyTradeBridge.watchHandlers(redirected));
        MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        assertEquals(LegacyTradeBridge.STORE + ".watch", calls(clinit).get(0));

        byte[] plain = forgeHandler();
        assertSame(plain, LegacyTradeBridge.watchHandlers(plain), "nothing names the generated event yet");
    }

    @Test
    @DisplayName("the generated classes verify, and the event answers getType both as a profession and as a key")
    void generatedClassesVerify() throws Exception {
        for (byte[] generated : List.of(LegacyTradeBridge.generateItemListing(), LegacyTradeBridge.generateBasicListing(),
                LegacyTradeBridge.generateVillagerEvent(), LegacyTradeBridge.generateWandererEvent(),
                LegacyTradeBridge.generateProfessions())) {
            ClassNode node = read(generated);
            for (MethodNode method : node.methods) {
                if ((method.access & ACC_ABSTRACT) == 0) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            }
        }
        List<String> getTypes = read(LegacyTradeBridge.generateVillagerEvent()).methods.stream()
                .filter(m -> m.name.equals("getType")).map(m -> m.desc).toList();
        assertEquals(List.of("()Lnet/minecraft/world/entity/npc/villager/VillagerProfession;",
                "()Lnet/minecraft/resources/ResourceKey;"), getTypes);
    }

    /** {@code registerTrades(VillagerTradesEvent)}: adds one BasicItemListing to level 5. */
    private static byte[] forgeHandler() {
        String event = "net/minecraftforge/event/village/VillagerTradesEvent";
        String listing = "net/minecraftforge/common/BasicItemListing";
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, HANDLER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerTrades", "(L" + event + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, event, "getTrades", "()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", false);
        mv.visitInsn(ICONST_5);
        mv.visitMethodInsn(INVOKEINTERFACE, "it/unimi/dsi/fastutil/ints/Int2ObjectMap", "get", "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "java/util/List");
        mv.visitTypeInsn(NEW, listing);
        mv.visitInsn(DUP);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitIntInsn(BIPUSH, 10);
        mv.visitInsn(ICONST_5);
        mv.visitLdcInsn(0.05F);
        mv.visitMethodInsn(INVOKESPECIAL, listing, "<init>", "(" + stack + stack + "IIF)V", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
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

    private static List<String> calls(MethodNode method) {
        List<String> calls = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }
}
