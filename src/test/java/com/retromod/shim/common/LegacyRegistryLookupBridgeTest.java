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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Carry On reads its tag lists through Registry.getTagNames() and its model overrides through asLookup() while configs load. */
class LegacyRegistryLookupBridgeTest {

    private static final String MOD_CLASS = "test/tags/ListHandler";
    private static final String STREAM = LegacyRegistryLookupBridge.STREAM;
    private static final LegacyRegistryLookupBridge.Names MOJANG = LegacyRegistryLookupBridge.MOJANG;
    private static final LegacyRegistryLookupBridge.Names INTERMEDIARY = LegacyRegistryLookupBridge.INTERMEDIARY;
    private static final LegacyRegistryLookupBridge.Dropped BOTH = new LegacyRegistryLookupBridge.Dropped(true, true);

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Mojang names: getTagNames() and asLookup() on Registry, DefaultedRegistry and MappedRegistry pass the registry to the helper")
    void mojangCallsReachTheHelper() {
        LegacyRegistryLookupBridge.registerRedirects(transformer, MOJANG, BOTH);

        List<MethodInsnNode> calls = calls(transform(caller(MOJANG,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/DefaultedRegistry", true),
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/Registry", true),
                new Call(Opcodes.INVOKEVIRTUAL, "net/minecraft/core/MappedRegistry", false))));

        assertEquals(6, calls.size(), calls.toString());
        for (MethodInsnNode call : calls) assertHelperCall(call, MOJANG);
    }

    @Test
    @DisplayName("intermediary names: a pre-26.1 Fabric host's DefaultedRegistry calls reach the intermediary helper")
    void intermediaryCallsReachTheHelper() {
        LegacyRegistryLookupBridge.registerRedirects(transformer, INTERMEDIARY, BOTH);

        List<MethodInsnNode> calls = calls(transform(caller(INTERMEDIARY,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/class_7922", true),
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/class_2378", true))));

        assertEquals(4, calls.size(), calls.toString());
        for (MethodInsnNode call : calls) assertHelperCall(call, INTERMEDIARY);
    }

    @Test
    @DisplayName("the helpers verify, and call the host's lookup method as an interface method")
    void helpersVerify() throws Exception {
        for (LegacyRegistryLookupBridge.Names names : List.of(MOJANG, INTERMEDIARY)) {
            ClassNode helper = node(LegacyRegistryLookupBridge.generateHelper(names, BOTH));
            for (MethodNode method : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
            MethodInsnNode lookup = methodCalls(helper).stream().filter(c -> c.name.equals(names.listTagIds())).findFirst()
                    .orElseGet(() -> fail(names.helper() + " never calls " + names.listTagIds()));
            assertEquals(Opcodes.INVOKEINTERFACE, lookup.getOpcode());
            assertEquals(names.registry(), lookup.owner);
            assertTrue(lookup.itf);
        }
    }

    @Test
    @DisplayName("the helper lists the tag ids once tags are bound, none while 26.x still refuses to, and answers the registry as its lookup")
    void helperAnswersBoundAndUnboundRegistries() throws Exception {
        ClassLoader loader = load(Map.of(MOJANG.helper(), LegacyRegistryLookupBridge.generateHelper(MOJANG, BOTH),
                MOJANG.lookup(), hostInterface(MOJANG.lookup(), null), MOJANG.registry(), hostInterface(MOJANG.registry(), MOJANG.lookup()),
                "test/tags/Bound", registryImpl("test/tags/Bound", false), "test/tags/Unbound", registryImpl("test/tags/Unbound", true)));
        Class<?> helper = loader.loadClass(MOJANG.helper().replace('/', '.'));
        Class<?> registry = loader.loadClass(MOJANG.registry().replace('/', '.'));
        Object bound = loader.loadClass("test.tags.Bound").getConstructor().newInstance();
        Object unbound = loader.loadClass("test.tags.Unbound").getConstructor().newInstance();

        assertEquals(List.of("forge:ores"), ((Stream<?>) helper.getMethod(MOJANG.tagNames(), registry).invoke(null, bound)).toList());
        assertEquals(List.of(), ((Stream<?>) helper.getMethod(MOJANG.tagNames(), registry).invoke(null, unbound)).toList(),
                "a registry whose tags are not bound yet lists none instead of stopping the config load");
        assertSame(bound, helper.getMethod(MOJANG.asLookup(), registry).invoke(null, bound),
                "since 1.21.2 the registry is its own lookup");
    }

    @Test
    @DisplayName("the host shape decides each member: a 1.21.1 Registry keeps both, a 26.x Registry has only the lookup")
    void hostShapeDecides() {
        assertEquals(new LegacyRegistryLookupBridge.Dropped(false, false),
                LegacyRegistryLookupBridge.droppedIn(registry(MOJANG, true, true, false), MOJANG), "1.21.1 keeps both");
        assertEquals(BOTH, LegacyRegistryLookupBridge.droppedIn(registry(MOJANG, false, false, true), MOJANG),
                "1.21.2 and 26.x extend the lookup and lost both");
        assertEquals(new LegacyRegistryLookupBridge.Dropped(false, true),
                LegacyRegistryLookupBridge.droppedIn(registry(MOJANG, true, false, true), MOJANG), "each member is checked on its own");
        assertEquals(new LegacyRegistryLookupBridge.Dropped(false, false),
                LegacyRegistryLookupBridge.droppedIn(registry(MOJANG, false, false, false), MOJANG),
                "without the lookup there is nothing to call instead");
        assertEquals(BOTH, LegacyRegistryLookupBridge.droppedIn(registry(INTERMEDIARY, false, false, true), INTERMEDIARY));
    }

    @Test
    @DisplayName("offline, with no host to read, a target before 1.21.2 keeps the old calls")
    void offlineFollowsTheTarget() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyRegistryLookupBridge.register(transformer);
        // An unrelated redirect, so method bodies are still visited.
        transformer.registerMethodRedirect("test/tags/Old", "old", "()V", "test/tags/New", "now", "()V");
        for (MethodInsnNode kept : calls(transform(caller(MOJANG,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/DefaultedRegistry", true))))) {
            assertEquals("net/minecraft/core/DefaultedRegistry", kept.owner);
        }

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyRegistryLookupBridge.register(transformer);
        for (MethodInsnNode call : calls(transform(caller(MOJANG,
                new Call(Opcodes.INVOKEINTERFACE, "net/minecraft/core/DefaultedRegistry", true))))) {
            assertHelperCall(call, MOJANG);
        }
    }

    // ---- fixtures ----

    private record Call(int opcode, String owner, boolean itf) {}

    /** A mod method that calls getTagNames() and asLookup() on each registry and drops the results. */
    private static byte[] caller(LegacyRegistryLookupBridge.Names names, Call... calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "()V", null, null);
        mv.visitCode();
        for (Call call : calls) {
            for (String[] member : new String[][] {{names.tagNames(), STREAM}, {names.asLookup(), names.asLookupDesc()}}) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitTypeInsn(Opcodes.CHECKCAST, call.owner());
                mv.visitMethodInsn(call.opcode(), call.owner(), member[0], member[1], call.itf());
                mv.visitInsn(Opcodes.POP);
            }
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A stand-in host interface: the lookup with {@code listTagIds()}, or Registry extending it. */
    private static byte[] hostInterface(String name, String parent) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null,
                "java/lang/Object", parent == null ? null : new String[] {parent});
        if (parent == null) cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, MOJANG.listTagIds(), STREAM, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A registry whose {@code listTagIds()} lists one tag, or throws "Tags not bound" as 26.x does before a world loads. */
    private static byte[] registryImpl(String name, boolean unbound) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object",
                new String[] {MOJANG.registry()});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor list = cw.visitMethod(Opcodes.ACC_PUBLIC, MOJANG.listTagIds(), STREAM, null, null);
        list.visitCode();
        if (unbound) {
            list.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            list.visitInsn(Opcodes.DUP);
            list.visitLdcInsn("Tags not bound");
            list.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
            list.visitInsn(Opcodes.ATHROW);
        } else {
            list.visitLdcInsn("forge:ores");
            list.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "of",
                    "(Ljava/lang/Object;)Ljava/util/stream/Stream;", true);
            list.visitInsn(Opcodes.ARETURN);
        }
        list.visitMaxs(0, 0);
        list.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassLoader load(Map<String, byte[]> classes) {
        Map<String, byte[]> byName = new HashMap<>(classes);
        return new ClassLoader(LegacyRegistryLookupBridgeTest.class.getClassLoader().getParent()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = byName.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** A Registry class node in the given names: with or without each old member and the lookup interface. */
    private static ClassNode registry(LegacyRegistryLookupBridge.Names names, boolean tagNames, boolean asLookup, boolean lookup) {
        ClassNode node = new ClassNode();
        node.name = names.registry();
        node.interfaces = new ArrayList<>(lookup ? List.of(names.lookup()) : List.of());
        if (tagNames) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, names.tagNames(), STREAM, null, null));
        if (asLookup) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, names.asLookup(), names.asLookupDesc(), null, null));
        }
        return node;
    }

    private ClassNode transform(byte[] bytes) {
        return node(transformer.transformClass(bytes, MOD_CLASS));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** The mod's calls to either old member, by name, wherever they now point. */
    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodInsnNode call : methodCalls(node)) {
            if (List.of(MOJANG.tagNames(), MOJANG.asLookup(), INTERMEDIARY.tagNames(), INTERMEDIARY.asLookup()).contains(call.name)) {
                calls.add(call);
            }
        }
        return calls;
    }

    private static List<MethodInsnNode> methodCalls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static void assertHelperCall(MethodInsnNode call, LegacyRegistryLookupBridge.Names names) {
        String where = call.owner + "." + call.name + call.desc;
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), where);
        assertEquals(names.helper(), call.owner, where);
        String oldDesc = call.name.equals(names.tagNames()) ? STREAM : names.asLookupDesc();
        assertEquals(names.helperDesc(oldDesc), call.desc, where);
        assertFalse(call.itf, where);
    }
}
