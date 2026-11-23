/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Old render accessors reach the host's replacements, and only on hosts that lost them. */
class LegacyRenderAccessBridgeTest {

    private static final String GAME_RENDERER = LegacyRenderAccessBridge.GAME_RENDERER;
    private static final String RENDER_SYSTEM = LegacyRenderAccessBridge.RENDER_SYSTEM;
    private static final String LEVEL_RENDERER = LegacyRenderAccessBridge.LEVEL_RENDERER;
    private static final String SHADER = "net/minecraft/client/renderer/ShaderInstance";
    private static final String PEARL_PROGRAM = "com/mojang/renderpearl/backend/opengl/GlProgram";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.20.1 shader getter and its method reference both link to the helper")
    void shaderGettersAndReferences() {
        RetromodTransformer t = registered(host263());
        byte[] out = t.transformClass(method("test/render/OldShaders", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GAME_RENDERER, "getPositionTexShader", "()L" + SHADER + ";", false);
            mv.visitInsn(Opcodes.POP);
            // RenderSystem.setShader(GameRenderer::getPositionColorShader) as Fabric sees it on 26.3.
            mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", metafactory(),
                    Type.getType("()Ljava/lang/Object;"),
                    new Handle(Opcodes.H_INVOKESTATIC, GAME_RENDERER, "getPositionColorShader", "()L" + PEARL_PROGRAM + ";", false),
                    Type.getType("()L" + PEARL_PROGRAM + ";"));
            mv.visitInsn(Opcodes.POP);
            // The same reference as Forge 26.2 sees it, where the old shader class keeps its removed name.
            mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", metafactory(),
                    Type.getType("()Ljava/lang/Object;"),
                    new Handle(Opcodes.H_INVOKESTATIC, GAME_RENDERER, "getPositionTexShader", "()L" + SHADER + ";", false),
                    Type.getType("()L" + SHADER + ";"));
            mv.visitInsn(Opcodes.POP);
        }), "test/render/OldShaders");

        ClassNode node = read(out);
        assertTrue(calls(node).contains(LegacyRenderAccessBridge.SHADERS + ".none()Ljava/lang/Object;"), calls(node).toString());
        for (InvokeDynamicInsnNode indy : indys(node)) {
            Handle reference = (Handle) indy.bsmArgs[1];
            assertEquals(LegacyRenderAccessBridge.SHADERS, reference.getOwner());
            assertEquals("none", reference.getName());
            // The metafactory resolves the instantiated type, which must not name the removed shader class.
            assertEquals(Type.getType("()Ljava/lang/Object;"), indy.bsmArgs[2]);
        }
        assertEquals(2, indys(node).size());
    }

    @Test
    @DisplayName("the PoseStack model view, the main target, the light lookup and allChanged reach their 26.3 homes")
    void levelCallsReachTheHost() {
        RetromodTransformer t = registered(host263());
        String tint = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;";
        byte[] out = t.transformClass(method("test/render/OldLevel", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RENDER_SYSTEM, "getModelViewStack", "()Lcom/mojang/blaze3d/vertex/PoseStack;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Minecraft", "getMainRenderTarget",
                    "()Lcom/mojang/blaze3d/pipeline/RenderTarget;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LEVEL_RENDERER, "getLightColor", "(" + tint + "Lnet/minecraft/core/BlockPos;)I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL_RENDERER, "allChanged", "()V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL_RENDERER, "needsUpdate", "()V", false);
        }), "test/render/OldLevel");

        String calls = String.join("\n", calls(read(out)));
        assertTrue(calls.contains(LegacyRenderAccessBridge.MODEL_VIEW + ".stack()"), calls);
        assertTrue(calls.contains(LegacyRenderAccessBridge.LEVEL_CALLS + ".mainRenderTarget(Lnet/minecraft/client/Minecraft;)"), calls);
        assertTrue(calls.contains(LegacyRenderAccessBridge.LIGHT_COORDS + ".getLightCoords(" + LegacyRenderAccessBridge.LIGHT_GETTER), calls);
        assertTrue(calls.contains(LegacyRenderAccessBridge.LEVEL_CALLS + ".allChanged(L" + LEVEL_RENDERER + ";)V"), calls);
        assertTrue(calls.contains(LegacyRenderAccessBridge.LEVEL_CALLS + ".needsUpdate(L" + LEVEL_RENDERER + ";)V"), calls);
    }

    @Test
    @DisplayName("the light lookup goes to LevelRenderer on 26.1, which still keeps it there")
    void lightLookupFollowsTheHost() {
        Map<String, ClassNode> host = host263();
        // 26.1 has LightCoordsUtil, without the light lookup.
        host.put(LegacyRenderAccessBridge.LIGHT_COORDS, owner(LegacyRenderAccessBridge.LIGHT_COORDS));
        host.put(LEVEL_RENDERER, owner(LEVEL_RENDERER, "getLightCoords", LegacyRenderAccessBridge.NEW_LIGHT, "allChanged", "()V"));
        RetromodTransformer t = registered(host);
        byte[] out = t.transformClass(method("test/render/OldLight", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LEVEL_RENDERER, "getLightColor",
                    "(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL_RENDERER, "allChanged", "()V", false);
        }), "test/render/OldLight");

        List<String> calls = calls(read(out));
        assertTrue(calls.contains(LEVEL_RENDERER + ".getLightCoords" + LegacyRenderAccessBridge.NEW_LIGHT), calls.toString());
        assertTrue(calls.contains(LEVEL_RENDERER + ".allChanged()V"), "26.1 still has allChanged: " + calls);
    }

    @Test
    @DisplayName("texture filter and id calls through a vanilla texture reach the helper")
    void textureCalls() {
        RetromodTransformer t = registered(host263());
        String dynamic = "net/minecraft/client/renderer/texture/DynamicTexture";
        byte[] out = t.transformClass(method("test/render/OldTexture", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, dynamic, "setFilter", "(ZZ)V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderAccessBridge.ABSTRACT_TEXTURE, "getId", "()I", false);
            mv.visitInsn(Opcodes.POP);
        }), "test/render/OldTexture");

        List<String> calls = calls(read(out));
        String helper = LegacyRenderAccessBridge.TEXTURE_CALLS;
        assertTrue(calls.contains(helper + ".setFilter(L" + LegacyRenderAccessBridge.ABSTRACT_TEXTURE + ";ZZ)V"), calls.toString());
        assertTrue(calls.contains(helper + ".getId(L" + LegacyRenderAccessBridge.ABSTRACT_TEXTURE + ";)I"), calls.toString());
    }

    @Test
    @DisplayName("a host that still has the old members keeps every call")
    void olderHostKeepsItsMembers() {
        Map<String, ClassNode> host = new HashMap<>();
        host.put(GAME_RENDERER, owner(GAME_RENDERER, "getPositionTexShader", "()L" + SHADER + ";"));
        host.put(RENDER_SYSTEM, owner(RENDER_SYSTEM, "getModelViewStack", "()Lcom/mojang/blaze3d/vertex/PoseStack;"));
        host.put("net/minecraft/client/Minecraft", owner("net/minecraft/client/Minecraft", "getMainRenderTarget",
                "()Lcom/mojang/blaze3d/pipeline/RenderTarget;"));
        host.put(LEVEL_RENDERER, owner(LEVEL_RENDERER, "allChanged", "()V", "needsUpdate", "()V", "getLightColor",
                "(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"));
        host.put(LegacyRenderAccessBridge.ABSTRACT_TEXTURE, owner(LegacyRenderAccessBridge.ABSTRACT_TEXTURE, "setFilter", "(ZZ)V",
                "getId", "()I"));
        RetromodTransformer t = registered(host);

        assertTrue(t.getMethodRedirects().keySet().stream().noneMatch(key -> key.owner().equals(GAME_RENDERER)
                || key.owner().equals(RENDER_SYSTEM) || key.owner().equals(LEVEL_RENDERER)
                || key.owner().equals("net/minecraft/client/Minecraft")
                || key.owner().equals(LegacyRenderAccessBridge.ABSTRACT_TEXTURE)), t.getMethodRedirects().keySet().toString());
    }

    // The 26.3 shapes of each owner the bridge reads, as javap shows them.
    private static Map<String, ClassNode> host263() {
        Map<String, ClassNode> host = new HashMap<>();
        host.put(GAME_RENDERER, owner(GAME_RENDERER, "mainRenderTarget", "()Lcom/mojang/blaze3d/pipeline/RenderTarget;"));
        host.put(RENDER_SYSTEM, owner(RENDER_SYSTEM, "getModelViewStack", "()Lorg/joml/Matrix4fStack;"));
        host.put("net/minecraft/client/Minecraft", owner("net/minecraft/client/Minecraft", "getWindow",
                "()Lcom/mojang/blaze3d/platform/Window;"));
        host.put(LEVEL_RENDERER, owner(LEVEL_RENDERER, "sectionOcclusionGraph",
                "()Lnet/minecraft/client/renderer/SectionOcclusionGraph;"));
        host.put(LegacyRenderAccessBridge.LEVEL_EXTRACTOR, owner(LegacyRenderAccessBridge.LEVEL_EXTRACTOR, "allChanged", "()V"));
        host.put(LegacyRenderAccessBridge.LIGHT_COORDS, owner(LegacyRenderAccessBridge.LIGHT_COORDS, "getLightCoords",
                LegacyRenderAccessBridge.NEW_LIGHT));
        host.put(LegacyRenderAccessBridge.ABSTRACT_TEXTURE, owner(LegacyRenderAccessBridge.ABSTRACT_TEXTURE, "getSampler",
                "()Lcom/mojang/renderpearl/api/textures/GpuSampler;", "getTexture", "()Lcom/mojang/renderpearl/api/textures/GpuTexture;"));
        return host;
    }

    private static RetromodTransformer registered(Map<String, ClassNode> host) {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRenderAccessBridge.register(t, host::get);
        return t;
    }

    private static ClassNode owner(String name, String... methods) {
        ClassNode node = new ClassNode();
        node.name = name;
        for (int i = 0; i < methods.length; i += 2) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, methods[i], methods[i + 1], null, null));
        }
        return node;
    }

    private static Handle metafactory() {
        return new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    private static byte[] method(String name, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }));
        return calls;
    }

    private static List<InvokeDynamicInsnNode> indys(ClassNode node) {
        List<InvokeDynamicInsnNode> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy) found.add(indy);
            }
        }
        return found;
    }
}
