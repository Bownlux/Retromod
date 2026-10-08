/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.21.x mod's own quad loop on 26.1 and newer: material accessors and putBulkData. */
class LegacyBakedQuadSyntheticTest {

    private static final String QUAD = "net/minecraft/client/resources/model/geometry/BakedQuad";
    private static final String CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String POSE = "com/mojang/blaze3d/vertex/PoseStack$Pose";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBakedQuadSynthetic.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("quad.tintIndex() goes through the forwarder that reads materialInfo()")
    void tintIndexReadsTheMaterial() {
        List<AbstractInsnNode> body = transformBody("(L" + QUAD + ";)I", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, QUAD, "tintIndex", "()I", false);
            mv.visitInsn(IRETURN);
        });
        MethodInsnNode call = firstCall(body, LegacyBakedQuadSynthetic.INTERNAL, "tintIndex");
        assertNotNull(call, "the removed record accessor must reach the forwarder");
        assertEquals(INVOKESTATIC, call.getOpcode(), "the quad becomes the forwarder's first argument");
        assertEquals("(L" + QUAD + ";)I", call.desc);
    }

    @Test
    @DisplayName("A Mojang-named mod's old-package quad calls reach the forwarder after the class move")
    void oldPackageQuadCallsReachTheForwarder() {
        String oldQuad = "net/minecraft/client/renderer/block/model/BakedQuad";
        transformer.registerClassRedirect(oldQuad, QUAD);
        String tail = LegacyBakedQuadSynthetic.PER_VERTEX_TAIL;
        List<AbstractInsnNode> body = transformBody("(L" + CONSUMER + ";L" + POSE + ";L" + oldQuad + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, oldQuad, "tintIndex", "()I", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKEINTERFACE, CONSUMER, "putBulkData", "(L" + POSE + ";L" + oldQuad + ";" + tail, true);
            mv.visitInsn(RETURN);
        });
        MethodInsnNode tint = firstCall(body, LegacyBakedQuadSynthetic.INTERNAL, "tintIndex");
        assertNotNull(tint, "a NeoForge or Forge mod still names the 1.21.x quad package");
        assertEquals("(L" + QUAD + ";)I", tint.desc);
        MethodInsnNode put = firstCall(body, LegacyBakedQuadSynthetic.INTERNAL, "putBulkData");
        assertNotNull(put, "the per-vertex putBulkData with the old quad package must reach the forwarder");
        assertEquals("(L" + CONSUMER + ";L" + POSE + ";L" + QUAD + ";" + tail, put.desc);
        assertFalse(put.itf, "the forwarder is a class, so the call must not carry the interface flag");
    }

    @Test
    @DisplayName("consumer.putBulkData(pose, quad, r, g, b, a, light, overlay) becomes a static forwarder call")
    void putBulkDataBecomesAForwarderCall() {
        String tail = LegacyBakedQuadSynthetic.UNIFORM_TAIL;
        List<AbstractInsnNode> body = transformBody("(L" + CONSUMER + ";L" + POSE + ";L" + QUAD + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKEINTERFACE, CONSUMER, "putBulkData",
                    "(L" + POSE + ";L" + QUAD + ";" + tail, true);
            mv.visitInsn(RETURN);
        });
        MethodInsnNode call = firstCall(body, LegacyBakedQuadSynthetic.INTERNAL, "putBulkData");
        assertNotNull(call, "putBulkData was removed at 26.1 and must reach the forwarder");
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals("(L" + CONSUMER + ";L" + POSE + ";L" + QUAD + ";" + tail, call.desc);
    }

    @Test
    @DisplayName("Per-vertex putBulkData scales the color by brightness and keeps each vertex's light")
    void perVertexPutBulkDataFillsTheQuadInstance() throws Exception {
        ClassLoader host = host();
        Class<?> forwarder = host.loadClass(LegacyBakedQuadSynthetic.INTERNAL.replace('/', '.'));
        Class<?> consumerType = host.loadClass("com.mojang.blaze3d.vertex.VertexConsumer");
        Class<?> recorder = host.loadClass("com.mojang.blaze3d.vertex.RecordingConsumer");
        Object consumer = recorder.getConstructor().newInstance();
        Object pose = host.loadClass("com.mojang.blaze3d.vertex.PoseStack$Pose").getConstructor().newInstance();
        Object quad = host.loadClass(QUAD.replace('/', '.')).getConstructor().newInstance();

        Method put = forwarder.getMethod("putBulkData", consumerType, pose.getClass(), quad.getClass(),
                float[].class, float.class, float.class, float.class, float.class, int[].class, int.class);
        put.invoke(null, consumer, pose, quad, new float[]{1F, 0.5F, 0F, 1F}, 1F, 1F, 1F, 1F,
                new int[]{10, 20, 30, 40}, 7);

        Object instance = recorder.getField("last").get(null);
        assertNotNull(instance, "the forwarder must hand the quad to putBakedQuad");
        int[] colors = (int[]) instance.getClass().getField("colors").get(instance);
        int[] lights = (int[]) instance.getClass().getField("lights").get(instance);
        assertEquals(0xFFFFFFFF, colors[0], "full brightness keeps the given color");
        assertEquals(0xFF7F7F7F, colors[1], "half brightness halves the RGB channels and keeps alpha");
        assertEquals(0xFF000000, colors[2]);
        assertArrayEquals(new int[]{10, 20, 30, 40}, lights, "each vertex keeps its own light");
        assertEquals(7, instance.getClass().getField("overlay").getInt(instance));
    }

    @Test
    @DisplayName("Material accessors return the values the quad's materialInfo() holds")
    void materialAccessorsReadMaterialInfo() throws Exception {
        ClassLoader host = host();
        Class<?> forwarder = host.loadClass(LegacyBakedQuadSynthetic.INTERNAL.replace('/', '.'));
        Class<?> quadType = host.loadClass(QUAD.replace('/', '.'));
        Object quad = quadType.getConstructor().newInstance();

        assertEquals(true, forwarder.getMethod("isTinted", quadType).invoke(null, quad));
        assertEquals(3, forwarder.getMethod("tintIndex", quadType).invoke(null, quad));
        assertEquals(15, forwarder.getMethod("lightEmission", quadType).invoke(null, quad));
    }

    // ---- fixtures ------------------------------------------------------------------------

    /** Minimal 26.x stand-ins, compiled in memory so no Minecraft class reaches the test classpath. */
    private static final Map<String, String> HOST_SOURCES = Map.of(
            "net.minecraft.util.ARGB", """
                    package net.minecraft.util;
                    public final class ARGB {
                        public static int colorFromFloat(float a, float r, float g, float b) {
                            return ((int) (a * 255) << 24) | ((int) (r * 255) << 16)
                                    | ((int) (g * 255) << 8) | (int) (b * 255);
                        }
                    }""",
            "com.mojang.blaze3d.vertex.QuadInstance", """
                    package com.mojang.blaze3d.vertex;
                    public class QuadInstance {
                        public int[] colors = new int[4];
                        public int[] lights = new int[4];
                        public int overlay;
                        public void setColor(int vertex, int color) { colors[vertex] = color; }
                        public void setColor(int color) { java.util.Arrays.fill(colors, color); }
                        public void setLightCoords(int vertex, int light) { lights[vertex] = light; }
                        public void setLightCoords(int light) { java.util.Arrays.fill(lights, light); }
                        public void setOverlayCoords(int overlay) { this.overlay = overlay; }
                    }""",
            "net.minecraft.client.renderer.texture.TextureAtlasSprite", """
                    package net.minecraft.client.renderer.texture;
                    public class TextureAtlasSprite {}""",
            "com.mojang.blaze3d.vertex.PoseStack", """
                    package com.mojang.blaze3d.vertex;
                    public class PoseStack { public static class Pose {} }""",
            "com.mojang.blaze3d.vertex.VertexConsumer", """
                    package com.mojang.blaze3d.vertex;
                    public interface VertexConsumer {
                        default void putBakedQuad(PoseStack.Pose pose,
                                net.minecraft.client.resources.model.geometry.BakedQuad quad, QuadInstance instance) {
                            RecordingConsumer.last = instance;
                        }
                    }""",
            "com.mojang.blaze3d.vertex.RecordingConsumer", """
                    package com.mojang.blaze3d.vertex;
                    public class RecordingConsumer implements VertexConsumer {
                        public static QuadInstance last;
                    }""",
            "net.minecraft.client.resources.model.geometry.BakedQuad", """
                    package net.minecraft.client.resources.model.geometry;
                    public class BakedQuad {
                        public MaterialInfo materialInfo() { return new MaterialInfo(); }
                        public static class MaterialInfo {
                            public boolean isTinted() { return true; }
                            public int tintIndex() { return 3; }
                            public int lightEmission() { return 15; }
                            public net.minecraft.client.renderer.texture.TextureAtlasSprite sprite() {
                                return null;
                            }
                        }
                    }""");

    private static ClassLoader host() throws Exception {
        Map<String, byte[]> classes = compile(HOST_SOURCES);
        classes.put(LegacyBakedQuadSynthetic.INTERNAL.replace('/', '.'), LegacyBakedQuadSynthetic.generate());
        return new ClassLoader(LegacyBakedQuadSyntheticTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the build runs on a JDK, which provides javac");
        Map<String, ByteArrayOutputStream> output = new HashMap<>();
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, code) -> units.add(new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        }));
        var files = new javax.tools.ForwardingJavaFileManager<>(compiler.getStandardFileManager(null, null, null)) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className,
                    JavaFileObject.Kind kind, javax.tools.FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className + ".class"), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return output.computeIfAbsent(className, k -> new ByteArrayOutputStream());
                    }
                };
            }
        };
        assertTrue(compiler.getTask(null, files, null, List.of("--release", "17"), null, units).call(),
                "the 26.x stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        output.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
        return classes;
    }

    private List<AbstractInsnNode> transformBody(String methodDesc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "test/QuadCaller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", methodDesc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(12, 4);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/QuadCaller");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("call")) insns.addAll(List.of(m.instructions.toArray()));
        }
        return insns;
    }

    private static MethodInsnNode firstCall(List<AbstractInsnNode> insns, String owner, String name) {
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return call;
            }
        }
        return null;
    }
}
