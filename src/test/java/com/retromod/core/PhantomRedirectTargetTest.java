/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A redirect whose target is a {@code com/retromod/*} class that will never resolve at runtime
 * (a "phantom" target, e.g. the never-written {@code EnchantmentShim} that crashed Apollo's
 * Enchantment Rebalance in an anvil, #119) must be dropped at the first transform rather than
 * silently rewriting a call into a {@code NoClassDefFoundError} that detonates on first use.
 * Redirects to real Retromod classes and to registered synthetics must be untouched.
 */
class PhantomRedirectTargetTest {

    private static final String SRC = "net/example/EnchLike";
    private static final String PHANTOM = "com/retromod/ghost/EnchantmentShim";
    private static final String REAL = "com/retromod/polyfill/fabric/MaterialPolyfill"; // exists on classpath
    private static final String SYNTHETIC = "com/retromod/test/RegisteredSynth";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    /** A mod class calling three instance methods on the source type. */
    private static byte[] callerClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run",
                "(L" + SRC + ";)V", null, null);
        mv.visitCode();
        for (String m : new String[]{"toPhantom", "toReal", "toSynthetic"}) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, SRC, m, "()I", false);
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Minimal loadable class bytes to register as a synthetic. */
    private static byte[] tinyClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("phantom com/retromod target is dropped; real + synthetic targets survive")
    void phantomDroppedRealAndSyntheticKept() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();

        // devirtualized redirects (receiver becomes arg0)
        t.registerMethodRedirect(SRC, "toPhantom", "()I",
                PHANTOM, "toPhantom", "(Ljava/lang/Object;)I", true);
        t.registerMethodRedirect(SRC, "toReal", "()I",
                REAL, "toReal", "(Ljava/lang/Object;)I", true);
        t.registerMethodRedirect(SRC, "toSynthetic", "()I",
                SYNTHETIC, "toSynthetic", "(Ljava/lang/Object;)I", true);
        // register the synthetic so the guard sees it as embed-bound, not phantom
        t.registerSyntheticClass(SYNTHETIC, tinyClass(SYNTHETIC));

        byte[] out = t.transformClass(callerClass(), "test/Caller.class");
        assertNotNull(out);

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodNode run = cn.methods.stream().filter(m -> m.name.equals("run"))
                .findFirst().orElseThrow();

        boolean phantomLeftOriginal = false, realRewritten = false, syntheticRewritten = false;
        for (AbstractInsnNode insn : run.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            if (SRC.equals(mi.owner) && "toPhantom".equals(mi.name)) phantomLeftOriginal = true;
            if (REAL.equals(mi.owner) && "toReal".equals(mi.name)) realRewritten = true;
            if (SYNTHETIC.equals(mi.owner) && "toSynthetic".equals(mi.name)) syntheticRewritten = true;
            // the phantom must not appear as a call owner anywhere
            assertNotEquals(PHANTOM, mi.owner,
                    "a call to the phantom target survived; it would NoClassDefFoundError at runtime");
        }
        assertTrue(phantomLeftOriginal,
                "phantom redirect must be dropped, leaving the original call intact");
        assertTrue(realRewritten, "redirect to a real Retromod class must still apply");
        assertTrue(syntheticRewritten, "redirect to a registered synthetic must still apply");
    }

    @Test
    @DisplayName("a dead target is named in one warning per process, not once per offline input")
    void warnsOncePerDeadTarget() throws Exception {
        // The warned names last for the process, so no other test may use these two.
        String first = "com/retromod/ghost/WarnOnceFirst";
        String second = "com/retromod/ghost/WarnOnceSecond";
        RetromodTransformer t = RetromodTransformer.getInstance();

        String twoInputs = LoaderApiTestSupport.captureErr(() -> {
            translateOfflineInput(t, first);
            translateOfflineInput(t, first);
        });
        List<String> warnings = droppedWarnings(twoInputs);
        assertEquals(1, warnings.size(), "each input registers the same dead target:\n" + twoInputs);
        assertTrue(warnings.get(0).contains(first));

        String thirdInput = LoaderApiTestSupport.captureErr(() -> translateOfflineInput(t, first, second));
        warnings = droppedWarnings(thirdInput);
        assertEquals(1, warnings.size(), "a new dead target is still reported:\n" + thirdInput);
        assertTrue(warnings.get(0).contains(second));
        assertFalse(warnings.get(0).contains(first), "the earlier target was already reported");
    }

    @Test
    @DisplayName("Fabric's init pass does not name a dead target again, because pre-launch already reached latest.log")
    void doesNotWarnAgainInFabricInitPass() throws Exception {
        String ghost = "com/retromod/ghost/WarnOnceAcrossPasses";
        RetromodTransformer t = RetromodTransformer.getInstance();
        String preLaunch = LoaderApiTestSupport.captureErr(() -> translateOfflineInput(t, ghost));

        // Init registers every shim again without a reset, and the loader API runtime re-arms the sweep.
        String init = LoaderApiTestSupport.captureErr(() -> {
            registerGhosts(t, ghost);
            t.requestTargetRevalidation();
            t.transformClass(callerClass(), "test/Caller.class");
        });

        assertEquals(1, droppedWarnings(preLaunch).size(), preLaunch);
        assertEquals(0, droppedWarnings(init).size(), "init repeats the warning only at DEBUG:\n" + init);
    }

    /** Starts an offline input, registers one redirect to each of {@code ghosts}, and transforms one class. */
    private static void translateOfflineInput(RetromodTransformer t, String... ghosts) {
        t.resetOfflineRegistrations();
        registerGhosts(t, ghosts);
        t.transformClass(callerClass(), "test/Caller.class");
    }

    private static void registerGhosts(RetromodTransformer t, String... ghosts) {
        for (String ghost : ghosts) {
            String method = ghost.substring(ghost.lastIndexOf('/') + 1);
            t.registerMethodRedirect(SRC, method, "()I", ghost, method, "(Ljava/lang/Object;)I", true);
        }
    }

    private static List<String> droppedWarnings(String log) {
        return log.lines()
                .filter(line -> line.contains("WARN") && line.contains("Dropped redirects"))
                .toList();
    }
}
