/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
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
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regression coverage for ENGRAM's player-model-parts accessor on Fabric 1.21.11. */
class MixinAccessorOwnerMoveTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String MUTABLE = "Lorg/spongepowered/asm/mixin/Mutable;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("#179: Player model-parts accessor follows the field to Avatar on 1.21.11")
    void playerModelPartsAccessorMovesToAvatar() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_10_to_1_21_11().registerRedirects(transformer);

        byte[] output = new MixinCompatibilityTransformer(transformer)
                .transformMixinClass(engramAccessor());
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);

        AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);
        List<?> targets = (List<?>) value(mixin, "value");
        assertEquals("net/minecraft/class_11890", ((Type) targets.get(0)).getInternalName(),
                "the accessor must target Avatar, which owns the field on 1.21.11");

        MethodNode method = node.methods.stream()
                .filter(m -> m.name.equals("horrorMod129$getPlayerModelParts"))
                .findFirst().orElseThrow();
        AnnotationNode accessor = annotation(method.visibleAnnotations, ACCESSOR);
        assertEquals("field_62514", value(accessor, "value"));
        assertEquals(false, value(accessor, "remap"),
                "the rewritten intermediary field must bypass the old refmap entry");
    }

    @Test
    @DisplayName("#228: implicit Block random-tick setter follows the field and becomes mutable")
    void blockRandomTickAccessorMovesToBlockBehaviour() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        byte[] output = new MixinCompatibilityTransformer(transformer)
                .transformMixinClass(permafrostAccessor());
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);

        AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);
        List<?> targets = (List<?>) value(mixin, "value");
        assertEquals("net/minecraft/world/level/block/state/BlockBehaviour",
                ((Type) targets.get(0)).getInternalName(),
                "the accessor must target the class that declares the random-tick field");

        MethodNode method = node.methods.stream()
                .filter(m -> m.name.equals("setRandomTicks"))
                .findFirst().orElseThrow();
        AnnotationNode accessor = annotation(method.visibleAnnotations, ACCESSOR);
        assertEquals("isRandomlyTicking", value(accessor, "value"));
        assertEquals(false, value(accessor, "remap"));
        assertNotNull(method.visibleAnnotations.stream()
                        .filter(a -> "Lorg/spongepowered/asm/mixin/Mutable;".equals(a.desc))
                        .findFirst().orElse(null),
                "a setter for the modern final field must carry @Mutable");
    }

    @Test
    @DisplayName("an existing invisible Mutable annotation is not duplicated")
    void preservesExistingMutableAnnotation() {
        ClassNode input = new ClassNode();
        new ClassReader(permafrostAccessor()).accept(input, 0);
        MethodNode setter = method(input, "setRandomTicks");
        setter.invisibleAnnotations = new java.util.ArrayList<>(List.of(
                new AnnotationNode(MUTABLE)));
        ClassWriter writer = new ClassWriter(0);
        input.accept(writer);

        ClassNode output = transformPermafrostAccessor(writer.toByteArray());
        MethodNode transformedSetter = method(output, "setRandomTicks");
        long mutableAnnotations = List.of(
                transformedSetter.visibleAnnotations != null
                        ? transformedSetter.visibleAnnotations : List.<AnnotationNode>of(),
                transformedSetter.invisibleAnnotations != null
                        ? transformedSetter.invisibleAnnotations : List.<AnnotationNode>of())
            .stream().flatMap(List::stream)
            .filter(annotation -> MUTABLE.equals(annotation.desc))
            .count();

        assertEquals(1, mutableAnnotations);
    }

    @Test
    @DisplayName("accessor owner move refuses an unmatched companion accessor")
    void refusesUnmatchedCompanionAccessor() {
        ClassNode node = transformPermafrostAccessor(
                permafrostAccessor("(Z)V", true, false));

        assertEquals("net/minecraft/class_2248", firstMixinTarget(node));
        assertFalse(hasMutable(node, "setRandomTicks"),
                "a partial accessor move must not mutate the setter");
    }

    @Test
    @DisplayName("accessor owner move refuses a mixin with multiple targets")
    void refusesMultipleMixinTargets() {
        ClassNode node = transformPermafrostAccessor(
                permafrostAccessor("(Z)V", false, true));

        AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);
        List<?> targets = (List<?>) value(mixin, "value");
        assertEquals(2, targets.size());
        assertEquals("net/minecraft/class_2248", ((Type) targets.get(0)).getInternalName());
        assertFalse(hasMutable(node, "setRandomTicks"));
    }

    @Test
    @DisplayName("accessor owner move refuses a field descriptor mismatch")
    void refusesDescriptorMismatch() {
        ClassNode node = transformPermafrostAccessor(
                permafrostAccessor("(I)V", false, false));

        assertEquals("net/minecraft/class_2248", firstMixinTarget(node));
        assertFalse(hasMutable(node, "setRandomTicks"));
    }

    @Test
    @DisplayName("implicit isFoo accessor uses Mixin's foo member name")
    void infersBooleanGetterNameLikeMixin() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerFieldRedirect(
                "example/OldOwner", "foo", "Z",
                "example/NewOwner", "foo", "Z");

        ClassNode node = transformAccessor(transformer,
                implicitAccessor("example/OldOwner", "isFoo", "()Z"));

        assertEquals("example/NewOwner", firstMixinTarget(node));
        MethodNode method = method(node, "isFoo");
        assertEquals("foo", value(annotation(method.visibleAnnotations, ACCESSOR), "value"));
    }

    @Test
    @DisplayName("implicit setURL accessor preserves an uppercase acronym without @Mutable")
    void preservesAccessorAcronymAndFinalRules() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        String stringDescriptor = "Ljava/lang/String;";
        transformer.registerFieldRedirect(
                "example/OldOwner", "URL", stringDescriptor,
                "example/NewOwner", "URL", stringDescriptor);

        ClassNode node = transformAccessor(transformer,
                implicitAccessor("example/OldOwner", "setURL",
                        "(" + stringDescriptor + ")V"));

        assertEquals("example/NewOwner", firstMixinTarget(node));
        MethodNode method = method(node, "setURL");
        assertEquals("URL", value(annotation(method.visibleAnnotations, ACCESSOR), "value"));
        assertFalse(hasMutable(node, "setURL"),
                "a moved setter needs an explicit mutable destination registration");
    }

    private static byte[] engramAccessor() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "horror/blueice129/mixin/client/PlayerEntityAccessor",
                null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/class_1657"));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "horrorMod129$getPlayerModelParts", "()Lnet/minecraft/class_2940;", null, null);
        AnnotationVisitor accessor = method.visitAnnotation(ACCESSOR, true);
        accessor.visit("value", "PLAYER_MODEL_PARTS");
        accessor.visitEnd();
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] permafrostAccessor() {
        return permafrostAccessor("(Z)V", false, false);
    }

    private static byte[] permafrostAccessor(
            String setterDescriptor, boolean companionAccessor, boolean multipleTargets) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "virtuoel/permafrost/mixin/BlockAccessor",
                null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/class_2248"));
        if (multipleTargets) {
            targets.visit(null, Type.getObjectType("net/minecraft/class_2246"));
        }
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "setRandomTicks", setterDescriptor, null, null);
        method.visitAnnotation(ACCESSOR, true).visitEnd();
        method.visitEnd();
        if (companionAccessor) {
            MethodVisitor companion = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "getLightOpacity", "()I", null, null);
            AnnotationVisitor accessor = companion.visitAnnotation(ACCESSOR, true);
            accessor.visit("value", "lightOpacity");
            accessor.visitEnd();
            companion.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] implicitAccessor(
            String targetOwner, String methodName, String methodDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "example/Accessor", null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetOwner));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                methodName, methodDescriptor, null, null);
        method.visitAnnotation(ACCESSOR, true).visitEnd();
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode transformPermafrostAccessor(byte[] input) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        return transformAccessor(transformer, input);
    }

    private static ClassNode transformAccessor(
            RetromodTransformer transformer, byte[] input) {
        byte[] output = new MixinCompatibilityTransformer(transformer).transformMixinClass(input);
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        return node;
    }

    private static String firstMixinTarget(ClassNode node) {
        AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);
        List<?> targets = (List<?>) value(mixin, "value");
        return ((Type) targets.get(0)).getInternalName();
    }

    private static boolean hasMutable(ClassNode node, String methodName) {
        MethodNode method = method(node, methodName);
        return method.visibleAnnotations != null
                && method.visibleAnnotations.stream().anyMatch(
                    annotation -> "Lorg/spongepowered/asm/mixin/Mutable;".equals(annotation.desc));
    }

    private static MethodNode method(ClassNode node, String methodName) {
        return node.methods.stream()
                .filter(candidate -> methodName.equals(candidate.name))
                .findFirst().orElseThrow();
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations.stream().filter(a -> descriptor.equals(a.desc)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
}
