/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.modelhost.BrokenOldModel;
import com.retromod.shim.common.modelhost.Model;
import com.retromod.shim.common.modelhost.ModelPart;
import com.retromod.shim.common.modelhost.OldModel;
import com.retromod.shim.common.modelhost.PoseStack;
import com.retromod.shim.common.modelhost.SelfDrawingModel;
import com.retromod.shim.common.modelhost.VertexConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.20.1 entity models draw on 1.21 to 1.21.11, whose model and part draws take one ARGB int (#404). */
class LegacyModelDrawBridgeTest {

    private static final String POSE = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String MODEL = LegacyModelDrawBridge.MODEL;
    private static final String PART = LegacyModelDrawBridge.MODEL_PART;
    private static final String SELF_DRAWING = "net/minecraft/client/model/HumanoidModel";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Only a host whose model draw is abstract and takes an int turns the pass on")
    void followsTheHost() throws Exception {
        assertTrue(LegacyModelDrawBridge.hostHasAbstractColorDraw(hostNode(Model.class)), "1.21.1");
        assertFalse(LegacyModelDrawBridge.hostHasAbstractColorDraw(hostNode(SelfDrawingModel.class)),
                "1.21.2 draws the root itself");
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertTrue(LegacyModelDrawBridge.hostHasAbstractColorDraw(null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        assertFalse(LegacyModelDrawBridge.hostHasAbstractColorDraw(null));
        assertTrue(LegacyModelDrawBridge.lacksOldDraw(hostNode(ModelPart.class), "render"));
    }

    @Test
    @DisplayName("Registering on 1.21.1 sends the part's old draw to the helper")
    void registersThePartDraw() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyModelDrawBridge.register(transformer, name -> {
            try {
                return name.equals(MODEL) ? hostNode(Model.class) : name.equals(PART) ? hostNode(ModelPart.class) : null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        var target = transformer.getMethodRedirects().get(new MethodKey(PART, "render", LegacyModelDrawBridge.OLD_DRAW));
        assertNotNull(target);
        assertEquals(LegacyModelDrawBridge.HELPER, target.owner());
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyModelDrawBridge.register(transformer, name -> null);
        assertNull(transformer.getMethodRedirects().get(new MethodKey(PART, "render", LegacyModelDrawBridge.OLD_DRAW)),
                "26.x has its own rebuilt draws");
    }

    @Test
    @DisplayName("The game's int draw runs an old model's four-float draw with the color split")
    void oldModelDrawsWithTheSplitColor() throws Exception {
        ClassLoader host = host(List.of(oldModelWithoutIntDraw()));
        Object model = host.loadClass("test.mod.OldModel").getConstructor().newInstance();
        drawWithInt(host, model, 0x80FF4000);
        float[] drawn = (float[]) model.getClass().getField("drawn").get(model);
        assertArrayEquals(new float[]{1.0F, 64 / 255.0F, 0.0F, 128 / 255.0F}, drawn, 1e-6F);
    }

    @Test
    @DisplayName("Under a Minecraft base that draws itself, an old draw that cannot link falls back to it, once")
    void brokenDrawFallsBack() throws Exception {
        ClassLoader host = host(List.of(LegacyModelDrawBridge.apply(modClass(BrokenOldModel.class), true, name -> null)));
        Object model = host.loadClass("test.mod.BrokenOldModel").getConstructor().newInstance();
        drawWithInt(host, model, 0xFF00FF00);
        drawWithInt(host, model, 0xFF0000FF);
        assertEquals(0xFF0000FF, model.getClass().getField("drawnColor").get(model));
        assertEquals(1, model.getClass().getField("attempts").get(model), "the failure is remembered, not retried every frame");
    }

    @Test
    @DisplayName("A model on a mod base gets no fallback, since the base's int draw would come back to the failing draw")
    void modBaseGetsNoFallback() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(modClass(OldModel.class)).accept(node, 0);
        node.methods.removeIf(m -> m.name.equals("renderToBuffer") && m.desc.equals(LegacyModelDrawBridge.COLOR_DRAW));
        node.name = "test/mod/OnLibraryBase";
        node.superName = "test/lib/LibraryModel";
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        ClassNode bridged = new ClassNode();
        new ClassReader(LegacyModelDrawBridge.apply(writer.toByteArray(), true, name -> null)).accept(bridged, 0);
        MethodNode draw = bridged.methods.stream().filter(m -> m.desc.equals(LegacyModelDrawBridge.COLOR_DRAW)).findFirst().orElseThrow();
        assertTrue(draw.tryCatchBlocks.isEmpty());
        assertTrue(bridged.fields.stream().noneMatch(f -> f.name.equals(LegacyModelDrawBridge.FAILED_FIELD)));
    }

    @Test
    @DisplayName("An old draw call on a Minecraft model takes the int draw with the floats packed")
    void callOnHostModelTakesTheIntDraw() throws Exception {
        byte[] caller = LegacyModelDrawBridge.apply(drawCaller(SELF_DRAWING), false, name -> null);
        ClassLoader host = host(List.of(caller));
        Object model = host.loadClass(SELF_DRAWING.replace('/', '.')).getConstructor().newInstance();
        Class<?> pose = host.loadClass(POSE.replace('/', '.'));
        host.loadClass("test.mod.LayerCaller").getMethod("draw", model.getClass(), pose)
                .invoke(null, model, pose.getConstructor().newInstance());
        assertEquals(0xFFFF8000, model.getClass().getField("drawnColor").get(model));
    }

    @Test
    @DisplayName("A super call to a Minecraft parent's old draw takes the parent's int draw and does not loop")
    void superCallTakesTheParentIntDraw() throws Exception {
        byte[] addOn = LegacyModelDrawBridge.apply(addOnModel(), true, name -> null);
        ClassLoader host = host(List.of(addOn));
        Object model = host.loadClass("test.mod.AddOnModel").getConstructor().newInstance();
        drawWithInt(host, model, 0x80FF4000);
        assertEquals(0x80FF4000, model.getClass().getField("drawnColor").get(model), "the parent drew with the same color");
    }

    @Test
    @DisplayName("A call on a mod class that declares the old draw, or on one that cannot be read, stays as it is")
    void callOnOldModClassStays() throws Exception {
        byte[] onOldModel = drawCaller("test/mod/OldModel");
        ClassNode oldModel = new ClassNode();
        new ClassReader(modClass(OldModel.class)).accept(oldModel, ClassReader.SKIP_CODE);
        assertSame(onOldModel, LegacyModelDrawBridge.apply(onOldModel, false, name -> name.equals("test/mod/OldModel") ? oldModel : null));
        assertSame(onOldModel, LegacyModelDrawBridge.apply(onOldModel, false, name -> null));
    }

    @Test
    @DisplayName("Registering on 1.21.1 packs model draw calls in the class pass")
    void registeredPassPacksCalls() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyModelDrawBridge.register(transformer, name -> {
            try {
                return name.equals(MODEL) ? hostNode(Model.class) : name.equals(PART) ? hostNode(ModelPart.class) : null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        ClassNode result = new ClassNode();
        new ClassReader(transformer.transformClass(drawCaller(SELF_DRAWING), "test/mod/LayerCaller.class")).accept(result, 0);
        List<String> calls = new java.util.ArrayList<>();
        result.methods.stream().filter(m -> m.name.equals("draw")).findFirst().orElseThrow().instructions.forEach(insn -> {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) calls.add(call.name + call.desc);
        });
        assertTrue(calls.contains("argb" + LegacyModelDrawBridge.ARGB), calls.toString());
        assertTrue(calls.contains("renderToBuffer" + LegacyModelDrawBridge.COLOR_DRAW), calls.toString());
    }

    /** {@code static void draw(model, pose)}: {@code model.renderToBuffer(pose, null, 15, 0, 1, 0.5, 0, 1)} on {@code owner}. */
    private static byte[] drawCaller(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/LayerCaller", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw",
                "(L" + owner + ";L" + POSE + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitIntInsn(Opcodes.BIPUSH, 15);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.FCONST_1);
        m.visitLdcInsn(0.5F);
        m.visitInsn(Opcodes.FCONST_0);
        m.visitInsn(Opcodes.FCONST_1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "renderToBuffer", LegacyModelDrawBridge.OLD_DRAW, false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 model on HumanoidModel whose old draw only calls its parent's old draw. */
    private static byte[] addOnModel() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/AddOnModel", null, SELF_DRAWING, null);
        org.objectweb.asm.MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF_DRAWING, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        org.objectweb.asm.MethodVisitor draw = cw.visitMethod(Opcodes.ACC_PUBLIC, "renderToBuffer", LegacyModelDrawBridge.OLD_DRAW, null, null);
        draw.visitCode();
        for (int slot = 0; slot <= 2; slot++) draw.visitVarInsn(Opcodes.ALOAD, slot);
        draw.visitVarInsn(Opcodes.ILOAD, 3);
        draw.visitVarInsn(Opcodes.ILOAD, 4);
        for (int slot = 5; slot <= 8; slot++) draw.visitVarInsn(Opcodes.FLOAD, slot);
        draw.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF_DRAWING, "renderToBuffer", LegacyModelDrawBridge.OLD_DRAW, false);
        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("The part helper packs the four floats into one ARGB int")
    void partHelperPacksTheColor() throws Exception {
        ClassLoader host = host(List.of());
        Object part = host.loadClass(PART.replace('/', '.')).getConstructor().newInstance();
        Class<?> pose = host.loadClass(POSE.replace('/', '.'));
        Class<?> consumer = host.loadClass(CONSUMER.replace('/', '.'));
        host.loadClass(LegacyModelDrawBridge.HELPER.replace('/', '.'))
                .getMethod("render", part.getClass(), pose, consumer, int.class, int.class,
                        float.class, float.class, float.class, float.class)
                .invoke(null, part, pose.getConstructor().newInstance(), null, 15, 0, 1.0F, 0.5F, 0.0F, 2.0F);
        assertEquals(0xFFFF8000, part.getClass().getField("drawnColor").get(part), "alpha past 1 is clamped");
    }

    @Test
    @DisplayName("A model with its own int draw and a class without the old draw are left alone")
    void leavesOtherClassesAlone() throws Exception {
        byte[] withIntDraw = modClass(OldModel.class);
        assertSame(withIntDraw, LegacyModelDrawBridge.apply(withIntDraw, true, name -> null));
        byte[] unrelated = modClass(SelfDrawingModel.class);
        assertSame(unrelated, LegacyModelDrawBridge.apply(unrelated, true, name -> null));
    }

    private static void drawWithInt(ClassLoader host, Object model, int color) throws Exception {
        Class<?> pose = host.loadClass(POSE.replace('/', '.'));
        Class<?> consumer = host.loadClass(CONSUMER.replace('/', '.'));
        model.getClass().getMethod("renderToBuffer", pose, consumer, int.class, int.class, int.class)
                .invoke(model, pose.getConstructor().newInstance(), null, 15, 0, color);
    }

    /** OldModel as a 1.20.1 jar has it, without the int draw, then through the bridge. */
    private static byte[] oldModelWithoutIntDraw() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(modClass(OldModel.class)).accept(node, 0);
        node.methods.removeIf(m -> m.name.equals("renderToBuffer") && m.desc.equals(LegacyModelDrawBridge.COLOR_DRAW));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bridged = LegacyModelDrawBridge.apply(writer.toByteArray(), true, name -> null);
        ClassNode result = new ClassNode();
        new ClassReader(bridged).accept(result, 0);
        MethodNode added = result.methods.stream().filter(m -> m.desc.equals(LegacyModelDrawBridge.COLOR_DRAW)).findFirst().orElseThrow();
        assertEquals(Opcodes.ACC_PUBLIC, added.access);
        return bridged;
    }

    private static SimpleRemapper names() {
        Map<String, String> names = new HashMap<>();
        names.put(internal(PoseStack.class), POSE);
        names.put(internal(VertexConsumer.class), CONSUMER);
        names.put(internal(Model.class), MODEL);
        names.put(internal(SelfDrawingModel.class), SELF_DRAWING);
        names.put(internal(ModelPart.class), PART);
        names.put(internal(OldModel.class), "test/mod/OldModel");
        names.put(internal(BrokenOldModel.class), "test/mod/BrokenOldModel");
        return new SimpleRemapper(names);
    }

    /** The host stand-ins, the part helper and the given mod classes, in their own loader. */
    private static ClassLoader host(List<byte[]> modClasses) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        for (Class<?> type : List.of(PoseStack.class, VertexConsumer.class, Model.class, SelfDrawingModel.class, ModelPart.class)) {
            classes.put(names().map(internal(type)).replace('/', '.'), remap(type));
        }
        classes.put(LegacyModelDrawBridge.HELPER.replace('/', '.'), LegacyModelDrawBridge.generateHelper());
        for (byte[] mod : modClasses) classes.put(new ClassReader(mod).getClassName().replace('/', '.'), mod);
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                // The helper's warning goes through the game's SLF4J, which the test's own loader has.
                if (name.startsWith("org.slf4j.")) return LegacyModelDrawBridgeTest.class.getClassLoader().loadClass(name);
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static byte[] modClass(Class<?> type) throws Exception {
        return remap(type);
    }

    private static ClassNode hostNode(Class<?> type) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(remap(type)).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static byte[] remap(Class<?> type) throws Exception {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, names()), 0);
            return writer.toByteArray();
        }
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
