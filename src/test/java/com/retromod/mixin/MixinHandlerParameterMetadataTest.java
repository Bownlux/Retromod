/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.mixin.MixinHandlerResignature.ParamInsert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.RETURN;

/** Regression coverage for metadata moved by automatic Mixin handler re-signaturing. */
class MixinHandlerParameterMetadataTest {

    private static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    @Test
    @DisplayName("An ordinary parameter annotation follows its parameter after a prepend")
    @SuppressWarnings("unchecked")
    void ordinaryParameterAnnotationMovesWithParameter() {
        MethodNode handler = handler();
        AnnotationNode nullable = new AnnotationNode("Lorg/jetbrains/annotations/Nullable;");
        List<AnnotationNode>[] annotations = new List[2];
        annotations[0] = new ArrayList<>(List.of(nullable));
        handler.visibleParameterAnnotations = annotations;
        handler.visibleAnnotableParameterCount = annotations.length;

        assertTrue(MixinHandlerResignature.insertParams(handler,
                List.of(new ParamInsert(0, "Ljava/lang/Integer;"))));

        assertEquals(3, handler.visibleParameterAnnotations.length);
        assertNull(handler.visibleParameterAnnotations[0], "the inserted parameter has no annotation");
        assertSame(nullable, handler.visibleParameterAnnotations[1].get(0),
                "@Nullable must remain attached to the original String parameter");
        assertNull(handler.visibleParameterAnnotations[2], "CallbackInfo remains unannotated");
        assertEquals(3, handler.visibleAnnotableParameterCount);
    }

    @Test
    @DisplayName("A semantic MixinExtras @Local parameter still makes re-signaturing decline")
    @SuppressWarnings("unchecked")
    void localCaptureAnnotationRemainsUnsafe() {
        MethodNode handler = handler();
        AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
        List<AnnotationNode>[] annotations = new List[2];
        annotations[0] = new ArrayList<>(List.of(local));
        handler.invisibleParameterAnnotations = annotations;
        String oldDescriptor = handler.desc;

        assertFalse(MixinHandlerResignature.insertParams(handler,
                List.of(new ParamInsert(0, "Ljava/lang/Integer;"))));

        assertEquals(oldDescriptor, handler.desc, "a declined handler keeps its descriptor");
        assertSame(local, handler.invisibleParameterAnnotations[0].get(0),
                "a declined handler keeps @Local on its original parameter");
    }

    @Test
    @DisplayName("Formal parameter type annotations use the new descriptor indices")
    void formalParameterTypeAnnotationIndicesShift() {
        MethodNode handler = handler();
        TypeAnnotationNode nullable = new TypeAnnotationNode(
                TypeReference.newFormalParameterReference(0).getValue(), null,
                "Lorg/jetbrains/annotations/Nullable;");
        TypeAnnotationNode callbackMarker = new TypeAnnotationNode(
                TypeReference.newFormalParameterReference(1).getValue(), null,
                "Ltest/CallbackMarker;");
        handler.visibleTypeAnnotations = new ArrayList<>(List.of(nullable, callbackMarker));

        assertTrue(MixinHandlerResignature.insertParams(handler,
                List.of(new ParamInsert(0, "Ljava/lang/Integer;"))));

        assertEquals(1, formalParameterIndex(nullable),
                "the String type annotation follows old parameter 0 to parameter 1");
        assertEquals(2, formalParameterIndex(callbackMarker),
                "the CallbackInfo type annotation follows old parameter 1 to parameter 2");
    }

    @Test
    @DisplayName("Local variable type annotation slots shift by the inserted parameter width")
    void localVariableAnnotationSlotsShift() {
        MethodNode handler = handler();
        LabelNode start = (LabelNode) handler.instructions.getFirst();
        LabelNode end = (LabelNode) handler.instructions.get(handler.instructions.size() - 2);
        LocalVariableAnnotationNode annotation = new LocalVariableAnnotationNode(
                TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).getValue(), null,
                new LabelNode[]{start, start}, new LabelNode[]{end, end}, new int[]{1, 3},
                "Ltest/TrackedLocal;");
        handler.visibleLocalVariableAnnotations = new ArrayList<>(List.of(annotation));

        assertTrue(MixinHandlerResignature.insertParams(handler,
                List.of(new ParamInsert(0, "J"))), "a long prepend occupies two local slots");

        assertEquals(List.of(3, 5), annotation.index,
                "the captured String and body local both move by the long parameter width");
    }

    private static int formalParameterIndex(TypeAnnotationNode annotation) {
        return new TypeReference(annotation.typeRef).getFormalParameterIndex();
    }

    private static MethodNode handler() {
        MethodNode handler = new MethodNode(ACC_PRIVATE, "handler",
                "(Ljava/lang/String;" + CALLBACK_INFO + ")V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        handler.instructions.add(start);
        handler.instructions.add(new VarInsnNode(ALOAD, 1));
        handler.instructions.add(new VarInsnNode(ASTORE, 3));
        handler.instructions.add(end);
        handler.instructions.add(new InsnNode(RETURN));
        handler.maxLocals = 4;
        handler.maxStack = 1;
        return handler;
    }
}
