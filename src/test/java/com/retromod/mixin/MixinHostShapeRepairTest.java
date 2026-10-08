/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Mixin members whose host member kept its name but changed type on 26.1. */
class MixinHostShapeRepairTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String FLOWING_FLUID = "net/minecraft/world/level/material/FlowingFluid";
    private static final String OLD_CONVERT = "(L" + LEVEL + ";)Z";
    private static final String NEW_CONVERT = "(L" + SERVER_LEVEL + ";)Z";
    private static final String CONTENTS = MixinContainerItemsAdapter.CONTENTS;
    private static final String NON_NULL_LIST = "net/minecraft/core/NonNullList";
    private static final String STACK = "net/minecraft/world/item/ItemStack";

    @TempDir
    Path tempDir;

    private FuzzyMethodResolver host;

    @BeforeEach
    void indexHost() throws IOException {
        Path jar = tempDir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(LEVEL, "java/lang/Object", c -> {}));
            put(out, hostClass(SERVER_LEVEL, LEVEL, c -> {}));
            put(out, hostClass(FLOWING_FLUID, "java/lang/Object", c -> {
                c.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "canConvertToSource",
                        NEW_CONVERT, null, null).visitEnd();
                c.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "getDropOff",
                        "(L" + LEVEL + ";)I", null, null).visitEnd();
            }));
            put(out, hostClass(CONTENTS, "java/lang/Object", c -> c.visitField(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "items", "Ljava/util/List;", null,
                    null).visitEnd()));
        }
        host = new FuzzyMethodResolver();
        host.indexJar(jar);
        assertTrue(host.isIndexed(), "the synthetic host jar must be indexed");
    }

    @Test
    void invokerFollowsATargetThatNarrowedLevelToServerLevel() {
        ClassNode accessor = accessor("callCanConvertToSource", OLD_CONVERT);

        assertTrue(MixinInvokerNarrowingRepair.apply(accessor, host));

        MethodNode invoker = method(accessor, "callCanConvertToSource", NEW_CONVERT);
        assertNotNull(invoker, "the invoker takes the host's ServerLevel parameter");
        assertTrue((invoker.access & Opcodes.ACC_ABSTRACT) != 0, "the invoker stays abstract");
        assertEquals(1, accessor.methods.size(),
                "no old-descriptor bridge is added: any non-accessor method would turn the "
                        + "accessor into a plain interface Mixin the mod can no longer load");
    }

    @Test
    void invokerThatStillMatchesTheHostIsLeftAlone() {
        ClassNode accessor = accessor("callGetDropOff", "(L" + LEVEL + ";)I");

        assertFalse(MixinInvokerNarrowingRepair.apply(accessor, host),
                "getDropOff(Level) still exists, so nothing changes");
        assertEquals(1, accessor.methods.size());
    }

    @Test
    void invokerWhoseTargetWidenedIsNotRetyped() {
        ClassNode accessor = accessor("callCanConvertToSource",
                "(Lnet/minecraft/server/level/ServerLevelSubtype;)Z");

        assertFalse(MixinInvokerNarrowingRepair.apply(accessor, host),
                "only a host parameter that is a subtype of the old one is a provable narrowing");
    }

    @Test
    void containerShadowReadsTheHostTemplateList() {
        ClassNode mixin = containerMixin(c -> {
            MethodVisitor slots = c.visitMethod(Opcodes.ACC_PUBLIC, "slots", "()I", null, null);
            slots.visitCode();
            slots.visitVarInsn(Opcodes.ALOAD, 0);
            readItems(slots);
            slots.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NON_NULL_LIST, "size", "()I", false);
            slots.visitInsn(Opcodes.IRETURN);
            slots.visitMaxs(1, 1);
            slots.visitEnd();

            MethodVisitor stack = c.visitMethod(Opcodes.ACC_PUBLIC, "stackInSlot",
                    "(I)L" + STACK + ";", null, null);
            stack.visitCode();
            stack.visitVarInsn(Opcodes.ALOAD, 0);
            readItems(stack);
            stack.visitVarInsn(Opcodes.ILOAD, 1);
            stack.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NON_NULL_LIST, "get",
                    "(I)Ljava/lang/Object;", false);
            stack.visitTypeInsn(Opcodes.CHECKCAST, STACK);
            stack.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STACK, "copy", "()L" + STACK + ";", false);
            stack.visitInsn(Opcodes.ARETURN);
            stack.visitMaxs(2, 2);
            stack.visitEnd();
        });

        assertTrue(MixinContainerItemsAdapter.apply(mixin, host));

        FieldNode shadow = mixin.fields.get(0);
        assertEquals("Ljava/util/List;", shadow.desc, "the shadow matches the 26.1 field");
        MethodInsnNode size = calls(method(mixin, "slots", "()I")).get(0);
        assertEquals("java/util/List", size.owner, "size() reads the host list directly");
        assertEquals(Opcodes.INVOKEINTERFACE, size.getOpcode());
        List<MethodInsnNode> get = calls(method(mixin, "stackInSlot", "(I)L" + STACK + ";"));
        assertEquals("java/util/List", get.get(0).owner);
        assertEquals(MixinContainerItemsAdapter.SLOT_STACK, get.get(1).name,
                "the slot's template becomes a fresh stack before the mod copies it");
        assertNotNull(method(mixin, MixinContainerItemsAdapter.SLOT_STACK,
                MixinContainerItemsAdapter.SLOT_STACK_DESC));
        assertTrue(method(mixin, MixinContainerItemsAdapter.LEGACY_ITEMS,
                MixinContainerItemsAdapter.LEGACY_ITEMS_DESC) == null,
                "no full copy is generated when every read is a size or slot read");
        for (MethodNode m : mixin.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode field && "items".equals(field.name)) {
                    assertEquals("Ljava/util/List;", field.desc, "every read uses the host type");
                }
            }
        }
        verify(mixin);
    }

    @Test
    void containerShadowPassedElsewhereGetsAFullLegacyCopy() {
        ClassNode mixin = containerMixin(c -> {
            MethodVisitor all = c.visitMethod(Opcodes.ACC_PUBLIC, "all",
                    "()L" + NON_NULL_LIST + ";", null, null);
            all.visitCode();
            all.visitVarInsn(Opcodes.ALOAD, 0);
            readItems(all);
            all.visitInsn(Opcodes.ARETURN);
            all.visitMaxs(1, 1);
            all.visitEnd();
        });

        assertTrue(MixinContainerItemsAdapter.apply(mixin, host));

        List<MethodInsnNode> calls = calls(method(mixin, "all", "()L" + NON_NULL_LIST + ";"));
        assertEquals(MixinContainerItemsAdapter.LEGACY_ITEMS, calls.get(0).name,
                "a list that escapes the method is converted to the old NonNullList shape");
        assertNotNull(method(mixin, MixinContainerItemsAdapter.LEGACY_ITEMS,
                MixinContainerItemsAdapter.LEGACY_ITEMS_DESC));
        verify(mixin);
    }

    @Test
    void containerShadowDuplicatedOnTheStackGetsAFullLegacyCopy() {
        ClassNode mixin = containerMixin(c -> {
            MethodVisitor both = c.visitMethod(Opcodes.ACC_PUBLIC, "both", "()I", null, null);
            both.visitCode();
            both.visitVarInsn(Opcodes.ALOAD, 0);
            readItems(both);
            both.visitInsn(Opcodes.DUP);
            both.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NON_NULL_LIST, "size", "()I", false);
            both.visitInsn(Opcodes.POP);
            both.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NON_NULL_LIST, "size", "()I", false);
            both.visitInsn(Opcodes.IRETURN);
            both.visitMaxs(2, 1);
            both.visitEnd();
        });

        assertTrue(MixinContainerItemsAdapter.apply(mixin, host));

        List<MethodInsnNode> calls = calls(method(mixin, "both", "()I"));
        assertEquals(MixinContainerItemsAdapter.LEGACY_ITEMS, calls.get(0).name,
                "a read that is copied on the stack reaches two consumers, so it gets the "
                        + "full legacy list rather than a partial retarget");
        assertEquals(NON_NULL_LIST, calls.get(1).owner, "both size() calls keep NonNullList");
        assertEquals(NON_NULL_LIST, calls.get(2).owner);
        verify(mixin);
    }

    @Test
    void containerShadowLiveAcrossAStackMapFrameGetsAFullLegacyCopy() {
        // items.get(flag ? 0 : 1): the list sits on the stack through two declared frames.
        String mixinName = "mod/ItemContainerContentsMixin";
        ClassNode mixin = containerMixin(c -> {
            MethodVisitor pick = c.visitMethod(Opcodes.ACC_PUBLIC, "pick", "(Z)L" + STACK + ";",
                    null, null);
            Label second = new Label();
            Label index = new Label();
            pick.visitCode();
            pick.visitVarInsn(Opcodes.ALOAD, 0);
            readItems(pick);
            pick.visitVarInsn(Opcodes.ILOAD, 1);
            pick.visitJumpInsn(Opcodes.IFEQ, second);
            pick.visitInsn(Opcodes.ICONST_0);
            pick.visitJumpInsn(Opcodes.GOTO, index);
            pick.visitLabel(second);
            pick.visitFrame(Opcodes.F_FULL, 2, new Object[] {mixinName, Opcodes.INTEGER},
                    1, new Object[] {NON_NULL_LIST});
            pick.visitInsn(Opcodes.ICONST_1);
            pick.visitLabel(index);
            pick.visitFrame(Opcodes.F_FULL, 2, new Object[] {mixinName, Opcodes.INTEGER},
                    2, new Object[] {NON_NULL_LIST, Opcodes.INTEGER});
            pick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NON_NULL_LIST, "get",
                    "(I)Ljava/lang/Object;", false);
            pick.visitTypeInsn(Opcodes.CHECKCAST, STACK);
            pick.visitInsn(Opcodes.ARETURN);
            pick.visitMaxs(3, 2);
            pick.visitEnd();
        });

        assertTrue(MixinContainerItemsAdapter.apply(mixin, host));

        List<MethodInsnNode> calls = calls(method(mixin, "pick", "(Z)L" + STACK + ";"));
        assertEquals(MixinContainerItemsAdapter.LEGACY_ITEMS, calls.get(0).name,
                "the declared frames still say NonNullList, so the read must keep that type");
        assertEquals(NON_NULL_LIST, calls.get(1).owner, "get(int) stays on NonNullList");
        verify(mixin);
    }

    @Test
    void containerShadowIsLeftAloneWhenTheHostStillHasTheOldField() throws IOException {
        Path jar = tempDir.resolve("old-host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(CONTENTS, "java/lang/Object", c -> c.visitField(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "items", "L" + NON_NULL_LIST + ";",
                    null, null).visitEnd()));
        }
        FuzzyMethodResolver oldHost = new FuzzyMethodResolver();
        oldHost.indexJar(jar);
        ClassNode mixin = containerMixin(c -> {});

        assertFalse(MixinContainerItemsAdapter.apply(mixin, oldHost),
                "a 1.21.x host keeps NonNullList, so the Mixin already matches");
        assertEquals("L" + NON_NULL_LIST + ";", mixin.fields.get(0).desc);
    }

    private static void readItems(MethodVisitor method) {
        method.visitFieldInsn(Opcodes.GETFIELD, "mod/ItemContainerContentsMixin", "items",
                "L" + NON_NULL_LIST + ";");
    }

    private static ClassNode accessor(String name, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "mod/FlowingFluidAccessor", null, "java/lang/Object", null);
        mixinAnnotation(cw, FLOWING_FLUID);
        MethodVisitor invoker = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name,
                desc, null, null);
        invoker.visitAnnotation(INVOKER, true).visitEnd();
        invoker.visitEnd();
        cw.visitEnd();
        return node(cw.toByteArray());
    }

    private static ClassNode containerMixin(Consumer<ClassWriter> members) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/ItemContainerContentsMixin", null,
                "java/lang/Object", null);
        mixinAnnotation(cw, CONTENTS);
        FieldVisitor field = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "items",
                "L" + NON_NULL_LIST + ";",
                "L" + NON_NULL_LIST + "<L" + STACK + ";>;", null);
        field.visitAnnotation(SHADOW, true).visitEnd();
        field.visitEnd();
        members.accept(cw);
        cw.visitEnd();
        return node(cw.toByteArray());
    }

    private static void mixinAnnotation(ClassWriter cw, String target) {
        AnnotationVisitor mixin = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    /** Every concrete method must pass ASM's verifier after the repair. */
    private static void verify(ClassNode node) {
        for (MethodNode m : node.methods) {
            if ((m.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            try {
                new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
            } catch (Exception e) {
                fail(node.name + "." + m.name + m.desc + " does not verify: " + e);
            }
        }
    }

    private static void put(JarOutputStream out, byte[] classBytes) throws IOException {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static byte[] hostClass(String name, String superName, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null,
                superName, null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
