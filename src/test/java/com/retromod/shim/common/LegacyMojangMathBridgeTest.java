/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** Old {@code com.mojang.math} render code moves onto JOML on a 1.19.3 or newer host. */
class LegacyMojangMathBridgeTest {

    private static final String MOD = "com/example/DragonRenderer";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String OLD_V3 = "Lcom/mojang/math/Vector3f;";
    private static final String OLD_Q = "Lcom/mojang/math/Quaternion;";
    private static final String OLD_M4 = "Lcom/mojang/math/Matrix4f;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        LegacyMojangMathBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void axisRotationReachesThePoseStackAsAJomlQuaternion() {
        // poseStack.mulPose(Vector3f.YP.rotationDegrees(yaw))
        byte[] renderer = method("render", "(L" + POSE_STACK + ";F)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, "com/mojang/math/Vector3f", "YP", OLD_V3);
            mv.visitVarInsn(FLOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/math/Vector3f", "rotationDegrees",
                    "(F)" + OLD_Q, false);
            mv.visitMethodInsn(INVOKEVIRTUAL, POSE_STACK, "mulPose", "(" + OLD_Q + ")V", false);
            mv.visitInsn(RETURN);
        });

        List<AbstractInsnNode> code = code(transformer.transformClass(renderer, MOD));
        FieldInsnNode axis = first(code, FieldInsnNode.class);
        assertTrue(axis.owner.endsWith("LegacyMojangMath") && axis.name.equals("YP"),
                "the axis constant is read from the generated helper, got " + axis.owner + "." + axis.name);
        assertEquals("Lorg/joml/Vector3f;", axis.desc);
        assertEquals(List.of("LegacyMojangMath.v3RotationDegrees", "PoseStack.mulPose"), calls(code));
        MethodInsnNode mulPose = (MethodInsnNode) code.stream()
                .filter(i -> i instanceof MethodInsnNode m && m.name.equals("mulPose")).findFirst().orElseThrow();
        assertEquals("(Lorg/joml/Quaternionf;)V", mulPose.desc,
                "the vanilla call links under its 1.19.3 descriptor");
        assertNoOldMath(code);
    }

    @Test
    void matrixWorkKeepsTheOldMeaning() {
        // Matrix4f m = new Matrix4f(); m.multiply(other); float x = m.m03; return new Quaternion(q);
        byte[] renderer = method("bones", "(" + OLD_M4 + OLD_Q + ")" + OLD_Q, mv -> {
            mv.visitTypeInsn(NEW, "com/mojang/math/Matrix4f");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "com/mojang/math/Matrix4f", "<init>", "()V", false);
            mv.visitVarInsn(ASTORE, 2);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/math/Matrix4f", "multiply", "(" + OLD_M4 + ")V", false);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitFieldInsn(GETFIELD, "com/mojang/math/Matrix4f", "m03", "F");
            mv.visitInsn(POP);
            mv.visitTypeInsn(NEW, "com/mojang/math/Quaternion");
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, "com/mojang/math/Quaternion", "<init>", "(" + OLD_Q + ")V", false);
            mv.visitInsn(ARETURN);
        });

        List<AbstractInsnNode> code = code(transformer.transformClass(renderer, MOD));
        assertEquals(List.of("LegacyMojangMath.m4Zero", "LegacyMojangMath.m4Mul",
                        "LegacyMojangMath.m4Get03", "LegacyMojangMath.qCopy"), calls(code),
                "the zero matrix, the void multiply, the row-major field and the copy all use helpers");
        assertTrue(code.stream().noneMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == NEW),
                "no constructor of a removed class is left");
        assertNoOldMath(code);
    }

    @Test
    void aModBuiltAgainstJomlKeepsItsIdentityMatrix() {
        byte[] modern = method("pose", "()Lorg/joml/Matrix4f;", mv -> {
            mv.visitTypeInsn(NEW, "org/joml/Matrix4f");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "org/joml/Matrix4f", "<init>", "()V", false);
            mv.visitInsn(ARETURN);
        });

        List<AbstractInsnNode> code = code(transformer.transformClass(modern, MOD));
        assertEquals(List.of("Matrix4f.<init>"), calls(code),
                "only the removed class's zero constructor is redirected");
    }

    @Test
    void generatedHelpersAreValid() {
        byte[] helpers = LegacyMojangMathBridge.generate();
        new ClassReader(helpers).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        ClassNode node = new ClassNode();
        new ClassReader(helpers).accept(node, 0);
        List<String> names = node.methods.stream().map(m -> m.name).toList();
        for (String expected : List.of("qEuler", "qAxisAngle", "m4AddTranslation", "m4Get03",
                "m4Set03", "m3Get22", "v4FromV3", "v3Normalize", "m4Invert")) {
            assertTrue(names.contains(expected), "helper " + expected + " is generated");
        }
    }

    @Test
    void limbSwingAndTextDrawingFollowTheirNewShapes() {
        transformer.clearRedirectsForTesting();
        LegacyMojangMathBridge.register(transformer);
        LegacyClientRenderBridge.register(transformer);
        String living = "net/minecraft/world/entity/LivingEntity";
        String font = "net/minecraft/client/gui/Font";
        String buffers = "Lnet/minecraft/client/renderer/MultiBufferSource;";
        byte[] renderer = method("render", "(L" + living + ";L" + font + ";" + OLD_M4 + buffers + ")V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, living, "animationSpeedOld", "F");
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitLdcInsn("egg");
            mv.visitInsn(FCONST_0);
            mv.visitInsn(FCONST_0);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, font, "drawInBatch",
                    "(Ljava/lang/String;FFIZ" + OLD_M4 + buffers + "ZII)I", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        assertEquals(List.of("LegacyClientRender.walkSpeedOld", "LegacyClientRender.drawInBatch"),
                calls(code(transformer.transformClass(renderer, MOD))),
                "the limb swing reads the walk animation state and the see-through flag picks a display mode");
        new ClassReader(LegacyClientRenderBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    private static void assertNoOldMath(List<AbstractInsnNode> code) {
        for (AbstractInsnNode insn : code) {
            String text = insn instanceof MethodInsnNode m ? m.owner + m.desc
                    : insn instanceof FieldInsnNode f ? f.owner + f.desc
                    : insn instanceof TypeInsnNode t ? t.desc : "";
            assertFalse(text.contains("com/mojang/math/"), "removed math class left in " + text);
        }
    }

    private static <T> T first(List<AbstractInsnNode> code, Class<T> type) {
        return code.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private static byte[] method(String name, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<AbstractInsnNode> code(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<AbstractInsnNode> code = new ArrayList<>();
        node.methods.get(0).instructions.forEach(code::add);
        return code;
    }

    /** Calls as simple owner name and method, so embedded owners compare equal. */
    private static List<String> calls(List<AbstractInsnNode> code) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }
}
