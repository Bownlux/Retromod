/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 1.21.5 moved {@code Item.appendHoverText} from a {@code List} sink to {@code TooltipDisplay} plus a
 * {@code Consumer}. Found on Macaw's Bridges on 26.2: the "use pliers" info lines on its items were
 * gone, because the mod's override kept the old descriptor and Minecraft never called it.
 */
class LegacyTooltip1215Test {

    private static final String ITEM = "net/minecraft/world/item/Item";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old tooltip override gets a 1.21.5 bridge that forwards its lines")
    void oldOverrideIsBridged() {
        byte[] out = LegacyTooltipAdapter.apply(itemWithOldTooltip(false));

        MethodNode bridge = method(out, LegacyTooltipAdapter.NEW_DESC);
        assertNotNull(bridge, "Minecraft calls only the 1.21.5 descriptor");
        assertNotNull(call(bridge, "test/mod/InfoItem", LegacyTooltipAdapter.APPEND),
                "the bridge must call the mod's own override");
        assertNotNull(call(bridge, "java/lang/Iterable", "forEach"),
                "the collected lines must reach the consumer");
        assertStackValid(out);
    }

    @Test
    @DisplayName("an old super call is retargeted, since the old method no longer exists")
    void superCallIsRetargeted() {
        byte[] out = LegacyTooltipAdapter.apply(itemWithOldTooltip(true));

        MethodInsnNode superCall = call(method(out, LegacyTooltipAdapter.OLD_DESC), ITEM,
                LegacyTooltipAdapter.APPEND);
        assertEquals(LegacyTooltipAdapter.NEW_DESC, superCall.desc);
        assertNotNull(call(method(out, LegacyTooltipAdapter.OLD_DESC),
                LegacyTooltipSynthetic.INTERNAL, "sink"), "the list is handed over as a Consumer");
        assertStackValid(out);
    }

    @Test
    @DisplayName("the sink adds every accepted line to the old list")
    @SuppressWarnings("unchecked")
    void sinkFillsTheList() throws Exception {
        byte[] bytes = LegacyTooltipSynthetic.generate();
        Class<?> sinkClass = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(LegacyTooltipSynthetic.INTERNAL.replace('/', '.'),
                        bytes, 0, bytes.length);
            }
        }.define();

        List<Object> lines = new ArrayList<>();
        Consumer<Object> sink = (Consumer<Object>) sinkClass.getMethod("sink", List.class)
                .invoke(null, lines);
        sink.accept("first");
        sink.accept("second");
        assertEquals(List.of("first", "second"), lines);
    }

    @Test
    @DisplayName("the adapter stays off unless a 1.21.4 to 1.21.5 shim enabled it")
    void adapterIsGatedOnTheShim() {
        byte[] original = itemWithOldTooltip(false);
        // Any real shim chain registers redirects; with none the transformer skips post-processing.
        transformer.registerClassRedirect("test/Unrelated", "test/Renamed");
        byte[] untouched = transformer.transformClass(original, "test/mod/InfoItem");
        assertNull(method(untouched, LegacyTooltipAdapter.NEW_DESC),
                "a mod written for 1.21.5 or newer must not be changed");

        LegacyTooltipSynthetic.register(transformer);
        byte[] repaired = transformer.transformClass(original, "test/mod/InfoItem");
        assertNotNull(method(repaired, LegacyTooltipAdapter.NEW_DESC));
    }

    @Test
    @DisplayName("a super call through the mod's own base class is retargeted when the base has no override")
    void superCallThroughModBaseIsRetargeted() {
        byte[] base = plainSubclass("test/mod/ModBaseItem", ITEM, false);
        byte[] out = withJar(java.util.Map.of("test/mod/ModBaseItem", base),
                () -> LegacyTooltipAdapter.apply(itemCallingSuper("test/mod/ModBaseItem")));

        MethodInsnNode superCall = call(method(out, LegacyTooltipAdapter.OLD_DESC),
                "test/mod/ModBaseItem", LegacyTooltipAdapter.APPEND);
        assertEquals(LegacyTooltipAdapter.NEW_DESC, superCall.desc,
                "the call resolves up to Item, which only has the new descriptor");
        assertNotNull(method(out, LegacyTooltipAdapter.NEW_DESC));
        assertStackValid(out);
    }

    @Test
    @DisplayName("a super call to a mod base that declares the old method is left as written")
    void superCallToModOverrideIsKept() {
        byte[] base = plainSubclass("test/mod/ModBaseItem", ITEM, true);
        byte[] out = withJar(java.util.Map.of("test/mod/ModBaseItem", base),
                () -> LegacyTooltipAdapter.apply(itemCallingSuper("test/mod/ModBaseItem")));

        MethodInsnNode superCall = call(method(out, LegacyTooltipAdapter.OLD_DESC),
                "test/mod/ModBaseItem", LegacyTooltipAdapter.APPEND);
        assertEquals(LegacyTooltipAdapter.OLD_DESC, superCall.desc,
                "retargeting here would run the base's bridge and recurse into this override");
        assertNotNull(method(out, LegacyTooltipAdapter.NEW_DESC));
    }

    @Test
    @DisplayName("an override whose super call leaves the jar is not bridged")
    void unknownSuperOwnerIsNotBridged() {
        byte[] original = itemCallingSuper("other/mod/LibraryItem");
        byte[] out = withJar(java.util.Map.of(), () -> LegacyTooltipAdapter.apply(original));

        assertNull(method(out, LegacyTooltipAdapter.NEW_DESC),
                "running the old override could reach Item with the old descriptor and crash");
    }

    private static byte[] withJar(java.util.Map<String, byte[]> classes,
            java.util.function.Supplier<byte[]> work) {
        try (var scope = RetromodTransformer.getInstance().pushJarClassBytesProvider(classes::get)) {
            return work.get();
        }
    }

    /** {@code name extends superName}, optionally declaring the old tooltip override. */
    private static byte[] plainSubclass(String name, String superName, boolean declaresOld) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        if (declaresOld) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, LegacyTooltipAdapter.APPEND,
                    LegacyTooltipAdapter.OLD_DESC, null, null);
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An item extending {@code superName} whose old override calls super first. */
    private static byte[] itemCallingSuper(String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/InfoItem", null, superName, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, LegacyTooltipAdapter.APPEND,
                LegacyTooltipAdapter.OLD_DESC, null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKESPECIAL, superName, LegacyTooltipAdapter.APPEND,
                LegacyTooltipAdapter.OLD_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An item with the 1.20.5 to 1.21.4 tooltip override, optionally calling super first. */
    private static byte[] itemWithOldTooltip(boolean callsSuper) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/InfoItem", null, ITEM, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, LegacyTooltipAdapter.APPEND,
                LegacyTooltipAdapter.OLD_DESC, null, null);
        mv.visitCode();
        if (callsSuper) {
            for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(ALOAD, slot);
            mv.visitMethodInsn(INVOKESPECIAL, ITEM, LegacyTooltipAdapter.APPEND,
                    LegacyTooltipAdapter.OLD_DESC, false);
        }
        mv.visitVarInsn(ALOAD, 3);
        mv.visitLdcInsn("info line");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(byte[] bytes, String desc) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if (m.name.equals(LegacyTooltipAdapter.APPEND) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    private static MethodInsnNode call(MethodNode m, String owner, String name) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static void assertStackValid(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> failures = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            try {
                new Analyzer<>(new BasicVerifier()).analyze(cn.name, m);
            } catch (Exception e) {
                failures.add(m.name + m.desc + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
