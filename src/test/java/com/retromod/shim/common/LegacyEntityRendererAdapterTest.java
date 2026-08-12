/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executable regression for the conservative 26.1 legacy entity-renderer adapter. */
class LegacyEntityRendererAdapterTest {

    private static final String OLD_RENDERER = "test/legacy/EntityRenderer";
    private static final String RENDERER =
            "net/minecraft/client/renderer/entity/EntityRenderer";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String BUFFER = "net/minecraft/client/renderer/MultiBufferSource";
    private static final String STATE =
            "net/minecraft/client/renderer/entity/state/EntityRenderState";
    private static final String TEST_ENTITY = "test/render/LegacyEntity";
    private static final String MOD_RENDERER = "test/render/LegacyRenderer";
    private static final String OTHER_RENDERER = "test/render/OtherRenderer";
    private static final String INDIRECT_BASE = "test/render/IntermediateRenderer";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restoreState() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void postRemapAdapterInjectsStateFactoryAndNeutralizesOnlyTheOldBaseCall()
            throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_RENDERER, RENDERER);

        byte[] transformed = transformer.transformClass(
                legacyRenderer(MOD_RENDERER, OLD_RENDERER, BUFFER, true, false),
                MOD_RENDERER + ".class");
        ClassNode output = read(transformed);

        assertEquals(RENDERER, output.superName, "the adapter must run after class remapping");
        assertDoesNotThrow(() -> new ClassReader(transformed)
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0));

        MethodNode stateFactory = output.methods.stream()
                .filter(method -> "createRenderState".equals(method.name)
                        && ("()L" + STATE + ";").equals(method.desc))
                .findFirst().orElseThrow();
        assertTrue((stateFactory.access & Opcodes.ACC_PUBLIC) != 0);
        assertTrue(stateFactory.instructions.iterator().hasNext());

        MethodNode typedRender = output.methods.stream()
                .filter(method -> "render".equals(method.name)
                        && legacyDescriptor(TEST_ENTITY, BUFFER).equals(method.desc))
                .findFirst().orElseThrow();
        long popCount = java.util.Arrays.stream(typedRender.instructions.toArray())
                .filter(InsnNode.class::isInstance)
                .map(InsnNode.class::cast)
                .filter(instruction -> instruction.getOpcode() == Opcodes.POP)
                .count();
        assertEquals(7, popCount, "six legacy arguments and the receiver must be discarded");
        assertFalse(hasCall(typedRender, Opcodes.INVOKESPECIAL, RENDERER,
                "render", legacyDescriptor(ENTITY, BUFFER)));
        assertTrue(hasCall(typedRender, Opcodes.INVOKEVIRTUAL, OTHER_RENDERER,
                "render", legacyDescriptor(ENTITY, BUFFER)),
                "a render-shaped call to another owner must remain intact");

        Map<String, byte[]> definitions = new HashMap<>();
        definitions.put(ENTITY.replace('/', '.'), plainClass(ENTITY, "java/lang/Object"));
        definitions.put(TEST_ENTITY.replace('/', '.'), plainClass(TEST_ENTITY, ENTITY));
        definitions.put(POSE_STACK.replace('/', '.'), plainClass(POSE_STACK, "java/lang/Object"));
        definitions.put(BUFFER.replace('/', '.'), markerInterface(BUFFER));
        definitions.put(STATE.replace('/', '.'), plainClass(STATE, "java/lang/Object"));
        definitions.put(RENDERER.replace('/', '.'), modernEntityRenderer());
        definitions.put(OTHER_RENDERER.replace('/', '.'), otherRenderer());
        definitions.put(MOD_RENDERER.replace('/', '.'), transformed);
        ClassLoader loader = new ByteArrayClassLoader(definitions);

        Class<?> rendererClass = Class.forName(MOD_RENDERER.replace('/', '.'), true, loader);
        Object renderer = rendererClass.getConstructor().newInstance();
        Method createState = rendererClass.getMethod("createRenderState");
        Class<?> stateClass = Class.forName(STATE.replace('/', '.'), false, loader);
        assertEquals(stateClass, createState.getReturnType());
        assertTrue(stateClass.isInstance(createState.invoke(renderer)));

        Class<?> entityClass = Class.forName(TEST_ENTITY.replace('/', '.'), true, loader);
        Class<?> poseClass = Class.forName(POSE_STACK.replace('/', '.'), true, loader);
        Class<?> bufferClass = Class.forName(BUFFER.replace('/', '.'), true, loader);
        Object entity = entityClass.getConstructor().newInstance();
        Object pose = poseClass.getConstructor().newInstance();
        rendererClass.getField("marker").setInt(null, 0);
        rendererClass.getMethod("render", entityClass, float.class, float.class,
                poseClass, bufferClass, int.class)
                .invoke(renderer, entity, 0.25f, 0.5f, pose, null, 9);
        assertEquals(2, rendererClass.getField("marker").getInt(null),
                "execution must continue after the removed base call");
        Class<?> otherClass = Class.forName(OTHER_RENDERER.replace('/', '.'), false, loader);
        assertEquals(1, otherClass.getField("calls").getInt(null),
                "the unrelated render-shaped invocation must still execute");
    }

    @Test
    void unrelatedRendererShapesRemainByteForByteUntouched() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";

        byte[] noErasedLegacyMethod = legacyRenderer(
                "test/render/TypedOnly", RENDERER, BUFFER, false, false);
        byte[] alreadyHasStateFactory = legacyRenderer(
                "test/render/AlreadyModern", RENDERER, BUFFER, true, true);
        byte[] indirectSubclass = legacyRenderer(
                "test/render/Indirect", INDIRECT_BASE, BUFFER, true, false);
        byte[] unrelatedBuffer = legacyRenderer(
                "test/render/OtherBuffer", RENDERER, "test/render/NotMultiBuffer", true, false);

        assertSame(noErasedLegacyMethod,
                LegacyEntityRendererAdapter.apply(noErasedLegacyMethod));
        assertSame(alreadyHasStateFactory,
                LegacyEntityRendererAdapter.apply(alreadyHasStateFactory));
        assertSame(indirectSubclass,
                LegacyEntityRendererAdapter.apply(indirectSubclass));
        assertSame(unrelatedBuffer,
                LegacyEntityRendererAdapter.apply(unrelatedBuffer));
    }

    @Test
    void versionGateStartsAt26_1() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        byte[] input = legacyRenderer(MOD_RENDERER, RENDERER, BUFFER, true, false);

        assertSame(input, LegacyEntityRendererAdapter.apply(input));

        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertNotSame(input, LegacyEntityRendererAdapter.apply(input));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean hasCall(MethodNode method, int opcode, String owner,
            String name, String descriptor) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.getOpcode() == opcode
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && descriptor.equals(call.desc));
    }

    private static byte[] legacyRenderer(String name, String superName, String buffer,
            boolean addErasedBridge, boolean addStateFactory) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "marker", "I", null, null)
                .visitEnd();
        noArgConstructor(writer, superName);

        MethodVisitor typed = writer.visitMethod(Opcodes.ACC_PUBLIC, "render",
                legacyDescriptor(TEST_ENTITY, buffer), null, null);
        typed.visitCode();
        typed.visitInsn(Opcodes.ICONST_1);
        typed.visitFieldInsn(Opcodes.PUTSTATIC, name, "marker", "I");
        loadLegacyArguments(typed);
        typed.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "render",
                legacyDescriptor(ENTITY, buffer), false);
        typed.visitInsn(Opcodes.ICONST_2);
        typed.visitFieldInsn(Opcodes.PUTSTATIC, name, "marker", "I");
        typed.visitTypeInsn(Opcodes.NEW, OTHER_RENDERER);
        typed.visitInsn(Opcodes.DUP);
        typed.visitMethodInsn(Opcodes.INVOKESPECIAL, OTHER_RENDERER, "<init>", "()V", false);
        typed.visitVarInsn(Opcodes.ALOAD, 1);
        typed.visitVarInsn(Opcodes.FLOAD, 2);
        typed.visitVarInsn(Opcodes.FLOAD, 3);
        typed.visitVarInsn(Opcodes.ALOAD, 4);
        typed.visitVarInsn(Opcodes.ALOAD, 5);
        typed.visitVarInsn(Opcodes.ILOAD, 6);
        typed.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OTHER_RENDERER, "render",
                legacyDescriptor(ENTITY, buffer), false);
        typed.visitInsn(Opcodes.RETURN);
        typed.visitMaxs(0, 0);
        typed.visitEnd();

        if (addErasedBridge) {
            MethodVisitor bridge = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC,
                    "render", legacyDescriptor(ENTITY, buffer), null, null);
            bridge.visitCode();
            bridge.visitVarInsn(Opcodes.ALOAD, 0);
            bridge.visitVarInsn(Opcodes.ALOAD, 1);
            bridge.visitTypeInsn(Opcodes.CHECKCAST, TEST_ENTITY);
            bridge.visitVarInsn(Opcodes.FLOAD, 2);
            bridge.visitVarInsn(Opcodes.FLOAD, 3);
            bridge.visitVarInsn(Opcodes.ALOAD, 4);
            bridge.visitVarInsn(Opcodes.ALOAD, 5);
            bridge.visitVarInsn(Opcodes.ILOAD, 6);
            bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "render",
                    legacyDescriptor(TEST_ENTITY, buffer), false);
            bridge.visitInsn(Opcodes.RETURN);
            bridge.visitMaxs(0, 0);
            bridge.visitEnd();
        }

        if (addStateFactory) addStateFactory(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void loadLegacyArguments(MethodVisitor method) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.FLOAD, 2);
        method.visitVarInsn(Opcodes.FLOAD, 3);
        method.visitVarInsn(Opcodes.ALOAD, 4);
        method.visitVarInsn(Opcodes.ALOAD, 5);
        method.visitVarInsn(Opcodes.ILOAD, 6);
    }

    private static void addStateFactory(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "createRenderState",
                "()L" + STATE + ";", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, STATE);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, STATE, "<init>", "()V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] modernEntityRenderer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                RENDERER, null, "java/lang/Object", null);
        noArgConstructor(writer, "java/lang/Object");
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "createRenderState", "()L" + STATE + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] otherRenderer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                OTHER_RENDERER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null)
                .visitEnd();
        noArgConstructor(writer, "java/lang/Object");
        MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC, "render",
                legacyDescriptor(ENTITY, BUFFER), null, null);
        render.visitCode();
        render.visitFieldInsn(Opcodes.GETSTATIC, OTHER_RENDERER, "calls", "I");
        render.visitInsn(Opcodes.ICONST_1);
        render.visitInsn(Opcodes.IADD);
        render.visitFieldInsn(Opcodes.PUTSTATIC, OTHER_RENDERER, "calls", "I");
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] plainClass(String name, String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        noArgConstructor(writer, superName);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] markerInterface(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void noArgConstructor(ClassWriter writer, String superName) {
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static String legacyDescriptor(String entity, String buffer) {
        return "(L" + entity + ";FFL" + POSE_STACK + ";L" + buffer + ";I)V";
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        private ByteArrayClassLoader(Map<String, byte[]> definitions) {
            super(LegacyEntityRendererAdapterTest.class.getClassLoader());
            this.definitions = definitions;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) return super.findClass(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
