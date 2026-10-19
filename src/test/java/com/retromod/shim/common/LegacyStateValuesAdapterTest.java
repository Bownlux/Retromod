/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * S33R More Food (#347), a 1.19.2 MCreator mod, eats a slice of its cake by swapping the block for
 * the next slice and copying the old state's properties across through {@code getValues()}. That
 * method returned an {@code ImmutableMap} when the mod was built and returns a stream of
 * {@code Property.Value} records on 26.1, so every bite failed with {@code NoSuchMethodError}.
 */
class LegacyStateValuesAdapterTest {

    private static final LegacyStateValuesAdapter.Names M = LegacyStateValuesAdapter.MOJANG;
    private static final LegacyStateValuesAdapter.Names I = LegacyStateValuesAdapter.INTERMEDIARY;
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String IMMUTABLE_MAP = LegacyStateValuesAdapter.IMMUTABLE_MAP;
    private static final String MAP = LegacyStateValuesAdapter.MAP;
    private static final String STREAM = LegacyStateValuesAdapter.STREAM;
    private static final Path MC_26_3 = Path.of("test-jars-mixin/minecraft-26.3-client.jar");

    /** Stands in for 26.1's {@code Property.Value}: the helper reads it through the same accessors. */
    public record Value(Object property, Object value) {}

    /** What the fake 26.x block state's {@code getValues()} streams. Public, since it is called from another package. */
    public static final class Host {
        public static Stream<Object> values() {
            return Stream.of(new Value("facing", "north"), new Value("bites", 1));
        }
    }

    @Test
    @DisplayName("on a 26.x host the old ImmutableMap call reads the stream and gets an ImmutableMap back")
    void oldMapCallReadsTheStream() throws Exception {
        List<String> registered = new ArrayList<>();
        byte[] out = LegacyStateValuesAdapter.apply(procedure(M.method(), BLOCK_STATE, IMMUTABLE_MAP),
                List.of(new LegacyStateValuesAdapter.HostShape(M, STREAM)), registered::add);

        List<MethodInsnNode> calls = calls(read(out), "copy");
        assertEquals(STREAM, calls.get(0).desc, "the call has to name the host's own getValues");
        assertEquals(LegacyStateValuesAdapter.HELPER, calls.get(1).owner);
        assertEquals("copyOf", calls.get(2).name, "the mod still gets the ImmutableMap it was compiled for");
        assertEquals("entrySet", calls.get(3).name);
        assertEquals(List.of(LegacyStateValuesAdapter.HELPER), registered, "the helper must be embedded");
        verify(out);
    }

    @Test
    @DisplayName("the repaired call runs and returns every property with its value")
    void repairedCallRuns() throws Exception {
        byte[] mod = LegacyStateValuesAdapter.apply(valuesReader(),
                List.of(new LegacyStateValuesAdapter.HostShape(M, STREAM)), helper -> {});
        Loader loader = new Loader(Map.of(BLOCK_STATE, fakeStreamBlockState(), "mod/ValuesReader", mod));

        Object state = loader.loadClass(BLOCK_STATE.replace('/', '.')).getConstructor().newInstance();
        Object values = loader.loadClass("mod.ValuesReader").getMethod("values", state.getClass()).invoke(null, state);

        assertEquals(ImmutableMap.of("facing", "north", "bites", 1), values);
    }

    @Test
    @DisplayName("an intermediary call on a 1.20.5 to 1.21.11 host copies the host's Map, without the helper")
    void intermediaryMapHost() {
        List<String> registered = new ArrayList<>();
        byte[] out = LegacyStateValuesAdapter.apply(procedure(I.method(), "net/minecraft/class_2680", IMMUTABLE_MAP),
                List.of(new LegacyStateValuesAdapter.HostShape(I, MAP)), registered::add);

        List<MethodInsnNode> calls = calls(read(out), "copy");
        assertEquals(MAP, calls.get(0).desc);
        assertEquals("copyOf", calls.get(1).name);
        assertEquals("entrySet", calls.get(2).name);
        assertTrue(registered.isEmpty(), "a Map host needs no helper");
    }

    @Test
    @DisplayName("a 1.21 mod's Map call on a 26.x host reads the stream and keeps its Map")
    void mapCallOnStreamHost() {
        byte[] out = LegacyStateValuesAdapter.apply(procedure(M.method(), BLOCK_STATE, MAP),
                List.of(new LegacyStateValuesAdapter.HostShape(M, STREAM)), helper -> {});

        List<MethodInsnNode> calls = calls(read(out), "copy");
        assertEquals(STREAM, calls.get(0).desc);
        assertEquals(LegacyStateValuesAdapter.HELPER, calls.get(1).owner);
        assertEquals("entrySet", calls.get(2).name, "no ImmutableMap copy for a mod that expects a Map");
    }

    @Test
    @DisplayName("current calls and a mod's own getValues are left alone")
    void unrelatedCallsAreKept() {
        List<LegacyStateValuesAdapter.HostShape> host = List.of(new LegacyStateValuesAdapter.HostShape(M, STREAM));
        byte[] current = procedure(M.method(), BLOCK_STATE, STREAM);
        byte[] foreign = procedure(M.method(), "mod/MyState", IMMUTABLE_MAP);
        assertSame(current, LegacyStateValuesAdapter.apply(current, host, helper -> fail("nothing to embed")));
        assertSame(foreign, LegacyStateValuesAdapter.apply(foreign, host, helper -> fail("nothing to embed")));
    }

    @Test
    @DisplayName("offline, the target version picks the shape")
    void offlineShapes() {
        assertTrue(LegacyStateValuesAdapter.offlineShapes("1.20.4").isEmpty());
        assertEquals(List.of(new LegacyStateValuesAdapter.HostShape(M, MAP), new LegacyStateValuesAdapter.HostShape(I, MAP)),
                LegacyStateValuesAdapter.offlineShapes("1.20.5"));
        assertEquals(List.of(new LegacyStateValuesAdapter.HostShape(M, MAP), new LegacyStateValuesAdapter.HostShape(I, MAP)),
                LegacyStateValuesAdapter.offlineShapes("1.21.11"));
        assertEquals(List.of(new LegacyStateValuesAdapter.HostShape(M, STREAM)), LegacyStateValuesAdapter.offlineShapes("26.1"));
        assertEquals(List.of(new LegacyStateValuesAdapter.HostShape(M, STREAM)), LegacyStateValuesAdapter.offlineShapes("26.3"));
    }

    @Test
    @DisplayName("the real 26.3 StateHolder has the stream shape")
    void real263HostShape() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(MC_26_3), "26.3 client jar present");
        try (ZipFile jar = new ZipFile(MC_26_3.toFile());
             InputStream in = jar.getInputStream(jar.getEntry(M.stateHolder() + ".class"))) {
            ClassNode stateHolder = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(stateHolder, ClassReader.SKIP_CODE);
            assertEquals(STREAM, LegacyStateValuesAdapter.hostDesc(stateHolder, M));
        }
    }

    /** {@code copy(state)} reads the state's values the way the mod was compiled to, then their entries. */
    private static byte[] procedure(String method, String owner, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/SliceProcedure", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "copy", "(L" + owner + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, method, desc, false);
        String returned = returnedType(desc);
        boolean itf = !returned.equals("com/google/common/collect/ImmutableMap");
        mv.visitMethodInsn(itf ? INVOKEINTERFACE : INVOKEVIRTUAL, returned, "entrySet",
                itf ? "()Ljava/util/Set;" : "()Lcom/google/common/collect/ImmutableSet;", itf);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return cw.toByteArray();
    }

    private static String returnedType(String desc) {
        return org.objectweb.asm.Type.getReturnType(desc).getInternalName();
    }

    /** {@code static ImmutableMap values(BlockState state) { return state.getValues(); }} */
    private static byte[] valuesReader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/ValuesReader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "values",
                "(L" + BLOCK_STATE + ";)Lcom/google/common/collect/ImmutableMap;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK_STATE, "getValues", IMMUTABLE_MAP, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return cw.toByteArray();
    }

    /** A 26.x-shaped block state whose {@code getValues()} streams {@link Host#values()}. */
    private static byte[] fakeStreamBlockState() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, BLOCK_STATE, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getValues", STREAM, null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, Host.class.getName().replace('.', '/'), "values", STREAM, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return cw.toByteArray();
    }

    /** Defines the given classes itself and leaves the rest, Guava and the helper included, to the test. */
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes) {
            super(LegacyStateValuesAdapterTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> known = findLoadedClass(name);
                return known != null ? known : defineClass(name, bytes, 0, bytes.length);
            }
        }
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node, String method) {
        MethodNode found = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : found.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
