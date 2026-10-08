/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Mod classes are transformed in parallel, and the first class to arrive runs the one-time
 * phantom-target sweep. Every class has to see the swept redirect set, whichever worker it lands
 * on, or the same jar transforms differently from one launch to the next.
 */
class ConcurrentPhantomSweepTest {

    private static final String IDENTIFIER = "net/example/Identifier";
    private static final String CTOR = "(Ljava/lang/String;)V";
    private static final String PARSE = "(Ljava/lang/String;)L" + IDENTIFIER + ";";
    private static final String PHANTOM_PACKAGE = "com/retromod/ghost/";
    private static final String PHANTOM_FACTORY = PHANTOM_PACKAGE + "IdentifierFactory";
    private static final String WIDGET = "net/example/Widget";

    /** Enough unresolvable targets that the sweep takes far longer than one class transform. */
    private static final int SLOW_SWEEP_TARGETS = 4000;
    private static final int WORKERS = 8;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("classes transformed while the phantom sweep runs see the swept redirects")
    void concurrentFirstTransformsMatch() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        warmUp(transformer);

        transformer.clearRedirectsForTesting();
        transformer.registerConstructorRedirect(IDENTIFIER, CTOR, IDENTIFIER, "parse", PARSE);
        // A phantom constructor factory: the sweep must drop it, so new Widget(String) stays.
        transformer.registerConstructorRedirect(WIDGET, CTOR, PHANTOM_FACTORY, "create",
                "(Ljava/lang/String;)L" + WIDGET + ";");
        for (int i = 0; i < SLOW_SWEEP_TARGETS; i++) {
            transformer.registerMethodRedirect("net/example/Slow" + i, "call", "()V",
                    PHANTOM_PACKAGE + "Slow" + i, "call", "()V");
        }

        byte[] input = identifierUser();
        List<byte[]> outputs = transformConcurrently(transformer, input);

        byte[] first = outputs.get(0);
        for (int i = 1; i < outputs.size(); i++) {
            assertArrayEquals(first, outputs.get(i),
                    "worker " + i + " produced different bytes from worker 0 for the same class");
        }
        for (byte[] output : outputs) {
            String text = new String(output, StandardCharsets.ISO_8859_1);
            assertFalse(text.contains(PHANTOM_PACKAGE),
                    "a class transformed during the sweep was rewritten to a phantom target");
            assertTrue(text.contains("parse"),
                    "the Identifier constructor calls were not redirected to the factory");
        }
    }

    private static List<byte[]> transformConcurrently(RetromodTransformer transformer, byte[] input)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transformer.transformClass(input, "test/IdentifierUser");
                }));
            }
            start.countDown();
            List<byte[]> outputs = new ArrayList<>();
            for (Future<byte[]> future : futures) {
                outputs.add(future.get(2, TimeUnit.MINUTES));
            }
            return outputs;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Loads the visitor chain first, so a cold JIT does not hide the race. */
    private static void warmUp(RetromodTransformer transformer) {
        transformer.clearRedirectsForTesting();
        transformer.registerConstructorRedirect(IDENTIFIER, CTOR, IDENTIFIER, "parse", PARSE);
        for (int i = 0; i < 20; i++) {
            transformer.transformClass(identifierUser(), "test/IdentifierUser");
        }
    }

    /** A mod method that builds four identifiers and one widget, like a data loader would. */
    private static byte[] identifierUser() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/IdentifierUser", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load", "()V", null, null);
        mv.visitCode();
        for (int i = 0; i < 4; i++) {
            newWithString(mv, IDENTIFIER, "palladium:power_" + i);
        }
        newWithString(mv, WIDGET, "widget");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void newWithString(MethodVisitor mv, String type, String value) {
        mv.visitTypeInsn(NEW, type);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(value);
        mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", CTOR, false);
        mv.visitInsn(POP);
    }
}
