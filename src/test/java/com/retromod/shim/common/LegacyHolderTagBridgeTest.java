/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Supplementaries checks every potion against a tag during common setup. 1.21.1 answered "not in the tag" before tags
 * loaded; 1.21.2 and newer throw "Tags not bound", which skipped the rest of Moonlight's setup on Fabric 26.3.
 */
class LegacyHolderTagBridgeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a holder whose tags are not bound is in no tag, and a bound holder answers as the host does")
    void unboundHolderIsInNoTag() throws Exception {
        HostStubs host = new HostStubs()
                .add(holderInterface())
                .add(LegacyHolderTagBridge.TAGS, Files.readAllBytes(COMPILED.resolve(LegacyHolderTagBridge.TAGS + ".class")))
                .add(holder("test/UnboundHolder", "Tags not bound"))
                .add(holder("test/BrokenHolder", "something else"))
                .add(holder("test/BoundHolder", null));
        Method is = host.method(LegacyHolderTagBridge.TAGS, "is");
        Object tag = host.load(TAG_KEY).getConstructor().newInstance();

        assertEquals(false, is.invoke(null, host.load("test/UnboundHolder").getConstructor().newInstance(), tag));
        assertEquals(true, is.invoke(null, host.load("test/BoundHolder").getConstructor().newInstance(), tag));
        var other = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> is.invoke(null, host.load("test/BrokenHolder").getConstructor().newInstance(), tag));
        assertEquals("something else", other.getCause().getMessage(), "any other host failure is passed on");
    }

    @Test
    @DisplayName("a mod built for 1.21.2 or newer keeps the plain call, since it expects the host's behavior")
    void newerModIsLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHolderTagBridge.register(transformer);
        transformer.setModSourceVersion("1.21.4");

        byte[] kept = transformer.transformClass(tagCheck(), "mod/ArrowSetup");

        assertEquals(HOLDER, onlyCall(kept).owner);
    }

    @Test
    @DisplayName("an old mod's tag check on a holder goes through the wrapper on a 26.3 host")
    void oldTagCheckIsRedirected() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHolderTagBridge.register(transformer);
        transformer.setModSourceVersion("1.21.1");

        byte[] fixed = transformer.transformClass(tagCheck(), "mod/ArrowSetup");

        MethodInsnNode call = onlyCall(fixed);
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyHolderTagBridge.TAGS, call.owner);
        assertEquals(LegacyHolderTagBridge.HELPER_DESC, call.desc);
    }

    @Test
    @DisplayName("a 1.21.1 host, whose unbound holders already answer false, keeps the call")
    void oldHostIsLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHolderTagBridge.register(transformer);

        byte[] kept = transformer.transformClass(tagCheck(), "mod/ArrowSetup");

        assertEquals(HOLDER, onlyCall(kept).owner);
    }

    @Test
    @DisplayName("an old mod iterating an unbound tag's set sees it empty, as 1.21.1 did, and a bound one in full")
    void unboundHolderSetReadsEmpty() throws Exception {
        HostStubs host = new HostStubs()
                .add(holderSetInterface())
                .add(holderSet("test/Unbound", false))
                .add(holderSet("test/Bound", true))
                .add(LegacyHolderTagBridge.SETS, Files.readAllBytes(COMPILED.resolve(LegacyHolderTagBridge.SETS + ".class")));
        Class<?> sets = host.load(LegacyHolderTagBridge.SETS);
        Class<?> setType = host.load(LegacyHolderTagBridge.HOLDER_SET);
        Object unbound = host.load("test/Unbound").getConstructor().newInstance();
        Object bound = host.load("test/Bound").getConstructor().newInstance();

        assertFalse(((java.util.Iterator<?>) sets.getMethod("iterator", setType).invoke(null, unbound)).hasNext());
        assertEquals(0, sets.getMethod("size", setType).invoke(null, unbound));
        assertEquals(0L, ((java.util.stream.Stream<?>) sets.getMethod("stream", setType).invoke(null, unbound)).count());
        assertEquals(List.of("a"), List.copyOf(com.google.common.collect.Lists.newArrayList(
                (java.util.Iterator<?>) sets.getMethod("iterator", setType).invoke(null, bound))));
        assertEquals(1, sets.getMethod("size", setType).invoke(null, bound));
    }

    @Test
    @DisplayName("an old mod's holder set iteration goes through the wrapper, and a 1.21.4 mod's stays the host's")
    void holderSetIterationIsRedirectedForOldModsOnly() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyHolderTagBridge.register(transformer);

        transformer.setModSourceVersion("1.21.1");
        MethodInsnNode old = onlyCall(transformer.transformClass(setIteration(), "mod/BurnTimes"));
        transformer.setModSourceVersion("1.21.4");
        MethodInsnNode current = onlyCall(transformer.transformClass(setIteration(), "mod/BurnTimes"));

        assertEquals(LegacyHolderTagBridge.SETS + ".iterator", old.owner + "." + old.name);
        assertEquals(INVOKESTATIC, old.getOpcode());
        assertEquals(LegacyHolderTagBridge.HOLDER_SET, current.owner);
    }

    @Test
    @DisplayName("an unbound tag's set contains a holder whose own tags carry the tag, and not one whose tags are unbound")
    void namedSetContainsAsksTheHolder() throws Exception {
        HostStubs host = new HostStubs()
                .add(holderInterface())
                .add(holder("test/UnboundHolder", "Tags not bound"))
                .add(holder("test/BoundHolder", null))
                .add(holderSetInterface())
                .add(namedSet())
                .add(LegacyHolderTagBridge.TAGS, Files.readAllBytes(COMPILED.resolve(LegacyHolderTagBridge.TAGS + ".class")))
                .add(LegacyHolderTagBridge.SETS, Files.readAllBytes(COMPILED.resolve(LegacyHolderTagBridge.SETS + ".class")));
        Method contains = host.load(LegacyHolderTagBridge.SETS).getMethod("contains",
                host.load(LegacyHolderTagBridge.HOLDER_SET), host.load(HOLDER));
        Object set = host.load("test/NamedSet").getConstructor().newInstance();

        assertEquals(true, contains.invoke(null, set, host.load("test/BoundHolder").getConstructor().newInstance()));
        assertEquals(false, contains.invoke(null, set, host.load("test/UnboundHolder").getConstructor().newInstance()));
    }

    /** A tag's set that is not bound: unwrapKey names its tag, and its own contains throws as 26.3's does. */
    private static byte[] namedSet() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/NamedSet", null, "java/lang/Object", new String[]{LegacyHolderTagBridge.HOLDER_SET});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor key = cw.visitMethod(ACC_PUBLIC, "unwrapKey", "()Ljava/util/Optional;", null, null);
        key.visitCode();
        key.visitTypeInsn(NEW, TAG_KEY);
        key.visitInsn(DUP);
        key.visitMethodInsn(INVOKESPECIAL, TAG_KEY, "<init>", "()V", false);
        key.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        key.visitInsn(ARETURN);
        key.visitMaxs(0, 0);
        key.visitEnd();
        MethodVisitor contains = cw.visitMethod(ACC_PUBLIC, "contains", "(L" + HOLDER + ";)Z", null, null);
        contains.visitCode();
        contains.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        contains.visitInsn(DUP);
        contains.visitLdcInsn("Trying to access unbound tag");
        contains.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        contains.visitInsn(ATHROW);
        contains.visitMaxs(0, 0);
        contains.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code interface HolderSet extends Iterable { boolean isBound(); int size(); Stream stream(); }} */
    private static byte[] holderSetInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, LegacyHolderTagBridge.HOLDER_SET, null, "java/lang/Object",
                new String[]{"java/lang/Iterable"});
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "isBound", "()Z", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "size", "()I", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "stream", "()Ljava/util/stream/Stream;", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "unwrapKey", "()Ljava/util/Optional;", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "contains", "(L" + HOLDER + ";)Z", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A set holding "a" when bound; unbound, every read throws as 26.3's unbound tag does. */
    private static byte[] holderSet(String name, boolean bound) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", new String[]{LegacyHolderTagBridge.HOLDER_SET});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor isBound = cw.visitMethod(ACC_PUBLIC, "isBound", "()Z", null, null);
        isBound.visitCode();
        isBound.visitInsn(bound ? ICONST_1 : ICONST_0);
        isBound.visitInsn(IRETURN);
        isBound.visitMaxs(0, 0);
        isBound.visitEnd();
        String[][] reads = {{"iterator", "()Ljava/util/Iterator;"}, {"size", "()I"}, {"stream", "()Ljava/util/stream/Stream;"}};
        for (String[] read : reads) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, read[0], read[1], null, null);
            mv.visitCode();
            if (!bound) {
                mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
                mv.visitInsn(DUP);
                mv.visitLdcInsn("Trying to access unbound tag");
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
                mv.visitInsn(ATHROW);
            } else if (read[0].equals("size")) {
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IRETURN);
            } else {
                mv.visitLdcInsn("a");
                mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", read[0], read[1], true);
                mv.visitInsn(ARETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Iterator burnables(HolderSet tag) { return tag.iterator(); }} */
    private static byte[] setIteration() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/BurnTimes", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "burnables",
                "(L" + LegacyHolderTagBridge.HOLDER_SET + ";)Ljava/util/Iterator;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, LegacyHolderTagBridge.HOLDER_SET, "iterator", "()Ljava/util/Iterator;", true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code interface Holder { boolean is(TagKey tag); }} */
    private static byte[] holderInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, HOLDER, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "is", LegacyHolderTagBridge.IS_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A holder that throws with {@code failure}, as 1.21.2 and newer do for unbound tags, or one in every tag. */
    private static byte[] holder(String name, String failure) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", new String[]{HOLDER});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor is = cw.visitMethod(ACC_PUBLIC, "is", LegacyHolderTagBridge.IS_DESC, null, null);
        is.visitCode();
        if (failure != null) {
            is.visitTypeInsn(NEW, "java/lang/IllegalStateException");
            is.visitInsn(DUP);
            is.visitLdcInsn(failure);
            is.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
            is.visitInsn(ATHROW);
        } else {
            is.visitInsn(ICONST_1);
            is.visitInsn(IRETURN);
        }
        is.visitMaxs(0, 0);
        is.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static boolean blacklisted(Holder potion, TagKey tag) { return potion.is(tag); }} */
    private static byte[] tagCheck() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/ArrowSetup", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blacklisted", LegacyHolderTagBridge.HELPER_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, HOLDER, "is", LegacyHolderTagBridge.IS_DESC, true);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call in " + node.name);
    }
}
