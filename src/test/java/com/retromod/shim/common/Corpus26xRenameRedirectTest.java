/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Corpus-mined 26.x vanilla method renames (from a linkcheck audit of the top-40 NeoForge 1.21.1
 * mods): {@code Minecraft.getTimer -> getDeltaTracker}, {@code Camera.getPosition -> position} and
 * {@code VertexConsumer/BufferBuilder.addVertex(Matrix4f..)} JOML-const widening (all landed by 26.1),
 * plus {@code GameRenderer.getMainCamera -> mainCamera} (26.2). Each is owner+descriptor-scoped, so a
 * generic name only rewrites the one intended overload. Drives the real shim registration and asserts
 * the call sites are rewritten.
 */
public class Corpus26xRenameRedirectTest {

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

    /** A class with one static method that makes a single call {@code owner.name desc} (given opcode). */
    private static byte[] caller(int opcode, String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        // static void call(owner recv, <args...>) { recv.name(<args>); } - we only need the call site.
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "(L" + owner + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        // push dummy args per the descriptor (only object/float args appear here)
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
        for (org.objectweb.asm.Type a : args) {
            if (a.getSort() == org.objectweb.asm.Type.FLOAT) mv.visitInsn(FCONST_0);
            else mv.visitInsn(ACONST_NULL);
        }
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == INVOKEINTERFACE);
        // pop any return value
        if (!org.objectweb.asm.Type.getReturnType(desc).equals(org.objectweb.asm.Type.VOID_TYPE)) mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private MethodInsnNode redirectedCall(byte[] in, String owner) {
        byte[] out = transformer.transformClass(in, "test/Caller");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode mi && mi.owner.equals(owner)) return mi;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Minecraft.getTimer() -> getDeltaTracker(); Camera.getPosition() -> position() (26.1)")
    void accessorRenames26_1() {
        Common_1_21_11_to_26_1_ClassMoves.registerClientAccessorRenames26_1(transformer);

        MethodInsnNode t = redirectedCall(caller(INVOKEVIRTUAL, "net/minecraft/client/Minecraft",
                "getTimer", "()Lnet/minecraft/client/DeltaTracker;"), "net/minecraft/client/Minecraft");
        assertNotNull(t); assertEquals("getDeltaTracker", t.name);

        MethodInsnNode p = redirectedCall(caller(INVOKEVIRTUAL, "net/minecraft/client/Camera",
                "getPosition", "()Lnet/minecraft/world/phys/Vec3;"), "net/minecraft/client/Camera");
        assertNotNull(p); assertEquals("position", p.name);
    }

    @Test
    @DisplayName("VertexConsumer/BufferBuilder.addVertex(Matrix4f,FFF) -> Matrix4fc param (JOML const, 26.1)")
    void jomlConstWidening() {
        Common_1_21_11_to_26_1_ClassMoves.registerClientAccessorRenames26_1(transformer);
        String oldDesc = "(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;";

        MethodInsnNode v = redirectedCall(caller(INVOKEINTERFACE, "com/mojang/blaze3d/vertex/VertexConsumer",
                "addVertex", oldDesc), "com/mojang/blaze3d/vertex/VertexConsumer");
        assertNotNull(v);
        assertEquals("(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", v.desc,
                "the Matrix4f param must widen to the Matrix4fc const interface");

        MethodInsnNode b = redirectedCall(caller(INVOKEVIRTUAL, "com/mojang/blaze3d/vertex/BufferBuilder",
                "addVertex", oldDesc), "com/mojang/blaze3d/vertex/BufferBuilder");
        assertNotNull(b);
        assertTrue(b.desc.contains("Lorg/joml/Matrix4fc;"), "BufferBuilder addVertex also widens");
    }

    @Test
    @DisplayName("GameRenderer.getMainCamera() -> mainCamera() (26.2)")
    void getMainCamera26_2() {
        Mc26_1To26_2CoreMoves.register(transformer);
        MethodInsnNode c = redirectedCall(caller(INVOKEVIRTUAL, "net/minecraft/client/renderer/GameRenderer",
                "getMainCamera", "()Lnet/minecraft/client/Camera;"),
                "net/minecraft/client/renderer/GameRenderer");
        assertNotNull(c); assertEquals("mainCamera", c.name);
    }

    @Test
    @DisplayName("Inventory.setPickedItem -> addAndPickItem (26.1 pick-block rename)")
    void inventorySetPickedItemRename() {
        Common_1_21_11_to_26_1_ClassMoves.registerCorpus26xDescriptorAdaptations(transformer);
        MethodInsnNode c = redirectedCall(caller(INVOKEVIRTUAL, "net/minecraft/world/entity/player/Inventory",
                "setPickedItem", "(Lnet/minecraft/world/item/ItemStack;)V"),
                "net/minecraft/world/entity/player/Inventory");
        assertNotNull(c); assertEquals("addAndPickItem", c.name);
    }

    @Test
    @DisplayName("InstantenousMobEffect -> InstantaneousMobEffect spelling fix (26.2)")
    void instantaneousMobEffectRename26_2() {
        Mc26_1To26_2CoreMoves.register(transformer);
        assertEquals("net/minecraft/world/effect/InstantaneousMobEffect",
                transformer.getClassRedirects().get("net/minecraft/world/effect/InstantenousMobEffect"),
                "a custom-instant-potion mod's `extends InstantenousMobEffect` must retarget the corrected spelling");
    }
}
