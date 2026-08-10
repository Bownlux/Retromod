package com.retromod.shim.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import com.retromod.core.RetromodTransformer;

class LegacyRenderPipelineBuilderBridgeTest {
    private static final String BUILDER =
            "com/mojang/blaze3d/pipeline/RenderPipeline$Builder";
    private static final String BRIDGE =
            "com/retromod/shim/common/LegacyRenderPipelineBuilderBridge";

    @Test
    void removedBuilderCallsBecomeAnEmbedded26_2Bridge() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            Mc26_1To26_2CoreMoves.register(transformer);
            byte[] output = transformer.transformClass(oldPipelineBuilderCalls(),
                    "test/OldPipelineBuilderCalls");

            ClassNode node = new ClassNode();
            new ClassReader(output).accept(node, 0);
            List<MethodInsnNode> calls = node.methods.stream()
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .toList();

            assertFalse(calls.stream().anyMatch(call -> BUILDER.equals(call.owner)
                    && SetOfRemovedBuilderMethods.contains(call.name)));
            assertTrue(calls.stream().anyMatch(call -> BRIDGE.equals(call.owner)
                    && "withSampler".equals(call.name)));
            assertTrue(calls.stream().anyMatch(call -> BRIDGE.equals(call.owner)
                    && "withBlend".equals(call.name)));
            assertTrue(calls.stream().anyMatch(call -> BRIDGE.equals(call.owner)
                    && "withFragmentShader".equals(call.name)));
            assertTrue(calls.stream().anyMatch(call -> BRIDGE.equals(call.owner)
                    && "withVertexFormat".equals(call.name)));
            assertTrue(calls.stream().anyMatch(call -> BRIDGE.equals(call.owner)
                    && "build".equals(call.name)));
            FieldInsnNode entityFormat = node.methods.stream()
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(FieldInsnNode.class::isInstance)
                    .map(FieldInsnNode.class::cast)
                    .filter(field -> "com/mojang/blaze3d/vertex/DefaultVertexFormat"
                            .equals(field.owner))
                    .findFirst()
                    .orElseThrow();
            assertEquals("ENTITY", entityFormat.name);
            assertTrue(transformer.getSyntheticClasses().containsKey(BRIDGE),
                    "offline output must embed the bridge instead of retaining a Retromod-only owner");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    void oldSamplerNamesChooseTheMatchingCombinedLayout() {
        assertEquals("SAMPLER0", layout("Sampler0"));
        assertEquals("SAMPLER0_SAMPLER2", layout("Sampler0", "Sampler2"));
        assertEquals("SAMPLER0_SAMPLER1_SAMPLER2",
                layout("Sampler0", "Sampler1", "Sampler2"));
    }

    private static String layout(String... samplers) {
        return LegacyRenderPipelineBuilderBridge.samplerLayoutField(
                new LinkedHashSet<>(List.of(samplers)));
    }

    private static byte[] oldPipelineBuilderCalls() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/OldPipelineBuilderCalls", null,
                "java/lang/Object", null);
        String desc = "(L" + BUILDER + ";Lcom/mojang/blaze3d/pipeline/BlendFunction;"
                + "Lcom/mojang/blaze3d/vertex/VertexFormat;"
                + "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;)V";
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "build", desc, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("Sampler0");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withSampler",
                "(Ljava/lang/String;)L" + BUILDER + ";", false);
        method.visitLdcInsn("core/rendertype_item_entity_translucent_cull");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withFragmentShader",
                "(Ljava/lang/String;)L" + BUILDER + ";", false);
        method.visitLdcInsn("Sampler2");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withSampler",
                "(Ljava/lang/String;)L" + BUILDER + ";", false);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withBlend",
                "(Lcom/mojang/blaze3d/pipeline/BlendFunction;)L" + BUILDER + ";", false);
        method.visitFieldInsn(Opcodes.GETSTATIC,
                "com/mojang/blaze3d/vertex/DefaultVertexFormat", "NEW_ENTITY",
                "Lcom/mojang/blaze3d/vertex/VertexFormat;");
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withVertexFormat",
                "(Lcom/mojang/blaze3d/vertex/VertexFormat;"
                        + "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;)L" + BUILDER + ";",
                false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build",
                "()Lcom/mojang/blaze3d/pipeline/RenderPipeline;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class SetOfRemovedBuilderMethods {
        private static final java.util.Set<String> NAMES = java.util.Set.of(
                "withSampler", "withBlend", "withFragmentShader", "withVertexFormat", "build");

        private SetOfRemovedBuilderMethods() {
        }

        static boolean contains(String name) {
            return NAMES.contains(name);
        }
    }
}
