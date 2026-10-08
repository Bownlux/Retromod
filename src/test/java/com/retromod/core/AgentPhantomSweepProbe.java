/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.nio.charset.StandardCharsets;

import static org.objectweb.asm.Opcodes.*;

/**
 * Child-JVM entry point for {@link AgentPhantomSweepTest}. It runs under the Java agent, so the
 * phantom sweep's class loads go through the agent's transform callback. The sweep is one-shot per
 * JVM, which is why each scenario needs its own process.
 */
public final class AgentPhantomSweepProbe {

    static final String FIXTURE = "com/retromod/shim/sweepfixture/SweepTargets$";
    static final String[] TARGETS = {
            FIXTURE + "First", FIXTURE + "Second", FIXTURE + "Third", FIXTURE + "Fourth"};
    static final String PHANTOM_PACKAGE = "com/retromod/shim/sweepfixture/ghost/";

    private AgentPhantomSweepProbe() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "reentry" -> reentry();
            case "concurrent-load" -> concurrentLoad(Long.parseLong(args[1]));
            default -> throw new IllegalArgumentException("Unknown scenario: " + args[0]);
        }
    }

    /** Every loadable target must survive the sweep, even though loading it re-enters the transformer. */
    private static void reentry() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.resetOfflineRegistrations();
        for (int i = 0; i < TARGETS.length; i++) {
            transformer.registerMethodRedirect("net/example/Old" + i, "call", "()V",
                    TARGETS[i], "call", "()V");
        }
        transformer.registerMethodRedirect("net/example/Ghost", "call", "()V",
                PHANTOM_PACKAGE + "Missing", "call", "()V");

        byte[] output = transformer.transformClass(callerOfOldTargets(), "test/Caller");

        String text = new String(output, StandardCharsets.ISO_8859_1);
        int redirected = 0;
        for (String target : TARGETS) {
            if (text.contains(target)) redirected++;
        }
        System.out.println("REDIRECTED " + redirected + "/" + TARGETS.length);
    }

    /**
     * One thread runs a slow sweep while another loads a class the sweep has yet to check. The
     * loading thread enters the agent callback holding that class's loading lock, so it must not
     * wait for the sweep, which needs the same lock.
     */
    private static void concurrentLoad(long loadDelayMillis) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.resetOfflineRegistrations();
        for (int i = 0; i < 4000; i++) {
            transformer.registerMethodRedirect("net/example/Slow" + i, "call", "()V",
                    PHANTOM_PACKAGE + "Slow" + i, "call", "()V");
        }
        // Constructor redirects are swept after method redirects, so this target is checked last.
        transformer.registerConstructorRedirect("net/example/Widget", "()V",
                TARGETS[0], "create", "()Lnet/example/Widget;");

        byte[] input = callerOfOldTargets();
        Thread sweeper = new Thread(() -> transformer.transformClass(input, "test/Caller"), "sweeper");
        Thread loader = new Thread(() -> {
            try {
                Thread.sleep(loadDelayMillis);
                Class.forName(TARGETS[0].replace('/', '.'), false,
                        AgentPhantomSweepProbe.class.getClassLoader());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "loader");
        sweeper.start();
        loader.start();
        sweeper.join();
        loader.join();
        System.out.println("FINISHED");
    }

    private static byte[] callerOfOldTargets() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (int i = 0; i < TARGETS.length; i++) {
            mv.visitMethodInsn(INVOKESTATIC, "net/example/Old" + i, "call", "()V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
