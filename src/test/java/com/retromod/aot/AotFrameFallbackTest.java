/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AOT's last-resort per-class path ({@code transformClassSimple}) recomputes stack-map frames with
 * {@code COMPUTE_FRAMES}, which can throw deep inside ASM ({@code Frame.merge} ->
 * {@code ArrayIndexOutOfBoundsException} / {@code NegativeArraySizeException}) on a class whose frames
 * can't be recomputed after our rewrites. Before snapshot.9 that one class's failure propagated out of
 * {@code transformClassAot} and aborted the WHOLE jar's AOT, dropping every mod back to un-transformed
 * bytes (#125 MineColonies, #127 The Flying Things). The fix wraps the simple path so a per-class
 * failure falls back to the robust main (JIT) transformer, then to the original bytes, and never
 * throws. This test drives the private method reflectively and asserts that contract.
 */
class AotFrameFallbackTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static Method simpleTransform() throws Exception {
        Method m = AotCompiler.class.getDeclaredMethod(
                "transformClassSimple", byte[].class, String.class);
        m.setAccessible(true);
        return m;
    }

    private static AotCompiler compiler() {
        return new AotCompiler(new ShimRegistry(), "26.2");
    }

    /** A trivial, well-formed class with one empty method. */
    private static byte[] wellFormedClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "noop", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("well-formed class still transforms to valid, loadable bytecode")
    void happyPathStillWorks() throws Exception {
        byte[] out = (byte[]) simpleTransform().invoke(compiler(),
                wellFormedClass("net/example/Fine"), "net/example/Fine");
        assertNotNull(out, "a well-formed class must transform to non-null bytes");
        // parseable => structurally valid; name preserved
        ClassReader cr = new ClassReader(out);
        assertEquals("net/example/Fine", cr.getClassName());
    }

    @Test
    @DisplayName("a class the processing path chokes on never aborts AOT; falls back without throwing")
    void unprocessableClassFallsBackAndDoesNotThrow() throws Exception {
        Method m = simpleTransform();
        AotCompiler c = compiler();
        // Truncated garbage: the class-processing path (new ClassReader / frame recomputation) throws,
        // standing in for the #125/#127 Frame.merge crash. The wrapper must swallow it and return bytes.
        byte[] garbage = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        Object result = assertDoesNotThrow(() -> m.invoke(c, garbage, "com/evil/Garbage"),
                "a per-class failure must not propagate and abort the whole jar's AOT");
        assertNotNull(result, "fallback must still return bytes, never null");
        assertTrue(((byte[]) result).length > 0, "fallback ships the original bytes as a last resort");
    }

    @Test
    @DisplayName("a bundled class that cannot be translated is named in a warning with its library")
    void untranslatableNestedClassIsReported() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        // A redirect makes transformClass parse the class instead of taking its fast path.
        transformer.registerClassRedirect("test/relocated/Old", "test/relocated/New");
        byte[] broken = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("lib/Lib.class", classWithField("lib/Lib", "test/relocated/Old"));
        entries.put("lib/Broken.class", broken);
        entries.put("lib/readme.txt", "kept".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String key = "outer.jar!/META-INF/jars/lib.jar";
        Method nested = AotCompiler.class.getDeclaredMethod("transformNestedJarAot", byte[].class,
                int.class, com.retromod.mixin.MixinCompatibilityTransformer.class, boolean.class,
                RetromodTransformer.NestedArchiveBudget.class, String.class);
        nested.setAccessible(true);
        byte[][] out = new byte[1][];

        String log = com.retromod.loaderapi.LoaderApiTestSupport.captureErr(() -> out[0] = (byte[]) nested.invoke(
                compiler(), zip(entries), 1, new com.retromod.mixin.MixinCompatibilityTransformer(transformer),
                false, RetromodTransformer.NestedArchiveBudget.defaults(), key));

        // The Mixin repair's own warnings name the class too, but never its library.
        assertTrue(log.lines().anyMatch(line -> line.contains("WARN") && line.contains("lib/Broken")
                && line.contains(key)), log);
        org.objectweb.asm.tree.ClassNode lib = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(unzip(out[0]).get("lib/Lib.class")).accept(lib, 0);
        assertEquals("Ltest/relocated/New;", lib.fields.get(0).desc);
        assertArrayEquals(broken, unzip(out[0]).get("lib/Broken.class"));
        assertArrayEquals(entries.get("lib/readme.txt"), unzip(out[0]).get("lib/readme.txt"));
    }

    private static byte[] classWithField(String name, String fieldType) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "ref", "L" + fieldType + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] zip(java.util.Map<String, byte[]> entries) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static java.util.Map<String, byte[]> unzip(byte[] jar) throws Exception {
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(jar))) {
            for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }
}
