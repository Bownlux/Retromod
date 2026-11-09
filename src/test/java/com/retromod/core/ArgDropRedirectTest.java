/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Arg-dropping method redirect (§B, Chunky). When a method was renamed AND lost a trailing
 * parameter (1.21.5 `ServerChunkCache.addRegionTicket(TicketType,ChunkPos,int,T)` ->
 * `addTicketWithRadius(TicketType,ChunkPos,int)`), the call must pop the dropped value and invoke
 * the shorter method. A plain rename can't do this (descriptor mismatch).
 */
class ArgDropRedirectTest {

    private static final String SCC = "net/minecraft/server/level/ServerChunkCache";
    private static final String OLD_DESC =
            "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V";
    private static final String NEW_DESC =
            "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("addRegionTicket(4 args) -> POP + addTicketWithRadius(3 args)")
    void dropsTrailingArgAndRenames() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        t.registerArgDropMethodRedirect(SCC, "addRegionTicket", OLD_DESC, SCC, "addTicketWithRadius", NEW_DESC);

        byte[] out = t.transformClass(callerClass(), "test/ChunkCaller");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodNode go = cn.methods.stream().filter(m -> m.name.equals("go")).findFirst().orElseThrow();
        AbstractInsnNode[] insns = go.instructions.toArray();

        MethodInsnNode call = Arrays.stream(insns)
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .filter(mi -> mi.owner.equals(SCC)).findFirst().orElseThrow();
        assertEquals("addTicketWithRadius", call.name, "renamed to the 3-arg method");
        assertEquals(NEW_DESC, call.desc, "uses the 3-arg descriptor");
        assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(), "still a virtual call");

        // a POP must precede the call to discard the dropped Object value
        long pops = Arrays.stream(insns)
                .filter(i -> i instanceof InsnNode && i.getOpcode() == Opcodes.POP).count();
        assertTrue(pops >= 1, "the dropped trailing value must be popped");

        assertFalse(Arrays.stream(insns).anyMatch(i -> i instanceof MethodInsnNode
                && ((MethodInsnNode) i).name.equals("addRegionTicket")), "no leftover addRegionTicket");
    }

    @Test
    @DisplayName("removeRegionTicket(4 args) -> one POP + removeTicketWithRadius(3 args)")
    void releasingATicketDropsTheValue() throws Exception {
        MethodNode go = transformedTicketCall("removeRegionTicket", OLD_DESC);

        assertEquals("removeTicketWithRadius " + NEW_DESC, ticketCall(go));
        assertEquals(1, pops(go), "only the dropped value is popped");
        new Analyzer<>(new BasicVerifier()).analyze("test/ChunkCaller", go);
    }

    @Test
    @DisplayName("the Forge and NeoForge (..., value, forceTicks) forms pop both trailing values")
    void forceTicksOverloadsPopTwoValues() throws Exception {
        String forceTicks = OLD_DESC.replace(")V", "Z)V");
        for (String[] shape : new String[][]{{"addRegionTicket", "addTicketWithRadius"},
                {"removeRegionTicket", "removeTicketWithRadius"}}) {
            MethodNode go = transformedTicketCall(shape[0], forceTicks);

            assertEquals(shape[1] + " " + NEW_DESC, ticketCall(go));
            assertEquals(2, pops(go), "the value and the forceTicks flag are both dropped");
            new Analyzer<>(new BasicVerifier()).analyze("test/ChunkCaller", go);
        }
    }

    /** The {@code go} method after a caller of {@code name(desc)} runs through the ticket bridge. */
    private static MethodNode transformedTicketCall(String name, String desc) {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        com.retromod.shim.common.LegacyChunkTicketBridge.register(t);
        ClassNode cn = new ClassNode();
        new ClassReader(t.transformClass(callerClass(name, desc), "test/ChunkCaller")).accept(cn, 0);
        MethodNode go = cn.methods.stream().filter(m -> m.name.equals("go")).findFirst().orElseThrow();
        assertFalse(Arrays.stream(go.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                && call.name.endsWith("RegionTicket")), "no *RegionTicket call may be left");
        return go;
    }

    private static String ticketCall(MethodNode go) {
        return Arrays.stream(go.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode call && call.owner.equals(SCC))
                .map(i -> ((MethodInsnNode) i).name + " " + ((MethodInsnNode) i).desc)
                .findFirst().orElseThrow();
    }

    private static long pops(MethodNode go) {
        return Arrays.stream(go.instructions.toArray())
                .filter(i -> i instanceof InsnNode && i.getOpcode() == Opcodes.POP).count();
    }

    private static byte[] callerClass() {
        return callerClass("addRegionTicket", OLD_DESC);
    }

    /** {@code static void go(cache, type, pos, radius, value)} calling the old 4-arg addRegionTicket. */
    private static byte[] callerClass(String name, String desc) {
        boolean forceTicks = desc.endsWith("Z)V");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ChunkCaller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go",
                "(L" + SCC + ";Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0); // cache (receiver)
        mv.visitVarInsn(Opcodes.ALOAD, 1); // TicketType
        mv.visitVarInsn(Opcodes.ALOAD, 2); // ChunkPos
        mv.visitVarInsn(Opcodes.ILOAD, 3); // int radius
        mv.visitVarInsn(Opcodes.ALOAD, 4); // Object value (dropped)
        if (forceTicks) mv.visitInsn(Opcodes.ICONST_1); // boolean forceTicks (dropped)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SCC, name, desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
