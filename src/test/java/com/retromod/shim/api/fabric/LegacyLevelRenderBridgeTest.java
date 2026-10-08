/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phantom Shapes registers on WorldRenderEvents.END and draws through VertexBuffer (#296). */
class LegacyLevelRenderBridgeTest {

    private static final String OLD_CONTEXT = "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext";
    private static final String PROBE = "probe/ShapesClient";

    private RetromodTransformer transformer;

    @BeforeEach
    void register() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_CONTEXT, LegacyLevelRenderBridge.LEVEL_CONTEXT);
        LegacyLevelRenderBridge.register(transformer);
    }

    @AfterEach
    void clear() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void endRegistrationReachesTheSyntheticHolderAndCallback() {
        MethodNode register = method(transformer.transformClass(fixture(), PROBE), "register");
        FieldInsnNode event = first(register, FieldInsnNode.class);
        InvokeDynamicInsnNode lambda = first(register, InvokeDynamicInsnNode.class);

        assertEquals(LegacyLevelRenderBridge.SYNTH, event.owner,
                "END must come from the holder that copies LevelRenderEvents.END_MAIN");
        assertEquals("()L" + LegacyLevelRenderBridge.SYNTH + "$End;", lambda.desc,
                "the mod's lambda must implement the bridged End callback");
    }

    @Test
    void endCallbackForwardsEndMainToTheOldMethod() {
        ClassNode callback = read(LegacyLevelRenderBridge.callbackBridge(LegacyLevelRenderBridge.SYNTH + "$End",
                LegacyLevelRenderBridge.LEVEL_EVENTS + "$EndMain", "onEnd", "endMain",
                LegacyLevelRenderBridge.LEVEL_CONTEXT));

        assertEquals(LegacyLevelRenderBridge.LEVEL_EVENTS + "$EndMain", callback.interfaces.get(0));
        MethodNode forward = callback.methods.stream().filter(m -> m.name.equals("endMain")).findFirst()
                .orElseThrow();
        MethodInsnNode call = first(forward, MethodInsnNode.class);
        assertEquals("onEnd", call.name, "Fabric calls endMain, which must reach the mod's onEnd");
    }

    @Test
    void contextCameraIsReadFromTheClientAndCastBack() {
        MethodNode camera = method(transformer.transformClass(fixture(), PROBE), "camera");
        MethodInsnNode call = first(camera, MethodInsnNode.class);

        assertEquals("com/retromod/polyfill/minecraft/LegacyWorldRenderContext", call.owner);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertTrue(call.getNext() instanceof TypeInsnNode cast && cast.desc.equals("net/minecraft/client/Camera"),
                "the helper returns Object, so the caller's Camera type must be restored");
    }

    @Test
    void vertexBufferUploadGoesToTheStandIn() {
        MethodNode upload = method(transformer.transformClass(fixture(), PROBE), "upload");
        MethodInsnNode call = first(upload, MethodInsnNode.class);

        assertEquals(LegacyLevelRenderBridge.VERTEX_BUFFER_POLYFILL, call.owner);
        assertEquals("(Ljava/lang/Object;)V", call.desc);
    }

    private static byte[] fixture() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PROBE, null, "java/lang/Object", null);
        String oldEvents = "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents";

        MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()V", null, null);
        register.visitCode();
        register.visitFieldInsn(Opcodes.GETSTATIC, oldEvents, "END", "Lnet/fabricmc/fabric/api/event/Event;");
        register.visitInvokeDynamicInsn("onEnd", "()L" + oldEvents + "$End;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType("(L" + OLD_CONTEXT + ";)V"),
                new Handle(Opcodes.H_INVOKESTATIC, PROBE, "camera", "(L" + OLD_CONTEXT + ";)V", false),
                Type.getMethodType("(L" + OLD_CONTEXT + ";)V"));
        register.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/fabricmc/fabric/api/event/Event", "register",
                "(Ljava/lang/Object;)V", false);
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(2, 0);
        register.visitEnd();

        MethodVisitor camera = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "camera",
                "(L" + OLD_CONTEXT + ";)V", null, null);
        camera.visitCode();
        camera.visitVarInsn(Opcodes.ALOAD, 0);
        camera.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD_CONTEXT, "camera", "()Lnet/minecraft/client/Camera;", true);
        camera.visitInsn(Opcodes.POP);
        camera.visitInsn(Opcodes.RETURN);
        camera.visitMaxs(1, 1);
        camera.visitEnd();

        MethodVisitor upload = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "upload",
                "(Lcom/mojang/blaze3d/vertex/VertexBuffer;Lcom/mojang/blaze3d/vertex/MeshData;)V", null, null);
        upload.visitCode();
        upload.visitVarInsn(Opcodes.ALOAD, 0);
        upload.visitVarInsn(Opcodes.ALOAD, 1);
        upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/mojang/blaze3d/vertex/VertexBuffer", "upload",
                "(Lcom/mojang/blaze3d/vertex/MeshData;)V", false);
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(2, 2);
        upload.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(byte[] bytes, String name) {
        return read(bytes).methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name));
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }
}
