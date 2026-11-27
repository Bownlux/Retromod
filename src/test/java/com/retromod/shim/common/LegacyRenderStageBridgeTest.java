/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.NeoForgeAbstractEventListenersAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** NeoForge 1.21.1 stage listeners and dimension effects reach the 26.x shapes. */
class LegacyRenderStageBridgeTest {

    private static final String EVENT = LegacyRenderStageBridge.EVENT;
    private static final String HANDLER = "test/rs/StageHandler";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a listener on the old event is given one listener per 26.3 stage event, and its stage calls reach the helpers")
    void listenerMovesToEachStage() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ClassNode abstractEvent = new ClassNode();
        abstractEvent.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
        LegacyRenderStageBridge.registerStages(t, name -> name.equals(EVENT) ? abstractEvent : new ClassNode());

        byte[] redirected = t.transformClass(handler(), HANDLER);
        ClassNode node = node(NeoForgeAbstractEventListenersAccess.applyRenderStages(redirected, name -> null, "26.3"));

        List<String> listeners = new ArrayList<>();
        for (MethodNode method : node.methods) if (subscribes(method)) listeners.add(method.desc);
        assertTrue(listeners.contains("(L" + EVENT + "$AfterTranslucentParticles;)V"), listeners.toString());
        assertTrue(listeners.contains("(L" + EVENT + "$AfterLevel;)V"), listeners.toString());
        assertFalse(listeners.contains("(L" + EVENT + ";)V"), "the abstract listener must lose its subscription");

        List<String> refs = refs(node);
        assertTrue(refs.contains(LegacyRenderStageBridge.CALLS + ".getStage(L" + EVENT + ";)L" + LegacyRenderStageBridge.STAGE + ";"), refs.toString());
        assertTrue(refs.contains("F " + LegacyRenderStageBridge.STAGE + ".AFTER_PARTICLES"), refs.toString());
        assertTrue(refs.contains(LegacyRenderStageBridge.CALLS + ".getCamera(L" + EVENT + ";)Lnet/minecraft/client/Camera;"), refs.toString());
        assertTrue(refs.contains(LegacyRenderStageBridge.CALLS + ".getPartialTickFloat(L" + EVENT + ";)F"),
                "Forge 1.20.1's float partial tick: " + refs);
        assertTrue(refs.contains(LegacyRenderStageBridge.CALLS + ".getFrustum(L" + EVENT + ";)Lnet/minecraft/client/renderer/culling/Frustum;"),
                refs.toString());
    }

    @Test
    @DisplayName("every old stage that now lands in one 26.x event is that event's stage, so identity checks still match")
    void stagesFollowTheEventClass() throws Exception {
        Class<?> stage = Class.forName("com.retromod.legacyapi.neoforge.client.event.LegacyRenderLevelStage");
        Method of = stage.getMethod("of", Object.class);
        assertSame(stage.getField("AFTER_PARTICLES").get(null), of.invoke(null, new AfterTranslucentParticles()));
        assertSame(stage.getField("AFTER_CUTOUT_BLOCKS").get(null), of.invoke(null, new AfterOpaqueBlocks()));
        assertSame(stage.getField("AFTER_SOLID_BLOCKS").get(null), of.invoke(null, new AfterOpaqueBlocks()));
        assertSame(stage.getField("AFTER_BLOCK_ENTITIES").get(null), of.invoke(null, new AfterOpaqueFeatures()));
        assertNotSame(stage.getField("AFTER_LEVEL").get(null), of.invoke(null, new Object()), "an unknown event is no old stage");

    }

    @Test
    @DisplayName("DimensionSpecialEffects and ClientLevel.effects() go to the rebuilt class")
    void dimensionEffects() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRenderStageBridge.registerDimensionEffects(t);
        String old = LegacyRenderStageBridge.OLD_EFFECTS;

        List<String> refs = refs(node(t.transformClass(method("test/rs/Effects", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderStageBridge.CLIENT_LEVEL, "effects", "()L" + old + ";", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, old, "getCloudHeight", "()F", false);
            mv.visitInsn(Opcodes.POP);
        }), "test/rs/Effects")));

        String effects = LegacyRenderStageBridge.EFFECTS;
        assertTrue(refs.contains(effects + ".effects(L" + LegacyRenderStageBridge.CLIENT_LEVEL + ";)L" + effects + ";"), refs.toString());
        assertTrue(refs.contains(effects + ".getCloudHeight()F"), refs.toString());
    }

    @Test
    @DisplayName("dimension effects come from the dimension type's skybox and lighting and the level's cloud height attribute")
    void dimensionEffectsFromTheHost() throws Exception {
        String fakes = "com/retromod/testsupport/fakemc/dimension/";
        java.util.Map<String, String> rename = new java.util.LinkedHashMap<>();
        rename.put(fakes + "FakeSkybox", "net/minecraft/world/level/dimension/DimensionType$Skybox");
        rename.put(fakes + "FakeCardinalType", "net/minecraft/world/level/CardinalLighting$Type");
        rename.put(fakes + "FakeAttribute", "net/minecraft/world/attribute/EnvironmentAttribute");
        rename.put(fakes + "FakeAttributes", "net/minecraft/world/attribute/EnvironmentAttributes");
        rename.put(fakes + "FakeAttributeReader", "net/minecraft/world/attribute/EnvironmentAttributeReader");
        rename.put(fakes + "FakeLevelAttributes", "test/rs/LevelAttributes");
        rename.put(fakes + "FakeDimensionType", "net/minecraft/world/level/dimension/DimensionType");
        rename.put(fakes + "FakeLevel", "net/minecraft/world/level/Level");
        rename.put(fakes + "FakeClientLevel", "net/minecraft/client/multiplayer/ClientLevel");
        rename.put(fakes + "FakeDimensionClient", "net/minecraft/client/Minecraft");
        org.objectweb.asm.commons.SimpleRemapper remapper = new org.objectweb.asm.commons.SimpleRemapper(rename);
        java.util.Map<String, byte[]> classes = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> fake : rename.entrySet()) {
            try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(fake.getKey() + ".class")) {
                org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
                new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                        new org.objectweb.asm.commons.ClassRemapper(writer, remapper), 0);
                classes.put(fake.getValue().replace('/', '.'), writer.toByteArray());
            }
        }
        java.nio.file.Path compiled = java.nio.file.Path.of("target", "legacyapi-classes");
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    byte[] bytes = classes.get(name);
                    java.nio.file.Path file = compiled.resolve(name.replace('.', '/') + ".class");
                    try {
                        // The rebuilt class and anything else it names come from the compiled legacy API and its stubs.
                        if (bytes == null && (name.startsWith("com.retromod.legacyapi.") || name.startsWith("net.minecraft.") || name.startsWith("org.joml."))
                                && java.nio.file.Files.exists(file)) {
                            bytes = java.nio.file.Files.readAllBytes(file);
                        }
                    } catch (java.io.IOException unreadable) {
                        throw new ClassNotFoundException(name, unreadable);
                    }
                    return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, resolve);
                }
            }
        };
        Class<?> skybox = loader.loadClass("net.minecraft.world.level.dimension.DimensionType$Skybox");
        Class<?> lighting = loader.loadClass("net.minecraft.world.level.CardinalLighting$Type");
        Class<?> type = loader.loadClass("net.minecraft.world.level.dimension.DimensionType");
        Class<?> level = loader.loadClass("net.minecraft.client.multiplayer.ClientLevel");
        Class<?> effects = loader.loadClass(LegacyRenderStageBridge.EFFECTS.replace('/', '.'));
        java.lang.reflect.Method read = effects.getMethod("effects", level);

        Object overworld = read.invoke(null, levelOf(skybox, lighting, type, level, "OVERWORLD", "DEFAULT", 211.5F));
        assertEquals(211.5F, effects.getField("cloudLevel").getFloat(overworld), "the cloud height is the level's attribute");
        assertEquals("NORMAL", effects.getField("skyType").get(overworld).toString());
        assertTrue(effects.getField("hasGround").getBoolean(overworld));
        assertFalse(effects.getField("constantAmbientLight").getBoolean(overworld));

        Object nether = read.invoke(null, levelOf(skybox, lighting, type, level, "NONE", "NETHER", 211.5F));
        assertTrue(Float.isNaN(effects.getField("cloudLevel").getFloat(nether)), "a dimension without the normal sky has no clouds");
        assertTrue(effects.getField("constantAmbientLight").getBoolean(nether), "nether lighting is constant ambient light");
        assertTrue((Boolean) effects.getMethod("isFoggyAt", int.class, int.class).invoke(nether, 0, 0));

        Object end = read.invoke(null, levelOf(skybox, lighting, type, level, "END", "DEFAULT", null));
        assertEquals("END", effects.getField("skyType").get(end).toString());
        assertFalse(effects.getField("hasGround").getBoolean(end));
        assertTrue(effects.getField("forceBrightLightmap").getBoolean(end));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object levelOf(Class<?> skybox, Class<?> lighting, Class<?> type, Class<?> level, String sky, String light,
            Float clouds) throws Exception {
        Object dimension = type.getConstructor(skybox, lighting).newInstance(Enum.valueOf((Class) skybox, sky),
                Enum.valueOf((Class) lighting, light));
        return level.getConstructor(type, Float.class).newInstance(dimension, clouds);
    }

    @Test
    @DisplayName("the stage bridge applies only where the event is abstract and split by stage")
    void gate() {
        ClassNode abstractEvent = new ClassNode();
        abstractEvent.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
        ClassNode concrete = new ClassNode();
        concrete.access = Opcodes.ACC_PUBLIC;
        assertTrue(LegacyRenderStageBridge.stagesSplit(abstractEvent, new ClassNode()));
        assertFalse(LegacyRenderStageBridge.stagesSplit(concrete, new ClassNode()), "NeoForge 1.21.1 posts the event itself");
        assertFalse(LegacyRenderStageBridge.stagesSplit(abstractEvent, null), "1.21.11 has other stage classes");
    }

    /** Named after 26.x's stage events, which the stage lookup reads by simple name. */
    static final class AfterTranslucentParticles {}

    static final class AfterOpaqueBlocks {}

    static final class AfterOpaqueFeatures {}

    /** A NeoForge 1.21.1 listener: one handler on the whole event, filtering by stage. */
    private static byte[] handler() {
        String stage = EVENT + "$Stage";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HANDLER, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onRender", "(L" + EVENT + ";)V", null, null);
        AnnotationVisitor subscribe = mv.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true);
        subscribe.visitEnd();
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "getStage", "()L" + stage + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, stage, "AFTER_PARTICLES", "L" + stage + ";");
        org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, skip);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "getCamera", "()Lnet/minecraft/client/Camera;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "getFrustum", "()Lnet/minecraft/client/renderer/culling/Frustum;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "getPartialTick", "()F", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(skip);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] method(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean subscribes(MethodNode method) {
        if (method.visibleAnnotations == null) return false;
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;")) return true;
        }
        return false;
    }

    private static List<String> refs(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add("F " + field.owner + "." + field.name);
            }
        }
        return refs;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
