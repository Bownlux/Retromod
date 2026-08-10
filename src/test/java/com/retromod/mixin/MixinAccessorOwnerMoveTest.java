/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
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

/** Regression coverage for ENGRAM's player-model-parts accessor on Fabric 1.21.11. */
class MixinAccessorOwnerMoveTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";

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
