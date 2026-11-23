/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Supplementaries' required wrap of {@code new Item(...)} after "shulker_shell" in {@code Items.<clinit>}, which 26.3 no longer makes. */
class MixinMissingNewSiteTest {

    private static final String ITEMS = "net/minecraft/world/item/Items";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

    @Test
    @DisplayName("on 26.3, where Items.<clinit> builds no item itself, the required wrap fails softly")
    void requiredWrapSoftensWhereTheHostBuildsNoItem() {
        ClassNode mixin = itemsMixin();
        assertTrue(MixinMissingNewSite.apply(mixin, host(items(false))));
        assertEquals(0, require(mixin));
    }

    @Test
    @DisplayName("on 1.21.1, where the item is still built after the constant, the mod's require stays")
    void requireStaysWhereTheSiteExists() {
        ClassNode mixin = itemsMixin();
        assertFalse(MixinMissingNewSite.apply(mixin, host(items(true))));
        assertEquals(1, require(mixin));
    }

    @Test
    @DisplayName("a target the host cannot show keeps the mod's require")
    void unreadableHostKeepsRequire() {
        ClassNode mixin = itemsMixin();
        assertFalse(MixinMissingNewSite.apply(mixin, name -> null));
        assertEquals(1, require(mixin));
    }

    @Test
    @DisplayName("a selector or built class the host does not know by that name, as a refmap would resolve it, keeps the require")
    void unprovenNamesKeepRequire() {
        ClassNode items = items(false);
        items.methods.forEach(method -> method.name = "method_10000");
        ClassNode renamedTarget = itemsMixin();
        ClassNode item = new ClassNode();
        item.name = ITEM;
        assertFalse(MixinMissingNewSite.apply(renamedTarget, Map.of(ITEMS, items, ITEM, item)::get),
                "no target declares the selected method under that name");
        assertEquals(1, require(renamedTarget));

        ClassNode unknownOwner = itemsMixin();
        assertFalse(MixinMissingNewSite.apply(unknownOwner, Map.of(ITEMS, items(false))::get),
                "the built class is not on the host under that name");
        assertEquals(1, require(unknownOwner));
    }

    @Test
    @DisplayName("NEW targets name their class in three spellings")
    void newTargetSpellings() {
        assertEquals(ITEM, MixinMissingNewSite.newOwner("(L" + ITEM + "$Properties;)L" + ITEM + ";"));
        assertEquals(ITEM, MixinMissingNewSite.newOwner("L" + ITEM + ";"));
        assertEquals(ITEM, MixinMissingNewSite.newOwner("net.minecraft.world.item.Item"));
    }

    private static Function<String, ClassNode> host(ClassNode items) {
        ClassNode item = new ClassNode();
        item.name = ITEM;
        return Map.of(ITEMS, items, ITEM, item)::get;
    }

    /** {@code Items} whose static initializer names "shulker_shell" and, when {@code builds}, constructs an item after it. */
    private static ClassNode items(boolean builds) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ITEMS, null, "java/lang/Object", null);
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("shulker_shell");
        clinit.visitInsn(POP);
        if (builds) {
            clinit.visitTypeInsn(NEW, ITEM);
            clinit.visitInsn(POP);
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return read(cw.toByteArray());
    }

    /** The mixin's shape: {@code @WrapOperation(method = "<clinit>", at = @At(value = "NEW", ...), require = 1, slice = ...)}. */
    private static ClassNode itemsMixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "mod/ItemsMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(ITEMS));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor handler = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "supp$overrideShulkerShell",
                "(L" + ITEM + "$Properties;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)L" + ITEM + ";",
                null, null);
        AnnotationVisitor wrap = handler.visitAnnotation(WRAP, true);
        AnnotationVisitor methods = wrap.visitArray("method");
        methods.visit(null, "<clinit>");
        methods.visitEnd();
        AnnotationVisitor ats = wrap.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "NEW");
        at.visit("target", "(L" + ITEM + "$Properties;)L" + ITEM + ";");
        at.visitEnd();
        ats.visitEnd();
        wrap.visit("require", 1);
        AnnotationVisitor slices = wrap.visitArray("slice");
        AnnotationVisitor slice = slices.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/Slice;");
        AnnotationVisitor from = slice.visitAnnotation("from", "Lorg/spongepowered/asm/mixin/injection/At;");
        from.visit("value", "CONSTANT");
        AnnotationVisitor args = from.visitArray("args");
        args.visit(null, "stringValue=shulker_shell");
        args.visitEnd();
        from.visitEnd();
        slice.visitEnd();
        slices.visitEnd();
        wrap.visitEnd();
        handler.visitEnd();
        cw.visitEnd();
        return read(cw.toByteArray());
    }

    private static int require(ClassNode mixin) {
        AnnotationNode wrap = mixin.methods.get(0).visibleAnnotations.get(0);
        for (int i = 0; i < wrap.values.size(); i += 2) {
            if (wrap.values.get(i).equals("require")) return (Integer) wrap.values.get(i + 1);
        }
        throw new AssertionError("no require");
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
