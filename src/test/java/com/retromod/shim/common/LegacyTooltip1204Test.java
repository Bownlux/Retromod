/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
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
 * Until 1.20.5 {@code Item.appendHoverText} took the {@code Level}; from 1.20.5 it takes a
 * {@code TooltipContext}, and from 1.21.5 a {@code TooltipDisplay} and a {@code Consumer} as well.
 * A Forge 1.20.1 item's tooltip override therefore never ran on a newer host, and its super call
 * named a method that no longer exists. Listed in the gap report for Cake Cow on NeoForge 26.1.2.
 */
class LegacyTooltip1204Test {

    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String CONTEXT = "net/minecraft/world/item/Item$TooltipContext";
    private static final String DISPLAY = "net/minecraft/world/item/component/TooltipDisplay";
    private static final String INFO_ITEM = "test/mod/ShakeItem";

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
    @DisplayName("an old Level override gets the 1.20.5 override, and its super call a context")
    void levelOverrideIsBridgedToTheContextShape() {
        byte[] out = LegacyTooltipAdapter.applyLevelShape(itemWithLevelTooltip(), false);

        MethodNode bridge = method(out, LegacyTooltipAdapter.OLD_DESC);
        assertNotNull(bridge, "1.20.5 to 1.21.4 hosts call the context descriptor");
        assertNotNull(call(bridge, INFO_ITEM, LegacyTooltipAdapter.APPEND), "the bridge runs the mod's override");
        assertNull(call(bridge, CONTEXT, "level"), "vanilla's context has no level() to call");

        MethodNode old = method(out, LegacyTooltipAdapter.LEVEL_DESC);
        assertEquals(LegacyTooltipAdapter.OLD_DESC, call(old, ITEM, LegacyTooltipAdapter.APPEND).desc,
                "the super call must use a descriptor the host still has");
        assertNotNull(call(old, CONTEXT, "of"), "the old level is turned into a context");
        assertStackValid(out);
    }

    @Test
    @DisplayName("on NeoForge the bridge hands the old method the context's level")
    void neoForgeContextSuppliesTheLevel() {
        byte[] out = LegacyTooltipAdapter.applyLevelShape(itemWithLevelTooltip(), true);

        assertNotNull(call(method(out, LegacyTooltipAdapter.OLD_DESC), CONTEXT, "level"));
        assertStackValid(out);
    }

    @Test
    @DisplayName("on a 26.x host the old override's lines and its parent's lines reach the tooltip")
    void levelOverrideLinesReachTheNewestTooltip() throws Exception {
        transformer.enablePostProcess(LegacyTooltipAdapter.LEVEL_FLAG);
        LegacyTooltipSynthetic.register(transformer);
        byte[] out = transformer.transformClass(itemWithLevelTooltip(), INFO_ITEM + ".class");
        assertNotNull(method(out, LegacyTooltipAdapter.NEW_DESC), "26.x calls only the 1.21.5 descriptor");

        HostStubs stubs = new HostStubs()
                .add(itemAddingALine())
                .add(contextInterface())
                .add(HostStubs.singleton(DISPLAY))
                .add(LegacyTooltipSynthetic.INTERNAL, LegacyTooltipSynthetic.generate())
                .add(out);
        Object item = stubs.load(INFO_ITEM).getConstructor().newInstance();
        List<Object> lines = new ArrayList<>();
        Consumer<Object> tooltip = lines::add;
        stubs.load(INFO_ITEM).getMethod(LegacyTooltipAdapter.APPEND, stubs.load("net/minecraft/world/item/ItemStack"),
                        stubs.load(CONTEXT), stubs.load(DISPLAY), Consumer.class,
                        stubs.load("net/minecraft/world/item/TooltipFlag"))
                .invoke(item, null, null, null, tooltip, null);

        assertEquals(List.of("item line", "shake line"), lines);
    }

    @Test
    @DisplayName("an override whose super call leaves the jar is not bridged")
    void unknownParentIsNotBridged() {
        byte[] original = itemWithLevelTooltip("other/mod/LibraryItem");
        byte[] out;
        try (var scope = transformer.pushJarClassBytesProvider(name -> null)) {
            out = LegacyTooltipAdapter.applyLevelShape(original, false);
        }

        assertNull(method(out, LegacyTooltipAdapter.OLD_DESC),
                "running the old override could reach Item with the Level descriptor and crash");
    }

    // --- classes under test --------------------------------------------------------------------

    private static byte[] itemWithLevelTooltip() {
        return itemWithLevelTooltip(ITEM);
    }

    /** {@code super.appendHoverText(stack, level, list, flag); list.add("shake line");} */
    private static byte[] itemWithLevelTooltip(String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, INFO_ITEM, null, superName, null);
        HostStubs.defaultConstructor(cw, superName);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, LegacyTooltipAdapter.APPEND,
                LegacyTooltipAdapter.LEVEL_DESC, null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKESPECIAL, superName, LegacyTooltipAdapter.APPEND,
                LegacyTooltipAdapter.LEVEL_DESC, false);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitLdcInsn("shake line");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The host's {@code Item}, whose 1.21.5 tooltip adds one line of its own. */
    private static byte[] itemAddingALine() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ITEM, null, "java/lang/Object", null);
        HostStubs.defaultConstructor(cw, "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, LegacyTooltipAdapter.APPEND,
                LegacyTooltipAdapter.NEW_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 4);
        mv.visitLdcInsn("item line");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Vanilla's {@code TooltipContext}: {@code of(Level)}, and no {@code level()}. */
    private static byte[] contextInterface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, CONTEXT, null, "java/lang/Object", null);
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of",
                "(Lnet/minecraft/world/level/Level;)L" + CONTEXT + ";", null, null);
        of.visitCode();
        of.visitInsn(ACONST_NULL);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // --- bytecode inspection -------------------------------------------------------------------

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
            if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) return c;
        }
        return null;
    }

    private static void assertStackValid(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            try {
                new Analyzer<>(new BasicVerifier()).analyze(cn.name, m);
            } catch (Exception e) {
                fail(cn.name + "." + m.name + m.desc + ": " + e.getMessage());
            }
        }
    }
}
