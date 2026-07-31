/*
 * Retromod - repro for the DuskConfig StackMapTable corruption (snapshot.8 SS A).
 * Reads the REAL failing class from the scratchpad when present; gated behind
 * -Dretromod.repro=true, so CI without the scratchpad is unaffected.
 *
 * The original bug: a ctor->factory redirect whose ARG EXPRESSION contains a branch
 * (ternary) plus a nested plain `new` (StringBuilder). The old single-slot deferral
 * flushed the buffered NEW+DUP at the nested NEW, i.e. mid-expression on ONE branch
 * path only, so paths reached the convergent <init> with different stack heights
 * (ASM Frame.merge AIOOBE -> silent COMPUTE_MAXS fallback -> stale frames ->
 * "StackMapTable format error: bad offset for Uninitialized" at load).
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CtorRedirectFrameRepro {

    private static final Path REAL_CLASS = Path.of(
            "/private/tmp/claude-501/-Users-rossi-Development-Minecraft-RetroMod-MC-RetroMod/"
            + "eabb6484-bc56-47a7-a471-3c4edc8596f8/scratchpad/duskrepro/"
            + "com/natamus/collective_fabric/config/DuskConfig.class");

    @Test
    @EnabledIfSystemProperty(named = "retromod.repro", matches = "true")
    void duskConfigCtorRedirectKeepsValidFrames() throws Exception {
        assertTrue(Files.exists(REAL_CLASS), "repro class missing");
        byte[] in = Files.readAllBytes(REAL_CLASS);

        // Mirror Pre1_19TextBridge exactly: ctor->factory (pre-remap keys) + retype to
        // MutableText (class redirects), so the factory result verifies at its use sites.
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        t.registerConstructorRedirect("net/minecraft/class_2588", "(Ljava/lang/String;)V",
                "net/minecraft/class_2561", "method_43471",
                "(Ljava/lang/String;)Lnet/minecraft/class_5250;", true);
        t.registerConstructorRedirect("net/minecraft/class_2585", "(Ljava/lang/String;)V",
                "net/minecraft/class_2561", "method_43470",
                "(Ljava/lang/String;)Lnet/minecraft/class_5250;", true);
        t.registerClassRedirect("net/minecraft/class_2588", "net/minecraft/class_5250");
        t.registerClassRedirect("net/minecraft/class_2585", "net/minecraft/class_5250");

        byte[] out = t.transformClass(in, "com/natamus/collective_fabric/config/DuskConfig.class");
        assertNotNull(out);

        // 1. Parsing with EXPAND_FRAMES resolves every StackMapTable offset; a frame pointing
        //    at a removed NEW dies here ("bad offset for Uninitialized" / "Undefined label").
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, ClassReader.EXPAND_FRAMES);

        // 2. BasicVerifier dataflow-analyzes each method without loading app classes: stack
        //    underflow or branch-path height mismatches (the Frame.merge AIOOBE) surface here.
        boolean sawFactory = false;
        boolean sawRawCtor = false;
        for (MethodNode mn : cn.methods) {
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, mn);
            } catch (Exception e) {
                fail("method " + mn.name + mn.desc + " does not verify after transform: " + e, e);
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode mi
                        && "net/minecraft/class_2561".equals(mi.owner)
                        && ("method_43471".equals(mi.name) || "method_43470".equals(mi.name))) {
                    sawFactory = true;
                    // Text is an interface on 1.19+: without the InterfaceMethodref form the
                    // call dies IncompatibleClassChangeError at link (round 5 of the pass)
                    assertTrue(mi.itf, "factory INVOKESTATIC must be the interface form");
                }
                // NO reference to the old classes may survive at all: leftover call owners
                // are exactly the VerifyError "not assignable to class_2588" failure mode.
                if (insn instanceof MethodInsnNode mi
                        && ("net/minecraft/class_2588".equals(mi.owner)
                                || "net/minecraft/class_2585".equals(mi.owner))) {
                    sawRawCtor = true;
                }
            }
        }
        // The redirect must actually have applied everywhere (no ship-original cop-out,
        // no forfeited site left behind by a nested plain new).
        assertTrue(sawFactory, "ctor->factory redirect must be applied");
        assertFalse(sawRawCtor, "no reference to the removed Text classes may survive");
    }
}
