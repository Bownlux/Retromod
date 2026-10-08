/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.retromod.shim.common.LegacyAttributeApiBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.20.1 mod's attribute code, after the SRG remap, against the 1.21 attribute API: bare
 * attribute constants, UUID-keyed modifiers, and Attribute parameters that became holders.
 */
class LegacyAttributeApiBridgeTest {

    /** Stands in for the host's ResourceLocation, so the generated id helpers can run. */
    public static final class StubId {
        private final String namespace;
        private final String path;

        private StubId(String namespace, String path) {
            this.namespace = namespace;
            this.path = path;
        }

        public static StubId fromNamespaceAndPath(String namespace, String path) {
            return new StubId(namespace, path);
        }

        public String getNamespace() { return namespace; }
        public String getPath() { return path; }
        @Override public String toString() { return namespace + ":" + path; }
        @Override public boolean equals(Object other) {
            return other instanceof StubId id && id.toString().equals(toString());
        }
        @Override public int hashCode() { return toString().hashCode(); }
    }

    private static final String ID = StubId.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void legacyAttributeCallsReachTheGeneratedAdapters() {
        RetromodTransformer transformer = register();

        List<String> calls = calls(transformer.transformClass(legacyCaller(), "test/LegacyMob.class"));

        assertTrue(calls.contains(HELPER + ".new$AttributeModifier$"
                        + hex("(Ljava/util/UUID;Ljava/lang/String;DL" + OPERATION + ";)V")),
                "the UUID modifier constructor must go through the id adapter: " + calls);
        assertTrue(calls.contains(HELPER + ".Attributes$MAX_HEALTH"),
                "a bare MAX_HEALTH read must unwrap the holder: " + calls);
        assertTrue(calls.contains(HELPER + ".AttributeInstance$removeModifier$"
                        + hex("(Ljava/util/UUID;)V")),
                "removal by UUID must use the derived id: " + calls);
        assertFalse(calls.contains(MODIFIER + ".<init>"), "no legacy constructor may survive");
    }

    @Test
    void aHolderReadFromANewerModIsLeftAlone() {
        RetromodTransformer transformer = register();

        ClassNode node = node(transformer.transformClass(modernCaller(), "test/ModernMob.class"));

        boolean plainRead = false;
        for (AbstractInsnNode insn : method(node, "use").instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.name.equals("MAX_HEALTH")) {
                plainRead = field.desc.equals("L" + HOLDER + ";");
            }
            assertFalse(insn instanceof MethodInsnNode call && call.owner.equals(HELPER),
                    "a read with the current holder type must not be redirected");
        }
        assertTrue(plainRead, "the holder read must stay a field read");
    }

    @Test
    void offlineA1211TargetGetsTheVerifiedShape() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();

        assertTrue(registerOffline(transformer, "1.21.1"), "1.21.1 is the verified offline shape");
        List<String> calls = calls(transformer.transformClass(legacyCaller(), "test/LegacyMob.class"));

        assertTrue(calls.contains(HELPER + ".Attributes$MAX_HEALTH"),
                "a bare MAX_HEALTH read must unwrap the holder offline too: " + calls);
        assertFalse(calls.contains(MODIFIER + ".<init>"), "no legacy constructor may survive");
    }

    @Test
    void offlineOtherTargetsAreNotGuessed() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();

        assertFalse(registerOffline(transformer, "1.20.6"), "1.20.6 still keys modifiers by UUID");
        assertFalse(registerOffline(transformer, "1.21.4"), "later versions renamed constants");
        assertFalse(registerOffline(transformer, "1.20.1"), "the old API is still there");
    }

    @Test
    void everyGeneratedAdapterIsStructurallyValid() throws Exception {
        ClassNode helper = node(generateHelper(ID, candidates(ID), List.of("MAX_HEALTH"), List.of("POISON")));
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void aHostThatStillKeysModifiersByUuidGetsOnlyTheHolderHelpers() throws Exception {
        // 1.20.5 and 1.20.6: holders, but the UUID modifier constructor is still there.
        List<Rewrite> holderOnly = candidates("java/util/UUID").stream()
                .filter(rewrite -> !rewrite.owner().equals(MODIFIER)).toList();
        ClassNode helper = node(generateHelper("java/util/UUID", holderOnly, List.of("MAX_HEALTH"), List.of()));

        assertTrue(helper.methods.stream().noneMatch(m -> m.name.equals("idOf") || m.name.equals("uuidOf")),
                "no id conversion is generated when the id is the UUID itself");
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void theSameUuidAlwaysGivesTheSameIdAndReadsBack() throws Exception {
        Class<?> helper = loadHelper();
        Method idOf = helper.getMethod("idOf", UUID.class);
        Method uuidOf = helper.getMethod("uuidOf", StubId.class);
        UUID uuid = UUID.fromString("7107de5e-7ce8-4030-940e-514c1f160890");

        Object id = idOf.invoke(null, uuid);

        assertEquals(id, idOf.invoke(null, uuid), "a fixed UUID must map to a fixed id");
        assertEquals("retromod:" + uuid, id.toString());
        assertEquals(uuid, uuidOf.invoke(null, id), "a derived id must read back as its UUID");
    }

    @Test
    void anIdFromANewerModGivesAStableNameBasedUuid() throws Exception {
        Method uuidOf = loadHelper().getMethod("uuidOf", StubId.class);
        StubId foreign = StubId.fromNamespaceAndPath("examplemod", "speed_boost");
        StubId oddPath = StubId.fromNamespaceAndPath("retromod", "not_a_uuid");

        assertEquals(UUID.nameUUIDFromBytes("examplemod:speed_boost".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), uuidOf.invoke(null, foreign));
        assertEquals(uuidOf.invoke(null, oddPath), uuidOf.invoke(null, oddPath),
                "a retromod id that is not a UUID must still give a stable answer");
    }

    private static RetromodTransformer register() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, ID, candidates(ID), List.of("MAX_HEALTH"), List.of());
        return transformer;
    }

    private static Class<?> loadHelper() {
        byte[] bytes = generateHelper(ID, List.of(), List.of(), List.of());
        // Reflection resolves every signature, and the holder wrappers name Minecraft's Holder.
        ClassWriter holder = new ClassWriter(0);
        holder.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                HOLDER, null, "java/lang/Object", null);
        holder.visitEnd();
        byte[] holderBytes = holder.toByteArray();
        return new ClassLoader(LegacyAttributeApiBridgeTest.class.getClassLoader()) {
            Class<?> define() {
                defineClass(HOLDER.replace('/', '.'), holderBytes, 0, holderBytes.length);
                return defineClass(HELPER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    private static String hex(String desc) {
        return Integer.toHexString(desc.hashCode());
    }

    /** {@code new AttributeModifier(uuid, "name", 2.0, op)}, a bare MAX_HEALTH, removal by UUID. */
    private static byte[] legacyCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyMob", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use",
                "(Ljava/util/UUID;L" + OPERATION + ";L" + INSTANCE + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INSTANCE, "removeModifier", "(Ljava/util/UUID;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "MAX_HEALTH", "L" + ATTRIBUTE + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, MODIFIER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("Legacy bonus");
        mv.visitLdcInsn(2.0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MODIFIER, "<init>",
                "(Ljava/util/UUID;Ljava/lang/String;DL" + OPERATION + ";)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] modernCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModernMob", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use",
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "MAX_HEALTH", "L" + HOLDER + ";");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node(bytes), "use").instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
