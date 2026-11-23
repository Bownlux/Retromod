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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Single removed members reach their rebuilt helpers, and only where the host lacks them. */
class LegacySmallCallsBridgeTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on a host without the old members every call goes to a helper or its replacement")
    void callsAreRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySmallCallsBridge.register(t, name -> null);

        List<String> calls = calls(t.transformClass(oldCalls(), "test/small/Old"));

        String client = LegacySmallCallsBridge.CLIENT + ".";
        String world = LegacySmallCallsBridge.WORLD + ".";
        assertTrue(calls.contains(client + "setKey(L" + LegacySmallCallsBridge.OPTIONS + ";" + LegacySmallCallsBridge.SET_KEY.substring(1)), calls.toString());
        assertTrue(calls.contains(LegacySmallCallsBridge.WIDGET + ".isMouseOver(DD)Z"), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(world + "spawnAtLocation(")), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(world + "isOp(")), calls.toString());
        assertTrue(calls.contains(LegacySmallCallsBridge.DEVICE + ".maxSupportedTextureSize()I"), calls.toString());
        assertFalse(calls.stream().anyMatch(c -> c.contains(".clicked(") || c.startsWith(LegacySmallCallsBridge.OPTIONS + ".setKey")),
                "no removed call may stay: " + calls);
    }

    @Test
    @DisplayName("every old spawnAtLocation form and getArmorSlots reach the helpers, also when called on Minecraft's own subclasses")
    void entityFormsAndVanillaOwners() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySmallCallsBridge.register(t, name -> null);
        String boat = "net/minecraft/world/entity/vehicle/boat/Boat";
        String player = "net/minecraft/world/entity/player/Player";
        String item = LegacySmallCallsBridge.ITEM_ENTITY;

        List<String> calls = calls(t.transformClass(method("test/small/Entities", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boat, "spawnAtLocation", "(" + LegacySmallCallsBridge.STACK + "F)" + item, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, player, "spawnAtLocation", "(" + LegacySmallCallsBridge.ITEM_LIKE + ")" + item, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.ENTITY, "spawnAtLocation",
                    "(" + LegacySmallCallsBridge.ITEM_LIKE + "I)" + item, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, player, "getArmorSlots", "()Ljava/lang/Iterable;", false);
            mv.visitInsn(Opcodes.POP);
        }), "test/small/Entities"));

        String world = LegacySmallCallsBridge.WORLD + ".";
        String entity = "(L" + LegacySmallCallsBridge.ENTITY + ";";
        assertTrue(calls.contains(world + "spawnAtLocation" + entity + LegacySmallCallsBridge.STACK + "F)" + item), calls.toString());
        assertTrue(calls.contains(world + "spawnAtLocation" + entity + LegacySmallCallsBridge.ITEM_LIKE + ")" + item), calls.toString());
        assertTrue(calls.contains(world + "spawnAtLocation" + entity + LegacySmallCallsBridge.ITEM_LIKE + "I)" + item), calls.toString());
        assertTrue(calls.contains(world + "getArmorSlots(L" + LegacySmallCallsBridge.LIVING + ";)Ljava/lang/Iterable;"), calls.toString());
        assertFalse(calls.stream().anyMatch(c -> c.contains(".spawnAtLocation(L") && !c.startsWith(world)), calls.toString());
    }

    @Test
    @DisplayName("the largest texture size comes from the device's info record on 26.2 and newer, which has no getMaxTextureSize")
    void maxTextureSizeFromDeviceInfo() throws Exception {
        java.util.Map<String, String> rename = java.util.Map.of(
                com.retromod.testsupport.fakemc.device.FakeRenderSystem.class.getName().replace('.', '/'),
                "com/mojang/blaze3d/systems/RenderSystem");
        java.util.Map<String, byte[]> classes = new java.util.HashMap<>();
        Class<?> fake = com.retromod.testsupport.fakemc.device.FakeRenderSystem.class;
        try (java.io.InputStream in = fake.getResourceAsStream(fake.getSimpleName() + ".class")) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(in.readAllBytes()).accept(new org.objectweb.asm.commons.ClassRemapper(writer,
                    new org.objectweb.asm.commons.SimpleRemapper(rename)), 0);
            classes.put("com.mojang.blaze3d.systems.RenderSystem", writer.toByteArray());
        }
        Class<?> helper = com.retromod.shim.common.embedded.LegacyDeviceLimits.class;
        try (java.io.InputStream in = helper.getResourceAsStream(helper.getSimpleName() + ".class")) {
            classes.put(helper.getName(), in.readAllBytes());
        }
        ClassLoader loader = new ClassLoader(LegacySmallCallsBridgeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    byte[] bytes = classes.get(name);
                    return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, resolve);
                }
            }
        };

        Object size = loader.loadClass(helper.getName()).getMethod("maxSupportedTextureSize").invoke(null);

        assertEquals(16384, size, "the device info's limit, not the 1024 fallback");
    }

    @Test
    @DisplayName("a host that still has a member keeps the mod's call to it")
    void hostWithTheMemberIsLeftAlone() {
        ClassNode widget = withMethod("clicked", "(DD)Z");
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySmallCallsBridge.register(t, Map.of(LegacySmallCallsBridge.WIDGET, widget)::get);

        List<String> calls = calls(t.transformClass(oldCalls(), "test/small/Old"));

        assertTrue(calls.contains(LegacySmallCallsBridge.WIDGET + ".clicked(DD)Z"), calls.toString());
    }

    @Test
    @DisplayName("Pack.open is bridged only where it returns a stream of layers")
    void packGate() {
        ClassNode stream = withMethod("open", LegacySmallCallsBridge.STREAM_OPEN);
        ClassNode single = withMethod("open", LegacySmallCallsBridge.OLD_OPEN);
        assertTrue(LegacySmallCallsBridge.packOpensAsStream(stream));
        assertFalse(LegacySmallCallsBridge.packOpensAsStream(single));
    }

    @Test
    @DisplayName("the shared buffer source and the removed accessors generate verifiable code")
    void renderAccessors() throws AnalyzerException {
        ClassNode source = node(RenderBufferSynthetics.generateBufferSource());
        for (MethodNode method : source.methods) new Analyzer<>(new BasicVerifier()).analyze(source.name, method);
        assertTrue(source.methods.stream().anyMatch(m -> m.name.equals("shared")), "the stand-in needs shared()");

        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        RenderBufferSynthetics.register(t);
        byte[] out = t.transformClass(method("test/small/Tes", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacySmallCallsBridge.RENDER_SYSTEM, "renderThreadTesselator",
                    "()Lcom/mojang/blaze3d/vertex/Tesselator;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, RenderBufferSynthetics.LEVEL_CONTEXT, "consumers",
                    "()Lnet/minecraft/client/renderer/MultiBufferSource;", true);
            mv.visitInsn(Opcodes.POP);
        }), "test/small/Tes");
        List<String> calls = calls(out);
        // The receiver of consumers() must be consumed by the helper, or the stack no longer balances.
        ClassNode rewritten = node(out);
        for (MethodNode method : rewritten.methods) new Analyzer<>(new BasicVerifier()).analyze(rewritten.name, method);
        assertTrue(calls.contains(RenderBufferSynthetics.TES + ".getInstance()L" + RenderBufferSynthetics.TES + ";"), calls.toString());
        assertTrue(calls.contains(RenderBufferSynthetics.BS + ".sharedFor(Ljava/lang/Object;)L" + RenderBufferSynthetics.BS + ";"),
                calls.toString());
    }

    private static byte[] oldCalls() {
        return method("test/small/Old", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.OPTIONS, "setKey", LegacySmallCallsBridge.SET_KEY, false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.WIDGET, "clicked", "(DD)Z", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.ENTITY, "spawnAtLocation", LegacySmallCallsBridge.SPAWN, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.PLAYER_LIST, "isOp", LegacySmallCallsBridge.IS_OP, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacySmallCallsBridge.RENDER_SYSTEM, "maxSupportedTextureSize", "()I", false);
            mv.visitInsn(Opcodes.POP);
        });
    }

    private static ClassNode withMethod(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    private static byte[] method(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(8, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
