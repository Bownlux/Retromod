/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** An old mod's {@code Registry.getTags()} streams the (key, set) pairs it was written for, and a newer mod's stays the host's. */
class LegacyRegistryTagPairsTest {

    private static final String STREAM = LegacyRegistryLookupBridge.STREAM;
    private static final LegacyRegistryLookupBridge.TagPairNames MOJANG = LegacyRegistryLookupBridge.MOJANG_TAGS;
    private static final LegacyRegistryLookupBridge.TagPairNames INTERMEDIARY = LegacyRegistryLookupBridge.INTERMEDIARY_TAGS;
    private static final String MOD_CLASS = "test/tags/Lister";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "mod built for {0}")
    @ValueSource(strings = {"1.20.1", "1.21.1"})
    @DisplayName("Mojang names: a mod built before 1.21.2 sends getTags() on Registry, DefaultedRegistry and MappedRegistry to the helper")
    void oldModsReachTheHelper(String built) {
        LegacyRegistryLookupBridge.registerTagPairRedirects(transformer, MOJANG);
        transformer.setModSourceVersion(built);

        List<MethodInsnNode> calls = calls(transform(caller(MOJANG,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/Registry", true),
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/DefaultedRegistry", true),
                new Call(Opcodes.INVOKEVIRTUAL, "net/minecraft/core/MappedRegistry", false))));

        assertEquals(3, calls.size(), calls.toString());
        for (MethodInsnNode call : calls) assertHelperCall(call, MOJANG);
    }

    @ParameterizedTest(name = "mod built for {0}")
    @ValueSource(strings = {"1.21.2", "1.21.5", "26.1"})
    @DisplayName("Mojang names: a mod built for 1.21.2 or later keeps the host's getTags(), whose named sets it expects")
    void newerModsKeepTheHostCall(String built) {
        LegacyRegistryLookupBridge.registerTagPairRedirects(transformer, MOJANG);
        transformer.setModSourceVersion(built);

        List<MethodInsnNode> calls = calls(transform(caller(MOJANG,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/DefaultedRegistry", true))));

        assertEquals(1, calls.size());
        assertEquals("net/minecraft/core/DefaultedRegistry", calls.get(0).owner);
        assertEquals("getTags", calls.get(0).name);
    }

    @ParameterizedTest(name = "mod built for {0}")
    @ValueSource(strings = {"1.20.1", "1.21.5"})
    @DisplayName("a bound registry::getTags reference, as JEI makes it, follows the same version limit as a call")
    void methodReferenceFollowsTheLimit(String built) {
        LegacyRegistryLookupBridge.registerTagPairRedirects(transformer, MOJANG);
        transformer.setModSourceVersion(built);

        Handle handle = handle(transform(boundReference(MOJANG.names().registry())));

        if (built.equals("1.20.1")) {
            assertEquals(Opcodes.H_INVOKESTATIC, handle.getTag());
            assertEquals(MOJANG.helper(), handle.getOwner());
            assertEquals(MOJANG.names().helperDesc(STREAM), handle.getDesc());
        } else {
            assertEquals(Opcodes.H_INVOKEINTERFACE, handle.getTag(), "a 1.21.5 mod keeps the host's getTags()");
            assertEquals(MOJANG.names().registry(), handle.getOwner());
        }
    }

    @ParameterizedTest(name = "jar {0}")
    @ValueSource(strings = {"lister-fabric-1.21.1.jar", "lister-fabric-1.21.4.jar"})
    @DisplayName("a mod that declares >=1.21 is judged by the version its jar shows it was built for")
    void buildVersionDecides(String jarName, @TempDir Path dir) throws Exception {
        LegacyRegistryLookupBridge.registerTagPairRedirects(transformer, MOJANG);
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write("{\"schemaVersion\":1,\"id\":\"lister\",\"version\":\"1.0\",\"depends\":{\"minecraft\":\">=1.21\"}}"
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        transformer.setModSourceVersion(">=1.21");
        transformer.setModSourceJar(jar);

        MethodInsnNode call = calls(transform(caller(MOJANG,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/DefaultedRegistry", true)))).get(0);

        if (jarName.contains("1.21.4")) {
            assertEquals("net/minecraft/core/DefaultedRegistry", call.owner, "a jar built for 1.21.4 expects the named sets");
        } else {
            assertHelperCall(call, MOJANG);
        }
    }

    @Test
    @DisplayName("intermediary names: a Fabric 1.21.1 mod's method_40272 reaches the intermediary helper on a pre-26.1 host")
    void intermediaryCallsReachTheHelper() {
        LegacyRegistryLookupBridge.registerTagPairRedirects(transformer, INTERMEDIARY);
        transformer.setModSourceVersion("1.21.1");

        List<MethodInsnNode> calls = calls(transform(caller(INTERMEDIARY,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/class_7922", true),
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/class_2378", true))));

        assertEquals(2, calls.size(), calls.toString());
        for (MethodInsnNode call : calls) assertHelperCall(call, INTERMEDIARY);
    }

    @Test
    @DisplayName("the host's generic signature decides: named sets need the helper, pairs do not")
    void signatureDecides() {
        assertTrue(LegacyRegistryLookupBridge.streamsNamedTags(registry(MOJANG,
                "()Ljava/util/stream/Stream<Lnet/minecraft/core/HolderSet$Named<TT;>;>;"), MOJANG), "1.21.2 to 26.3");
        assertFalse(LegacyRegistryLookupBridge.streamsNamedTags(registry(MOJANG,
                "()Ljava/util/stream/Stream<Lcom/mojang/datafixers/util/Pair<Lnet/minecraft/tags/TagKey<TT;>;"
                        + "Lnet/minecraft/core/HolderSet$Named<TT;>;>;>;"), MOJANG), "1.21.1 streams pairs, which name the set too");
        assertTrue(LegacyRegistryLookupBridge.streamsNamedTags(registry(INTERMEDIARY,
                "()Ljava/util/stream/Stream<Lnet/minecraft/class_6885$class_6888<TT;>;>;"), INTERMEDIARY));
        assertFalse(LegacyRegistryLookupBridge.streamsNamedTags(registry(MOJANG, null), MOJANG), "no signature proves nothing");
    }

    @Test
    @DisplayName("offline, with no host to read, a target before 1.21.2 keeps getTags() and one from 1.21.2 gets the helper")
    void offlineFollowsTheTarget() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyRegistryLookupBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(MOJANG.helper()));

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyRegistryLookupBridge.register(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(MOJANG.helper()));
    }

    @Test
    @DisplayName("the helpers verify and call the host's getTags() and the set's key() by their names")
    void helpersVerify() throws Exception {
        for (LegacyRegistryLookupBridge.TagPairNames tags : List.of(MOJANG, INTERMEDIARY)) {
            ClassNode helper = node(LegacyRegistryLookupBridge.generateTagPairHelper(tags));
            for (MethodNode method : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
            List<String> calls = new ArrayList<>();
            for (MethodNode method : helper.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc + (call.itf ? " itf" : ""));
                }
            }
            assertTrue(calls.contains(tags.names().registry() + "." + tags.getTags() + STREAM + " itf"), calls.toString());
            assertTrue(calls.contains(tags.named() + "." + tags.key() + "()L" + tags.tagKey() + ";"), calls.toString());
            assertTrue(calls.contains(LegacyRegistryLookupBridge.PAIR + ".of(Ljava/lang/Object;Ljava/lang/Object;)L"
                    + LegacyRegistryLookupBridge.PAIR + ";"), calls.toString());
        }
    }

    @Test
    @DisplayName("the helper pairs each named set with its key, as getTags() did before 1.21.2")
    void helperPairsEachSetWithItsKey() throws Exception {
        String named = MOJANG.named();
        String registry = MOJANG.names().registry();
        ClassLoader loader = load(Map.of(
                MOJANG.helper(), LegacyRegistryLookupBridge.generateTagPairHelper(MOJANG),
                registry, registryInterface(registry),
                MOJANG.tagKey(), plainClass(MOJANG.tagKey()),
                named, namedSet(named, MOJANG.tagKey()),
                LegacyRegistryLookupBridge.PAIR, pair(),
                "test/tags/Items", registryImpl("test/tags/Items", registry, named, MOJANG.tagKey())));
        Class<?> helper = loader.loadClass(MOJANG.helper().replace('/', '.'));
        Object items = loader.loadClass("test.tags.Items").getConstructor().newInstance();

        List<?> pairs = ((Stream<?>) helper.getMethod("getTags", loader.loadClass(registry.replace('/', '.'))).invoke(null, items)).toList();

        assertEquals(2, pairs.size());
        for (Object pair : pairs) {
            Object set = pair.getClass().getMethod("getSecond").invoke(pair);
            assertEquals(named.replace('/', '.'), set.getClass().getName(), "the second is the named set itself");
            assertSame(set.getClass().getField("key").get(set), pair.getClass().getMethod("getFirst").invoke(pair),
                    "the first is that set's key");
        }
    }

    @Test
    @DisplayName("before a world binds tags, the helper streams none where the host refuses, as getTagNames() does")
    void helperStreamsNoneBeforeTagsBind() throws Exception {
        String registry = MOJANG.names().registry();
        ClassLoader loader = load(Map.of(
                MOJANG.helper(), LegacyRegistryLookupBridge.generateTagPairHelper(MOJANG),
                registry, registryInterface(registry),
                "test/tags/Unbound", unboundRegistry("test/tags/Unbound", registry)));
        Class<?> helper = loader.loadClass(MOJANG.helper().replace('/', '.'));
        Object unbound = loader.loadClass("test.tags.Unbound").getConstructor().newInstance();

        assertEquals(List.of(), ((Stream<?>) helper.getMethod("getTags", loader.loadClass(registry.replace('/', '.')))
                .invoke(null, unbound)).toList());
    }

    // ---- fixtures ----

    /** {@code Supplier<Stream> tags = registry::getTags}, a bound reference whose receiver the call site captures. */
    private static byte[] boundReference(String registry) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tags",
                "(L" + registry + ";)Ljava/util/function/Supplier;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("get", "(L" + registry + ";)Ljava/util/function/Supplier;", metafactory,
                Type.getMethodType("()Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKEINTERFACE, registry, "getTags", STREAM, true),
                Type.getMethodType(STREAM));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Handle handle(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle handle) return handle;
                }
            }
        }
        return fail(node.name + " has no method reference");
    }

    /** A registry whose getTags() throws "Tags not bound", as 26.x does before a world loads. */
    private static byte[] unboundRegistry(String name, String registry) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", new String[] {registry});
        constructor(cw, "java/lang/Object");
        MethodVisitor tags = cw.visitMethod(Opcodes.ACC_PUBLIC, "getTags", STREAM, null, null);
        tags.visitCode();
        tags.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        tags.visitInsn(Opcodes.DUP);
        tags.visitLdcInsn("Tags not bound");
        tags.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        tags.visitInsn(Opcodes.ATHROW);
        tags.visitMaxs(0, 0);
        tags.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private record Call(int opcode, String owner, boolean itf) {}

    /** A mod method that calls getTags() on each registry and drops the stream. */
    private static byte[] caller(LegacyRegistryLookupBridge.TagPairNames tags, Call... calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "list", "()V", null, null);
        mv.visitCode();
        for (Call call : calls) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, call.owner());
            mv.visitMethodInsn(call.opcode(), call.owner(), tags.getTags(), STREAM, call.itf());
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode registry(LegacyRegistryLookupBridge.TagPairNames tags, String signature) {
        ClassNode node = new ClassNode();
        node.name = tags.names().registry();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, tags.getTags(), STREAM, signature, null));
        return node;
    }

    private static byte[] registryInterface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getTags", STREAM, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] plainClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A named holder set whose key() answers its own {@code public final key} field, made in its constructor. */
    private static byte[] namedSet(String name, String tagKey) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "key", "L" + tagKey + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, tagKey);
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, tagKey, "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, "key", "L" + tagKey + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor key = cw.visitMethod(Opcodes.ACC_PUBLIC, "key", "()L" + tagKey + ";", null, null);
        key.visitCode();
        key.visitVarInsn(Opcodes.ALOAD, 0);
        key.visitFieldInsn(Opcodes.GETFIELD, name, "key", "L" + tagKey + ";");
        key.visitInsn(Opcodes.ARETURN);
        key.visitMaxs(0, 0);
        key.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** DataFixerUpper's Pair: {@code of(first, second)}, {@code getFirst()} and {@code getSecond()}. */
    private static byte[] pair() {
        String pair = LegacyRegistryLookupBridge.PAIR;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, pair, null, "java/lang/Object", null);
        for (String field : List.of("first", "second")) {
            cw.visitField(Opcodes.ACC_PUBLIC, field, "Ljava/lang/Object;", null, null).visitEnd();
            MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, field.equals("first") ? "getFirst" : "getSecond",
                    "()Ljava/lang/Object;", null, null);
            get.visitCode();
            get.visitVarInsn(Opcodes.ALOAD, 0);
            get.visitFieldInsn(Opcodes.GETFIELD, pair, field, "Ljava/lang/Object;");
            get.visitInsn(Opcodes.ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
        constructor(cw, "java/lang/Object");
        MethodVisitor of = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)L" + pair + ";", null, null);
        of.visitCode();
        of.visitTypeInsn(Opcodes.NEW, pair);
        of.visitInsn(Opcodes.DUP);
        of.visitMethodInsn(Opcodes.INVOKESPECIAL, pair, "<init>", "()V", false);
        of.visitVarInsn(Opcodes.ASTORE, 2);
        of.visitVarInsn(Opcodes.ALOAD, 2);
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitFieldInsn(Opcodes.PUTFIELD, pair, "first", "Ljava/lang/Object;");
        of.visitVarInsn(Opcodes.ALOAD, 2);
        of.visitVarInsn(Opcodes.ALOAD, 1);
        of.visitFieldInsn(Opcodes.PUTFIELD, pair, "second", "Ljava/lang/Object;");
        of.visitVarInsn(Opcodes.ALOAD, 2);
        of.visitInsn(Opcodes.ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A registry whose getTags() streams two new named sets, as 1.21.2 and later do. */
    private static byte[] registryImpl(String name, String registry, String named, String tagKey) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", new String[] {registry});
        constructor(cw, "java/lang/Object");
        MethodVisitor tags = cw.visitMethod(Opcodes.ACC_PUBLIC, "getTags", STREAM, null, null);
        tags.visitCode();
        for (int i = 0; i < 2; i++) {
            tags.visitTypeInsn(Opcodes.NEW, named);
            tags.visitInsn(Opcodes.DUP);
            tags.visitMethodInsn(Opcodes.INVOKESPECIAL, named, "<init>", "()V", false);
            tags.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "of",
                    "(Ljava/lang/Object;)Ljava/util/stream/Stream;", true);
        }
        tags.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "concat",
                "(Ljava/util/stream/Stream;Ljava/util/stream/Stream;)Ljava/util/stream/Stream;", true);
        tags.visitInsn(Opcodes.ARETURN);
        tags.visitMaxs(0, 0);
        tags.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static ClassLoader load(Map<String, byte[]> classes) {
        Map<String, byte[]> byName = new HashMap<>(classes);
        return new ClassLoader(LegacyRegistryTagPairsTest.class.getClassLoader().getParent()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = byName.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private ClassNode transform(byte[] bytes) {
        return node(transformer.transformClass(bytes, MOD_CLASS));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static void assertHelperCall(MethodInsnNode call, LegacyRegistryLookupBridge.TagPairNames tags) {
        String where = call.owner + "." + call.name + call.desc;
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), where);
        assertEquals(tags.helper(), call.owner, where);
        assertEquals("getTags", call.name, where);
        assertEquals(tags.names().helperDesc(STREAM), call.desc, where);
        assertFalse(call.itf, where);
    }
}
