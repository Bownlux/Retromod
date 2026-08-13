/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-shape regression coverage for Double Hotbar 1.3.4 (#181). */
class DoubleHotbarMixinRepairTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String HUD = "net/minecraft/client/gui/Hud";
    private static final String MIXIN_CLASS = "test/DoubleHotbarMixin";
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String DELTA = "net/minecraft/client/DeltaTracker";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String ITEM = "net/minecraft/world/item/ItemStack";
    private static final String CALLBACK =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String EXTRACT_DESC = "(L" + EXTRACTOR + ";L" + DELTA + ";)V";
    private static final String HANDLER_DESC =
            "(L" + EXTRACTOR + ";L" + DELTA + ";L" + CALLBACK + ";)V";
    private static final String SLOT_DESC =
            "(L" + EXTRACTOR + ";IIL" + DELTA + ";L" + PLAYER + ";L" + ITEM + ";I)V";

    private String savedVersion;

    @AfterEach
    void restoreVersion() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("#181: Double Hotbar follows the 26.1 hotbar extraction method names")
    void repairs26_1GuiHotbarMixin() {
        ClassNode repaired = repair("26.1.2", doubleHotbarMixin(true));

        assertEquals(List.of(GUI), mixinTargets(repaired));
        assertTrue(hasShadow(repaired, "extractSlot", SLOT_DESC));
        assertFalse(hasMethod(repaired, "renderSlot", SLOT_DESC));
        assertTrue(calls(method(repaired, "renderHotbarItems", HANDLER_DESC),
                MIXIN_CLASS, "extractSlot", SLOT_DESC));
        assertItemSelectorsRepaired(repaired);
    }

    @Test
    @DisplayName("#181: Double Hotbar follows the Gui to Hud move on 26.2")
    void repairs26_2HudTarget() {
        ClassNode repaired = repair("26.2", doubleHotbarMixin(true));

        assertEquals(List.of(HUD), mixinTargets(repaired));
        assertTrue(hasShadow(repaired, "extractSlot", SLOT_DESC));
        assertTrue(calls(method(repaired, "renderHotbarItems", HANDLER_DESC),
                MIXIN_CLASS, "extractSlot", SLOT_DESC));
        assertItemSelectorsRepaired(repaired);
    }

    @Test
    @DisplayName("The HUD repair refuses a partial lookalike instead of moving an unrelated mixin")
    void refusesIncompleteShape() {
        ClassNode partial = read(doubleHotbarMixin(false));

        assertFalse(MixinLegacyMemberBridge.apply(partial));
        assertEquals(List.of(GUI), mixinTargets(partial));
        assertTrue(hasShadow(partial, "renderSlot", SLOT_DESC));
        assertFalse(hasMethod(partial, "extractSlot", SLOT_DESC));
    }

    private ClassNode repair(String version, byte[] input) {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = version;
        ClassNode classNode = read(input);
        assertTrue(MixinLegacyMemberBridge.apply(classNode));
        return classNode;
    }

    private static byte[] doubleHotbarMixin(boolean complete) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                MIXIN_CLASS, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(GUI));
        values.visitEnd();
        mixin.visitEnd();

        var sprite = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "HOTBAR_SPRITE", "Lnet/minecraft/resources/Identifier;", null, null);
        sprite.visitAnnotation(SHADOW, true).visitEnd();
        sprite.visitEnd();

        MethodVisitor slot = writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                "renderSlot", SLOT_DESC, null, null);
        slot.visitAnnotation(SHADOW, true).visitEnd();
        slot.visitEnd();

        for (String name : List.of(
                "renderHotbarFrame", "shiftHotbarSelector", "returnHotbarSelector",
                "shiftHotbarItems", "renderHotbarItems")) {
            addHandler(writer, name, "renderItemHotbar" + EXTRACT_DESC,
                    "renderHotbarItems".equals(name));
        }
        addHandler(writer, "shiftStatusBars",
                "renderHotbarAndDecorations" + EXTRACT_DESC, false);
        if (complete) {
            addHandler(writer, "returnStatusBars",
                    "renderHotbarAndDecorations" + EXTRACT_DESC, false);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addHandler(
            ClassWriter writer, String name, String selector, boolean callsSlot) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, name, HANDLER_DESC, null, null);
        AnnotationVisitor inject = method.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor at = inject.visitAnnotation("at", AT);
        at.visit("value", "HEAD");
        at.visitEnd();
        inject.visitEnd();
        method.visitCode();
        if (callsSlot) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    MIXIN_CLASS, "renderSlot", SLOT_DESC, false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void assertItemSelectorsRepaired(ClassNode classNode) {
        for (String name : List.of(
                "renderHotbarFrame", "shiftHotbarSelector", "returnHotbarSelector",
                "shiftHotbarItems", "renderHotbarItems")) {
            assertEquals("extractItemHotbar" + EXTRACT_DESC,
                    selector(method(classNode, name, HANDLER_DESC)));
        }
        for (String name : List.of("shiftStatusBars", "returnStatusBars")) {
            assertEquals("extractHotbarAndDecorations" + EXTRACT_DESC,
                    selector(method(classNode, name, HANDLER_DESC)));
        }
    }

    private static String selector(MethodNode method) {
        AnnotationNode inject = annotations(method).stream()
                .filter(annotation -> INJECT.equals(annotation.desc))
                .findFirst().orElseThrow();
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if ("method".equals(inject.values.get(i))) {
                return ((List<?>) inject.values.get(i + 1)).get(0).toString();
            }
        }
        throw new AssertionError("missing method selector");
    }

    private static boolean hasShadow(ClassNode classNode, String name, String desc) {
        return classNode.methods.stream().anyMatch(method -> name.equals(method.name)
                && desc.equals(method.desc)
                && annotations(method).stream().anyMatch(annotation -> SHADOW.equals(annotation.desc)));
    }

    private static boolean hasMethod(ClassNode classNode, String name, String desc) {
        return classNode.methods.stream()
                .anyMatch(method -> name.equals(method.name) && desc.equals(method.desc));
    }

    private static MethodNode method(ClassNode classNode, String name, String desc) {
        return classNode.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static boolean calls(MethodNode method, String owner, String name, String desc) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> owner.equals(call.owner) && name.equals(call.name)
                        && desc.equals(call.desc));
    }

    private static List<String> mixinTargets(ClassNode classNode) {
        AnnotationNode mixin = classAnnotations(classNode).stream()
                .filter(annotation -> MIXIN.equals(annotation.desc))
                .findFirst().orElseThrow();
        for (int i = 0; i + 1 < mixin.values.size(); i += 2) {
            if ("value".equals(mixin.values.get(i))) {
                List<String> targets = new ArrayList<>();
                for (Object target : (List<?>) mixin.values.get(i + 1)) {
                    targets.add(((Type) target).getInternalName());
                }
                return targets;
            }
        }
        return List.of();
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static List<AnnotationNode> classAnnotations(ClassNode classNode) {
        List<AnnotationNode> result = new ArrayList<>();
        if (classNode.visibleAnnotations != null) result.addAll(classNode.visibleAnnotations);
        if (classNode.invisibleAnnotations != null) result.addAll(classNode.invisibleAnnotations);
        return result;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, 0);
        return classNode;
    }
}
