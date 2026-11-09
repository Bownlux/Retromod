/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodTransformer.MethodTarget;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Forge 1.20.1's ArmPose.create and its transformer go to the rebuilt helper only on hosts that pose arms from render states. */
class LegacyArmPoseBridgeTest {

    private static final String OLD_APPLY = "(Lnet/minecraft/client/model/HumanoidModel;"
            + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/HumanoidArm;)V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Forge 26.2: create takes the offhand flag and the transformer a render state")
    void forge262TakesRenderStates() {
        assertTrue(LegacyArmPoseBridge.forgeTakesRenderStates(host(
                pose(LegacyArmPoseBridge.HOST_CREATE), transformer(LegacyArmPoseBridge.HOST_APPLY))));
    }

    @Test
    @DisplayName("Forge 1.20.1 keeps its own create and transformer")
    void forge1201KeepsItsCreate() {
        assertFalse(LegacyArmPoseBridge.forgeTakesRenderStates(host(
                pose(LegacyArmPoseBridge.OLD_CREATE), transformer(OLD_APPLY))));
    }

    @Test
    @DisplayName("Offline, a Forge target from 26.2 gets the helper, and a dedicated server follows the same rule")
    void offlineAndServerFollowTheTarget() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyArmPoseBridge.forgeTakesRenderStates(name -> null));
        assertTrue(LegacyArmPoseBridge.forgeTakesRenderStates(host(null, transformer(LegacyArmPoseBridge.HOST_APPLY))),
                "a server reads no client class");
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(LegacyArmPoseBridge.forgeTakesRenderStates(name -> null));
    }

    @Test
    @DisplayName("NeoForge, Fabric and Quilt never take the Forge helper")
    void otherLoadersNever() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceForge(false);
        assertFalse(LegacyArmPoseBridge.forgeTakesRenderStates(name -> null));
        McReflect.setForceNeoForge(true);
        assertFalse(LegacyArmPoseBridge.forgeTakesRenderStates(name -> null));
    }

    @Test
    @DisplayName("A 1.20.1 mod's create, transformer class and transformer lambda move to the rebuilt names")
    void oldModMovesToTheHelper() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyArmPoseBridge.registerForge(transformer);
        transformer.setModSourceVersion("1.20.1");

        assertEquals(List.of(LegacyArmPoseBridge.HELPER + ".create" + LegacyArmPoseBridge.HELPER_CREATE),
                creates(transformer.transformClass(poses(), "mod/Poses.class")));
        assertEquals(List.of(LegacyArmPoseBridge.LEGACY_TRANSFORMER), interfaces(
                transformer.transformClass(transformerClass(), "mod/Flip.class")));
        assertEquals("()L" + LegacyArmPoseBridge.LEGACY_TRANSFORMER + ";", lambdaType(
                transformer.transformClass(poses(), "mod/Poses.class")), "the lambda implements the rebuilt interface");
        assertTrue(transformer.getSyntheticClasses().keySet().containsAll(List.of(LegacyArmPoseBridge.HELPER,
                LegacyArmPoseBridge.HELPER + "$Adapter", LegacyArmPoseBridge.LEGACY_TRANSFORMER)));
    }

    @Test
    @DisplayName("The rebuilt adapter keeps implementing the host's transformer, which old mods no longer name")
    void adapterKeepsTheHostInterface() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyArmPoseBridge.registerForge(transformer);
        assertFalse(transformer.getClassRedirectsForHostCode().containsKey(LegacyArmPoseBridge.TRANSFORMER),
                "Retromod's own classes mean the host's IArmPoseTransformer");
        assertEquals(List.of(LegacyArmPoseBridge.TRANSFORMER),
                interfaces(transformer.getSyntheticClasses().get(LegacyArmPoseBridge.HELPER + "$Adapter")));
    }

    @Test
    @DisplayName("A mod built for render states keeps the host's create and transformer")
    void renderStateModKeepsHostNames() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyArmPoseBridge.registerForge(transformer);
        transformer.setModSourceVersion("1.21.4");

        assertEquals(List.of(LegacyArmPoseBridge.ARM_POSE + ".create" + LegacyArmPoseBridge.OLD_CREATE),
                creates(transformer.transformClass(poses(), "mod/Poses.class")));
        assertEquals(List.of(LegacyArmPoseBridge.TRANSFORMER),
                interfaces(transformer.transformClass(transformerClass(), "mod/Flip.class")));
    }

    @Test
    @DisplayName("On NeoForge an old create goes to the fixed-pose helper and the transformer still loads")
    void neoForgeGetsTheFixedPose() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyArmPoseBridge.registerNeoForge(transformer);

        MethodTarget target = transformer.getMethodRedirects().get(
                new MethodKey(LegacyArmPoseBridge.ARM_POSE, "create", LegacyArmPoseBridge.OLD_CREATE));
        assertEquals(LegacyArmPoseBridge.NEO_HELPER, target.owner());
        assertEquals(LegacyArmPoseBridge.LEGACY_TRANSFORMER, transformer.getClassRedirects().get(LegacyArmPoseBridge.TRANSFORMER));

        transformer.clearRedirectsForTesting();
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        LegacyArmPoseBridge.registerNeoForge(transformer);
        assertNull(transformer.getClassRedirects().get(LegacyArmPoseBridge.TRANSFORMER), "a Forge host keeps the Forge path");
    }

    // ---- fixtures ----

    /** A running Forge host with the given arm pose and transformer classes. */
    private static Function<String, ClassNode> host(ClassNode pose, ClassNode transformer) {
        ClassNode anchor = new ClassNode();
        anchor.name = ForgeHostShape.ANCHOR;
        java.util.HashMap<String, ClassNode> classes = new java.util.HashMap<>();
        classes.put(ForgeHostShape.ANCHOR, anchor);
        if (pose != null) classes.put(LegacyArmPoseBridge.ARM_POSE, pose);
        if (transformer != null) classes.put(LegacyArmPoseBridge.TRANSFORMER, transformer);
        return classes::get;
    }

    private static ClassNode pose(String createDesc) {
        ClassNode node = new ClassNode();
        node.name = LegacyArmPoseBridge.ARM_POSE;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create", createDesc, null, null));
        return node;
    }

    private static ClassNode transformer(String applyDesc) {
        ClassNode node = new ClassNode();
        node.name = LegacyArmPoseBridge.TRANSFORMER;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "applyTransform", applyDesc, null, null));
        return node;
    }

    /** {@code static ArmPose FLIP = ArmPose.create("FLIP", false, () -> lambda)}, as Farmer's Delight's client setup builds it. */
    private static byte[] poses() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Poses", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FLIP",
                "L" + LegacyArmPoseBridge.ARM_POSE + ";", null, null).visitEnd();
        MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$flip", OLD_APPLY, null, null);
        lambda.visitCode();
        lambda.visitInsn(Opcodes.RETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn("FLIP");
        init.visitInsn(Opcodes.ICONST_0);
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        init.visitInvokeDynamicInsn("applyTransform", "()L" + LegacyArmPoseBridge.TRANSFORMER + ";", metafactory,
                Type.getMethodType(OLD_APPLY), new Handle(Opcodes.H_INVOKESTATIC, "mod/Poses", "lambda$flip", OLD_APPLY, false),
                Type.getMethodType(OLD_APPLY));
        init.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyArmPoseBridge.ARM_POSE, "create", LegacyArmPoseBridge.OLD_CREATE, false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/Poses", "FLIP", "L" + LegacyArmPoseBridge.ARM_POSE + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class implementing the old transformer, as Farmer's Delight's skillet transformer does. */
    private static byte[] transformerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Flip", null, "java/lang/Object",
                new String[]{LegacyArmPoseBridge.TRANSFORMER});
        MethodVisitor apply = cw.visitMethod(Opcodes.ACC_PUBLIC, "applyTransform", OLD_APPLY, null, null);
        apply.visitCode();
        apply.visitInsn(Opcodes.RETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> creates(byte[] bytes) {
        return read(bytes).methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(insn -> insn instanceof MethodInsnNode call && call.name.equals("create"))
                .map(insn -> ((MethodInsnNode) insn).owner + ".create" + ((MethodInsnNode) insn).desc).toList();
    }

    private static List<String> interfaces(byte[] bytes) {
        return read(bytes).interfaces;
    }

    private static String lambdaType(byte[] bytes) {
        for (MethodNode method : read(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy) return indy.desc;
            }
        }
        throw new AssertionError("no lambda");
    }
}
