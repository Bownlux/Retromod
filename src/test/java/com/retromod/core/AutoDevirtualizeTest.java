/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.polyfill.minecraft.item.ItemComponentPolyfill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the spawn-mod bootstrap crash (snapshot.3): the ItemStack NBT polyfill
 * registered instance to static redirects via the 6-arg {@code registerMethodRedirect}
 * form, so {@code ItemStack.getTag()} became {@code INVOKEVIRTUAL ItemStackNbtBridge.getTag(Object)},
 * which pops a receiver plus an argument where the call site only pushed the receiver. The
 * underflow tripped {@code Frame.merge} with {@code ArrayIndexOutOfBoundsException} when Mixin
 * recomputed frames.
 *
 * <p>Fix: an instance call redirected to a descriptor with one extra parameter is
 * auto-devirtualized to {@code INVOKESTATIC} (receiver-as-arg0).
 */
class AutoDevirtualizeTest {

    private static byte[] classCalling(String owner, String method, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/devirt/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "use", "(L" + owner + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);                 // push only the receiver
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, method, desc, false);
        if (desc.endsWith(")V")) { /* nothing */ } else { mv.visitInsn(Opcodes.POP); }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("ItemStack.getTag() (6-arg polyfill registration) emits INVOKESTATIC, not INVOKEVIRTUAL")
    void getTagIsDevirtualized() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        new ItemComponentPolyfill().registerPolyfills(t); // the registrations that crashed

        byte[] in = classCalling("net/minecraft/world/item/ItemStack",
                "getTag", "()Lnet/minecraft/nbt/CompoundTag;");
        byte[] out = t.transformClass(in, "test/devirt/Caller");

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : cn.methods.stream()
                .filter(m -> m.name.equals("use")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("getTag")) { call = m; break; }
        }
        assertNotNull(call, "the getTag call must survive (redirected)");
        assertEquals("com/retromod/polyfill/minecraft/item/embedded/ItemStackNbtBridge", call.owner);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(),
                "receiver-as-arg0 redirect must be INVOKESTATIC; INVOKEVIRTUAL pops one "
                + "value too many and corrupts the frame stack");
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", call.desc);
    }

    @Test
    @DisplayName("a same-arity instance redirect stays an instance call (no false devirtualize)")
    void sameArityStaysVirtual() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.registerMethodRedirect(
                "test/devirt/Owner", "oldName", "()V",
                "test/devirt/Owner", "newName", "()V");

        byte[] in = classCalling("test/devirt/Owner", "oldName", "()V");
        byte[] out = t.transformClass(in, "test/devirt/Caller");

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : cn.methods.stream()
                .filter(m -> m.name.equals("use")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("newName")) { call = m; break; }
        }
        assertNotNull(call);
        assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(),
                "same-arity rename must keep the instance opcode");
    }

    @Test
    @DisplayName("a devirtualize redirect never matches a static call, even when its target takes no extra argument")
    void devirtualizeRedirectSkipsStaticCall() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.registerMethodRedirect(
                "test/devirt/StaticOwner", "ping", "(I)V",
                "test/devirt/Helper", "ping", "(I)V", true);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/devirt/StaticCaller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/devirt/StaticOwner", "ping", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] out = t.transformClass(cw.toByteArray(), "test/devirt/StaticCaller");

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : cn.methods.stream()
                .filter(m -> m.name.equals("use")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("ping")) { call = m; break; }
        }
        assertNotNull(call);
        assertEquals("test/devirt/StaticOwner", call.owner,
                "a static call has no receiver to move, so a newer mod's static call keeps its host method");
    }
}
