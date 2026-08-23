/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The single-jar {@code transform} command has two paths: a version-detected one and an "all shims"
 * fallback (source MC version unreadable). The fallback used to skip the Fabric intermediary->Mojang
 * member map, so a Fabric mod that fell through kept its intermediary names and crashed on a 26.1+
 * host (found in-game: AppleSkin, whose version detection fell through). Both paths now run
 * {@link RetromodCli#register26xTargetMappings}. This proves that registration actually installs the
 * Fabric member map (a class_XXXX call remaps to its Mojang name) for a fabric mod on the 26.1 target.
 */
class TransformFallbackParityTest {

    @AfterEach
    void tearDown() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static ModVersionInfo fabricInfo(String targetMc) {
        return new ModVersionInfo("testmod", "1.0.0", targetMc, "fabric", "0.16",
                Set.of("test/mod"), Set.of(), false);
    }

    @Test
    @DisplayName("register26xTargetMappings installs the Fabric intermediary->Mojang member map (fallback parity)")
    void fallbackRegistersIntermediaryMemberMap() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // TARGET_MC_VERSION defaults to 26.1 (unobfuscated) in tests, so the 26.1+ branch runs.
        int moves = RetromodCli.register26xTargetMappings(t, fabricInfo(null)); // null target = the fallback shape
        assertTrue(moves > 0, "the 26.1 class-move table should have registered");

        // A distributed Fabric mod calling Minecraft.getInstance() by intermediary id (class_310 /
        // method_1551) must now remap to the Mojang names.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/mod/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/class_310", "method_1551",
                "()Lnet/minecraft/class_310;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = t.transformClass(cw.toByteArray(), "test/mod/Caller");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodInsnNode call = null;
        for (MethodNode m : cn.methods) {
            for (var i : m.instructions.toArray()) if (i instanceof MethodInsnNode mi) call = mi;
        }
        assertNotNull(call);
        assertEquals("net/minecraft/client/Minecraft", call.owner,
                "class_310 owner must remap to Minecraft (the fallback now installs the intermediary map)");
        assertEquals("getInstance", call.name, "method_1551 must remap to getInstance");
    }

    @Test
    @DisplayName("register26xTargetMappings no-ops on a pre-26.1 (obfuscated) target")
    void noOpBelow26() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // A pre-26.1 target is still obfuscated (intermediary runtime); the remap must not run there.
        // register26xTargetMappings gates on TARGET_MC_VERSION (26.1 in tests), so we assert the
        // Fabric-loader gate at least: a non-fabric info doesn't install the member map.
        RetromodCli.register26xTargetMappings(t, new ModVersionInfo("m", "1", null, "neoforge", "1",
                Set.of(), Set.of(), false));
        // NeoForge mods are Mojang-named; the member map must not be applied (would clobber fields).
        byte[] cw;
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, ACC_PUBLIC, "test/N", null, "java/lang/Object", null);
        MethodVisitor mv = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "c", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/class_310", "method_1551",
                "()Lnet/minecraft/class_310;", false);
        mv.visitInsn(POP); mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); w.visitEnd();
        cw = w.toByteArray();
        byte[] out = t.transformClass(cw, "test/N");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        boolean stillIntermediary = false;
        for (MethodNode m : cn.methods)
            for (var i : m.instructions.toArray())
                if (i instanceof MethodInsnNode mi && mi.owner.equals("net/minecraft/class_310")) stillIntermediary = true;
        assertTrue(stillIntermediary, "a NeoForge info must NOT trigger the Fabric intermediary remap");
    }

    @Test
    @DisplayName("register26xTargetMappings installs the 26.2 core moves for a 26.2 target (empty-chain parity)")
    void coreMoves262RegisteredFor262Target() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // The version-graph BFS finds no path from a 1.21.x source, so Fabric_26_1_to_26_2 never
        // applies offline; register26xTargetMappings must therefore pull Mc26_1To26_2CoreMoves in
        // unconditionally for a 26.2+ target. TARGET_MC_VERSION defaults to 26.1 in tests, so flip
        // it to 26.2 for this test (and restore after: other tests depend on the default).
        java.lang.reflect.Field target = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        target.setAccessible(true);
        String saved = (String) target.get(null);
        try {
            target.set(null, "26.2");
            RetromodCli.register26xTargetMappings(t, fabricInfo(null));

            // Probe with the 26.2-epoch screen hop: GETFIELD Minecraft.screen must rewrite to
            // GETFIELD Minecraft.gui + INVOKEVIRTUAL Gui.screen(), which only Mc26_1To26_2CoreMoves
            // registers. (transformBody kept inline: this file predates a shared helper.)
            ClassWriter w = new ClassWriter(0);
            w.visit(Opcodes.V17, ACC_PUBLIC, "test/S", null, "java/lang/Object", null);
            MethodVisitor mv = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "c",
                    "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/gui/screens/Screen;",
                    null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, "net/minecraft/client/Minecraft", "screen",
                    "Lnet/minecraft/client/gui/screens/Screen;");
            mv.visitInsn(ARETURN); mv.visitMaxs(0, 0); mv.visitEnd(); w.visitEnd();
            byte[] out = t.transformClass(w.toByteArray(), "test/S");
            ClassNode cn = new ClassNode();
            new ClassReader(out).accept(cn, 0);
            boolean hopped = false, rawFieldGone = true;
            for (MethodNode m : cn.methods)
                for (var i : m.instructions.toArray()) {
                    if (i instanceof MethodInsnNode mi && mi.owner.equals("net/minecraft/client/gui/Gui")
                            && mi.name.equals("screen")) hopped = true;
                    if (i instanceof org.objectweb.asm.tree.FieldInsnNode fi
                            && fi.name.equals("screen")) rawFieldGone = false;
                }
            assertTrue(hopped, "a 26.2 target must install the CoreMoves screen hop via the CLI path");
            assertTrue(rawFieldGone, "the raw Minecraft.screen read must be rewritten");
        } finally {
            target.set(null, saved);
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("all-shims fallback gates by target: 26.2-epoch shims must NOT fire on a 26.1 target")
    void fallbackAllShimsGatedByTarget() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        java.lang.reflect.Field target = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        target.setAccessible(true);
        String saved = (String) target.get(null);
        try {
            // 26.1 target (the default): Minecraft.screen is a real public field there, so the
            // ungated fallback registering Mc26_1To26_2CoreMoves' screen hop would rewrite a
            // WORKING read into 26.2-only Gui.screen() -> NoSuchMethodError (review finding).
            target.set(null, "26.1");
            t.clearRedirectsForTesting();
            RetromodCli.registerAllShimsGated(t, "fabric");
            assertFalse(screenReadRewritten(t),
                    "a 26.1-target fallback must leave the still-public Minecraft.screen alone");

            // 26.2 target: the same fallback must now include the 26.2 shims and rewrite it.
            target.set(null, "26.2");
            t.clearRedirectsForTesting();
            RetromodCli.registerAllShimsGated(t, "fabric");
            assertTrue(screenReadRewritten(t),
                    "a 26.2-target fallback must apply the screen hop");
        } finally {
            target.set(null, saved);
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("transform fallback recognizes blank, placeholder, and unparseable source versions")
    void transformFallbackRecognizesEveryUnknownSourceShape() {
        assertTrue(RetromodCli.isUnknownSourceVersion(null));
        assertTrue(RetromodCli.isUnknownSourceVersion(""));
        assertTrue(RetromodCli.isUnknownSourceVersion("   "));
        assertTrue(RetromodCli.isUnknownSourceVersion("${minecraft_version}"));
        assertTrue(RetromodCli.isUnknownSourceVersion("unknown"));
        assertFalse(RetromodCli.isUnknownSourceVersion("1.20.1"));
        assertFalse(RetromodCli.isUnknownSourceVersion(">=1.20"));
    }

    private static boolean screenReadRewritten(RetromodTransformer t) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, ACC_PUBLIC, "test/G", null, "java/lang/Object", null);
        MethodVisitor mv = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "c",
                "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/gui/screens/Screen;",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "net/minecraft/client/Minecraft", "screen",
                "Lnet/minecraft/client/gui/screens/Screen;");
        mv.visitInsn(ARETURN); mv.visitMaxs(0, 0); mv.visitEnd(); w.visitEnd();
        byte[] out = RetromodTransformer.getInstance().transformClass(w.toByteArray(), "test/G");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        for (MethodNode m : cn.methods)
            for (var i : m.instructions.toArray())
                if (i instanceof MethodInsnNode mi && mi.owner.equals("net/minecraft/client/gui/Gui")
                        && mi.name.equals("screen")) return true;
        return false;
    }
}
