/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ChestBlock}'s constructor moved its properties last in 1.21.2 and gained the open and close
 * sounds in 1.21.9. Bountiful Fares' ceramic chest still calls the 1.21.1 {@code super(props,
 * supplier)}, and the {@code NoSuchMethodError} stopped its block registration on 26.1.2.
 */
class LegacyChestBlockBridgeTest {

    private static final String MOD_CHEST = "test/mod/CeramicChestBlock";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.21.1 chest gets the supplier first, then the vanilla sounds, then the properties")
    void legacyChestOnSoundHost() {
        LegacyChestBlockBridge.register(transformer, "26.1.2");
        MethodNode ctor = transformedCtor(LegacyChestBlockBridge.PROPS_FIRST_DESC,
                LegacyChestBlockBridge.PROPS_FIRST_DESC);

        Call call = superCall(ctor);
        assertEquals(LegacyChestBlockBridge.WITH_SOUNDS_DESC, call.insn.desc,
                "26.1.2 only declares the four-argument chest constructor");
        assertEquals(List.of("this", "arg2", "CHEST_OPEN", "CHEST_CLOSE", "arg1"), call.sources,
                "the supplier is the mod's second argument and the properties its first");
    }

    @Test
    @DisplayName("a 1.21.2 chest gets the vanilla sounds slid in before the properties")
    void supplierFirstChestOnSoundHost() {
        LegacyChestBlockBridge.register(transformer, "26.1.2");
        MethodNode ctor = transformedCtor(LegacyChestBlockBridge.SUPPLIER_FIRST_DESC,
                LegacyChestBlockBridge.SUPPLIER_FIRST_DESC);

        Call call = superCall(ctor);
        assertEquals(LegacyChestBlockBridge.WITH_SOUNDS_DESC, call.insn.desc);
        assertEquals(List.of("this", "arg1", "CHEST_OPEN", "CHEST_CLOSE", "arg2"), call.sources);
    }

    @Test
    @DisplayName("a 1.21.1 chest on a 1.21.4 host only swaps its two arguments")
    void legacyChestBeforeSounds() {
        LegacyChestBlockBridge.register(transformer, "1.21.4");
        MethodNode ctor = transformedCtor(LegacyChestBlockBridge.PROPS_FIRST_DESC,
                LegacyChestBlockBridge.PROPS_FIRST_DESC);

        Call call = superCall(ctor);
        assertEquals(LegacyChestBlockBridge.SUPPLIER_FIRST_DESC, call.insn.desc,
                "1.21.2 to 1.21.8 declare (Supplier, Properties) and have no chest sounds");
        assertEquals(List.of("this", "arg2", "arg1"), call.sources);
    }

    @Test
    @DisplayName("a chest built for the current shape passes through")
    void modernChestUnchanged() {
        LegacyChestBlockBridge.register(transformer, "26.1.2");
        String modern = "(Ljava/util/function/Supplier;Lnet/minecraft/sounds/SoundEvent;"
                + "Lnet/minecraft/sounds/SoundEvent;"
                + "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V";
        MethodNode ctor = transformedCtor(modern, modern);

        Call call = superCall(ctor);
        assertEquals(modern, call.insn.desc);
        assertEquals(List.of("this", "arg1", "arg2", "arg3", "arg4"), call.sources);
    }

    private record Call(MethodInsnNode insn, List<String> sources) {}

    /**
     * The mod's {@code super(...)} call and where each of its arguments came from, found by
     * replaying the straight-line constructor's loads, static reads and swaps.
     */
    private static Call superCall(MethodNode ctor) {
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof VarInsnNode v) {
                stack.push(v.var == 0 ? "this" : "arg" + v.var);
            } else if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
                stack.push(f.name);
            } else if (insn.getOpcode() == Opcodes.SWAP) {
                String top = stack.pop();
                String below = stack.pop();
                stack.push(top);
                stack.push(below);
            } else if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) {
                assertEquals(LegacyChestBlockBridge.CHEST, call.owner);
                List<String> sources = new ArrayList<>(stack);
                java.util.Collections.reverse(sources);
                return new Call(call, sources);
            }
        }
        fail("the constructor must still call super(...)");
        return null;
    }

    private MethodNode transformedCtor(String ownDesc, String superDesc) {
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(chestSubclass(ownDesc, superDesc), MOD_CHEST))
                .accept(out, 0);
        return out.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
    }

    /** {@code class CeramicChestBlock extends ChestBlock { <init>(ownDesc) { super(args...); } }} */
    private static byte[] chestSubclass(String ownDesc, String superDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CHEST, null,
                LegacyChestBlockBridge.CHEST, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ownDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        int argCount = org.objectweb.asm.Type.getArgumentTypes(ownDesc).length;
        for (int i = 1; i <= argCount; i++) mv.visitVarInsn(Opcodes.ALOAD, i);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyChestBlockBridge.CHEST, "<init>",
                superDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
