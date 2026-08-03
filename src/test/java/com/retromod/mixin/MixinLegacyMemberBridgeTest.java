/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for legacy member bridges found in Species 2.3 (#168). */
class MixinLegacyMemberBridgeTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String MOB_EFFECT = "net/minecraft/world/effect/MobEffect";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String POSE = "net/minecraft/world/entity/Pose";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String MAP_CODEC_CODEC = "com/mojang/serialization/MapCodec$MapCodecCodec";
    private static final String TREE_DECORATOR_TYPE =
            "net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecoratorType";

    private String savedVersion;

    @AfterEach
    void restoreVersion() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static byte[] livingEntityMixin() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "test/LegacyEffectsMixin", null, "java/lang/Object", null);
        var mixin = cw.visitAnnotation(MIXIN, false);
        var values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(LIVING_ENTITY));
        values.visitEnd();
        mixin.visitEnd();

        MethodVisitor shadow = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "hasEffect", "(L" + MOB_EFFECT + ";)Z", null, null);
        shadow.visitAnnotation(SHADOW, false).visitEnd();
        shadow.visitEnd();

        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PUBLIC, "handler",
                "(L" + LIVING_ENTITY + ";Lnet/minecraft/world/effect/MobEffectInstance;L"
                        + MOB_EFFECT + ";)V", null, null);
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 0);
        handler.visitVarInsn(Opcodes.ALOAD, 3);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "test/LegacyEffectsMixin", "hasEffect",
                "(L" + MOB_EFFECT + ";)Z", false);
        handler.visitInsn(Opcodes.POP);
        handler.visitVarInsn(Opcodes.ALOAD, 1);
        handler.visitVarInsn(Opcodes.ALOAD, 3);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING_ENTITY, "getEffect",
                "(L" + MOB_EFFECT + ";)Lnet/minecraft/world/effect/MobEffectInstance;", false);
        handler.visitInsn(Opcodes.POP);
        handler.visitVarInsn(Opcodes.ALOAD, 2);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffectInstance", "getEffect",
                "()L" + MOB_EFFECT + ";", false);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] poseMixin() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyPoseMixin", null,
                "java/lang/Object", null);
        var mixin = cw.visitAnnotation(MIXIN, false);
        var values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(POSE));
        values.visitEnd();
        mixin.visitEnd();

        MethodVisitor method = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "newPose", "(Ljava/lang/String;I)L" + POSE + ";", null, null);
        method.visitAnnotation(INVOKER, false).visit("value", "<init>");
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A mixin on a worldgen type wrapper. Mods reach the private registrar and constructor
     * through invokers, which still carry the {@code Codec} descriptor they were built against.
     */
    private static byte[] treeDecoratorTypeMixin(boolean registrar, boolean factory) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/TreeDecoratorTypeMixin", null,
                "java/lang/Object", null);
        var mixin = cw.visitAnnotation(MIXIN, false);
        var values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(TREE_DECORATOR_TYPE));
        values.visitEnd();
        mixin.visitEnd();

        if (registrar) {
            addThrowingInvoker(cw, "invokeRegister",
                    "(Ljava/lang/String;L" + CODEC + ";)L" + TREE_DECORATOR_TYPE + ";", "register");
        }
        if (factory) {
            addThrowingInvoker(cw, "newType",
                    "(L" + CODEC + ";)L" + TREE_DECORATOR_TYPE + ";", "<init>");
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The accessor-interface form of the same mixin, which Mixin does not merge into the target. */
    private static byte[] treeDecoratorTypeAccessorInterface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "test/TreeDecoratorTypeAccessor", null, "java/lang/Object", null);
        var mixin = cw.visitAnnotation(MIXIN, false);
        var values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(TREE_DECORATOR_TYPE));
        values.visitEnd();
        mixin.visitEnd();

        addThrowingInvoker(cw, "callRegister",
                "(Ljava/lang/String;L" + CODEC + ";)L" + TREE_DECORATOR_TYPE + ";", "register");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A static invoker must be concrete, so mods give it a throwing placeholder body. */
    private static void addThrowingInvoker(ClassWriter cw, String name, String desc, String target) {
        MethodVisitor method = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, desc, null, null);
        var annotation = method.visitAnnotation(INVOKER, false);
        annotation.visit("value", target);
        annotation.visitEnd();
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /**
     * The state the Fabric path used to hand to the bridges: the Mixin annotation is already
     * remapped, but the member descriptors are still intermediary.
     */
    private static byte[] halfRemappedLivingEntityMixin() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "test/HalfRemappedEffectsMixin", null, "java/lang/Object", null);
        var mixin = cw.visitAnnotation(MIXIN, false);
        var values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(LIVING_ENTITY));
        values.visitEnd();
        mixin.visitEnd();

        MethodVisitor shadow = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "method_6059", "(Lnet/minecraft/class_1291;)Z", null, null);
        shadow.visitAnnotation(SHADOW, false).visitEnd();
        shadow.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A HUD mixin shadowing the method the 26.x HUD rework removed. */
    private static byte[] guiMixin() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "test/InGameHudMixin", null, "java/lang/Object", null);
        var mixin = cw.visitAnnotation(MIXIN, false);
        var values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType("net/minecraft/client/gui/Gui"));
        values.visitEnd();
        mixin.visitEnd();

        MethodVisitor shadow = cw.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                "getCameraPlayer", "()Lnet/minecraft/world/entity/player/Player;", null, null);
        shadow.visitAnnotation(SHADOW, false).visitEnd();
        shadow.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, 0);
        return classNode;
    }

    private static MethodNode method(ClassNode classNode, String name, String desc) {
        return classNode.methods.stream()
                .filter(m -> name.equals(m.name) && desc.equals(m.desc))
                .findFirst().orElseThrow();
    }

    private static boolean hasAnnotation(MethodNode method, String desc) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations.stream().anyMatch(a -> desc.equals(a.desc));
    }

    @Test
    @DisplayName("#168: a MobEffect shadow becomes a Holder-backed bridge and related calls stay old-type compatible")
    void bridgesLegacyMobEffectMembers() {
        ClassNode classNode = read(livingEntityMixin());
        assertTrue(MixinLegacyMemberBridge.apply(classNode));

        MethodNode shadow = method(classNode, "hasEffect", "(L" + MOB_EFFECT + ";)Z");
        assertEquals(0, shadow.access & Opcodes.ACC_ABSTRACT);
        assertFalse(hasAnnotation(shadow, SHADOW));
        assertTrue(hasAnnotation(shadow, UNIQUE));
        assertTrue(calls(shadow, "net/minecraft/core/Registry", "wrapAsHolder",
                "(Ljava/lang/Object;)Lnet/minecraft/core/Holder;"));
        assertTrue(calls(shadow, LIVING_ENTITY, "hasEffect", "(Lnet/minecraft/core/Holder;)Z"));

        MethodNode handler = method(classNode, "handler",
                "(L" + LIVING_ENTITY + ";Lnet/minecraft/world/effect/MobEffectInstance;L"
                        + MOB_EFFECT + ";)V");
        assertTrue(calls(handler, "test/LegacyEffectsMixin", "retromod$getEffect",
                "(L" + MOB_EFFECT + ";)Lnet/minecraft/world/effect/MobEffectInstance;"));
        assertTrue(calls(handler, "net/minecraft/core/Holder", "value", "()Ljava/lang/Object;"));
        assertTrue(java.util.Arrays.stream(handler.instructions.toArray())
                .anyMatch(i -> i instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST
                        && MOB_EFFECT.equals(type.desc)));
    }

    @Test
    @DisplayName("#168: the old Pose enum invoker becomes a modern constructor bridge")
    void bridgesPoseConstructorInvoker() {
        ClassNode classNode = read(poseMixin());
        assertTrue(MixinLegacyMemberBridge.apply(classNode));

        MethodNode method = method(classNode, "newPose", "(Ljava/lang/String;I)L" + POSE + ";");
        assertFalse(hasAnnotation(method, INVOKER));
        assertTrue(hasAnnotation(method, UNIQUE));
        assertTrue(calls(method, "java/lang/String", "toLowerCase",
                "(Ljava/util/Locale;)Ljava/lang/String;"));
        assertTrue(calls(method, POSE, "<init>", "(Ljava/lang/String;IILjava/lang/String;)V"));
    }

    @Test
    @DisplayName("Legacy bridges are gated off before the modern registry and Pose changes")
    void oldHostKeepsOriginalMixins() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        MixinCompatibilityTransformer transformer =
                new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        MethodNode shadow = method(read(transformer.applyLegacyMemberBridges(livingEntityMixin())),
                "hasEffect", "(L" + MOB_EFFECT + ";)Z");
        assertTrue(hasAnnotation(shadow, SHADOW));
        assertEquals(Opcodes.ACC_ABSTRACT, shadow.access & Opcodes.ACC_ABSTRACT);
    }

    @Test
    @DisplayName("#168: a legacy Codec registrar invoker calls the MapCodec registrar instead")
    void bridgesWorldgenTypeRegistrar() {
        ClassNode classNode = read(treeDecoratorTypeMixin(true, false));
        assertTrue(MixinLegacyMemberBridge.apply(classNode));

        MethodNode bridged = method(classNode, "invokeRegister",
                "(Ljava/lang/String;L" + CODEC + ";)L" + TREE_DECORATOR_TYPE + ";");
        assertFalse(hasAnnotation(bridged, INVOKER));
        assertTrue(hasAnnotation(bridged, UNIQUE));
        assertTrue(calls(bridged, TREE_DECORATOR_TYPE, "register",
                "(Ljava/lang/String;L" + MAP_CODEC + ";)L" + TREE_DECORATOR_TYPE + ";"));
        // A record codec is a wrapped MapCodec, so it is unwrapped rather than re-nested.
        assertTrue(calls(bridged, MAP_CODEC_CODEC, "codec", "()L" + MAP_CODEC + ";"));
        assertTrue(calls(bridged, MAP_CODEC, "assumeMapUnsafe",
                "(L" + CODEC + ";)L" + MAP_CODEC + ";"));
    }

    @Test
    @DisplayName("#168: a legacy Codec constructor invoker builds the wrapper from a MapCodec")
    void bridgesWorldgenTypeConstructorInvoker() {
        ClassNode classNode = read(treeDecoratorTypeMixin(false, true));
        assertTrue(MixinLegacyMemberBridge.apply(classNode));

        MethodNode bridged = method(classNode, "newType",
                "(L" + CODEC + ";)L" + TREE_DECORATOR_TYPE + ";");
        assertFalse(hasAnnotation(bridged, INVOKER));
        assertTrue(hasAnnotation(bridged, UNIQUE));
        assertTrue(calls(bridged, TREE_DECORATOR_TYPE, "<init>", "(L" + MAP_CODEC + ";)V"));
    }

    @Test
    @DisplayName("#181: a shadow of the removed Gui.getCameraPlayer reads the camera from the client")
    void bridgesRemovedGuiCameraPlayer() {
        ClassNode classNode = read(guiMixin());
        assertTrue(MixinLegacyMemberBridge.apply(classNode));

        MethodNode bridged = method(classNode, "getCameraPlayer",
                "()Lnet/minecraft/world/entity/player/Player;");
        assertEquals(0, bridged.access & Opcodes.ACC_ABSTRACT, "it has to be callable now");
        assertFalse(hasAnnotation(bridged, SHADOW), "the target no longer has it to shadow");
        assertTrue(hasAnnotation(bridged, UNIQUE));
        assertTrue(calls(bridged, "net/minecraft/client/Minecraft", "getInstance",
                "()Lnet/minecraft/client/Minecraft;"));
        assertTrue(calls(bridged, "net/minecraft/client/Minecraft", "getCameraEntity",
                "()Lnet/minecraft/world/entity/Entity;"));
        // The camera can be a non-player entity, which the removed method reported as null.
        assertTrue(java.util.Arrays.stream(bridged.instructions.toArray())
                .anyMatch(i -> i.getOpcode() == Opcodes.ACONST_NULL),
                "a non-player camera must still return nothing");
    }

    @Test
    @DisplayName("An accessor interface keeps its invoker, because a bridge there could not reach the private member")
    void leavesAccessorInterfaceAlone() {
        ClassNode classNode = read(treeDecoratorTypeAccessorInterface());
        assertFalse(MixinLegacyMemberBridge.apply(classNode));

        MethodNode invoker = method(classNode, "callRegister",
                "(Ljava/lang/String;L" + CODEC + ";)L" + TREE_DECORATOR_TYPE + ";");
        assertTrue(hasAnnotation(invoker, INVOKER), "Mixin still has to widen and resolve it");
        assertFalse(calls(invoker, TREE_DECORATOR_TYPE, "register",
                "(Ljava/lang/String;L" + MAP_CODEC + ";)L" + TREE_DECORATOR_TYPE + ";"),
                "a direct call from outside the target would be an illegal access");
    }

    @Test
    @DisplayName("A bridged registrar carries its own stack map and a consistent stack")
    void bridgedRegistrarKeepsValidFrames() throws Exception {
        ClassNode classNode = read(treeDecoratorTypeMixin(true, true));
        assertTrue(MixinLegacyMemberBridge.apply(classNode));

        // Written with ClassWriter(0), so the frames have to survive a full write and re-read.
        ClassWriter cw = new ClassWriter(0);
        classNode.accept(cw);
        ClassNode reread = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(reread, ClassReader.EXPAND_FRAMES);

        for (MethodNode bridged : reread.methods) {
            if (!bridged.name.startsWith("invokeRegister") && !bridged.name.startsWith("newType")) {
                continue;
            }
            long frames = java.util.Arrays.stream(bridged.instructions.toArray())
                    .filter(org.objectweb.asm.tree.FrameNode.class::isInstance).count();
            assertEquals(2, frames, "the codec branch in " + bridged.name + " needs both frames");
            new org.objectweb.asm.tree.analysis.Analyzer<>(
                    new org.objectweb.asm.tree.analysis.BasicVerifier())
                    .analyze(reread.name, bridged);
        }
    }

    @Test
    @DisplayName("Bridges run after the remap, because a half-remapped mixin matches nothing")
    void legacyBridgesOnlyRunAfterTheRemap() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";

        // transformMixinClass rewrites the Mixin annotation but not the member descriptors, so a
        // Fabric mod is still intermediary-named at that point and no bridge can match it.
        ClassNode halfRemapped = read(halfRemappedLivingEntityMixin());
        assertFalse(MixinLegacyMemberBridge.apply(halfRemapped));

        MixinCompatibilityTransformer transformer =
                new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        MethodNode untouched = method(read(transformer.transformMixinClass(livingEntityMixin())),
                "hasEffect", "(L" + MOB_EFFECT + ";)Z");
        assertTrue(hasAnnotation(untouched, SHADOW));

        // The dedicated post-remap step is what actually bridges the member.
        MethodNode bridged = method(read(transformer.applyLegacyMemberBridges(livingEntityMixin())),
                "hasEffect", "(L" + MOB_EFFECT + ";)Z");
        assertFalse(hasAnnotation(bridged, SHADOW));
        assertTrue(hasAnnotation(bridged, UNIQUE));
    }

    private static boolean calls(MethodNode method, String owner, String name, String desc) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> owner.equals(call.owner) && name.equals(call.name) && desc.equals(call.desc));
    }
}
