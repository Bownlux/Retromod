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
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyModelSubmitBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiny Dragons Reforged, a 1.21.11 Fabric mod, on 26.3 (#336): its dragon's armor and color layers
 * call {@code submitModel} with a {@code TextureAtlasSprite} and a breaking overlay. 26.3 takes a
 * {@code UvMapping} and draws the overlay through {@code submitCrumblingOverlay}, so the layers
 * failed with {@code NoSuchMethodError} on their first frame.
 *
 * <p>The transformed layer runs against a recording collector compiled from 26.3's shapes.
 */
class LegacyModelSubmitBridgeTest {

    private static final String LAYER = "test/mod/DragonLayer";
    private static final String CLIENT = "net/minecraft/client/";
    private static final String MODEL = CLIENT + "model/Model";
    private static final String PART = CLIENT + "model/geom/ModelPart";
    private static final String POSE = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String RENDER_TYPE = CLIENT + "renderer/rendertype/RenderType";
    private static final String SPRITE = CLIENT + "renderer/texture/TextureAtlasSprite";
    private static final String CRUMBLING = CLIENT + "renderer/feature/ModelFeatureRenderer$CrumblingOverlay";

    private RetromodTransformer transformer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an entity layer's sprite call reaches 26.3's UvMapping form and draws no overlay")
    void spriteFormWithoutOverlay() throws Exception {
        assertEquals(List.of("submitModel(uv=sprite, tint=-1, outline=7)"), run("modelWithSprite", false));
    }

    @Test
    @DisplayName("a breaking overlay is drawn through submitCrumblingOverlay, as 26.3's renderers do")
    void breakingOverlayIsDrawnSeparately() throws Exception {
        assertEquals(List.of("submitModel(uv=sprite, tint=-1, outline=7)", "submitCrumblingOverlay(model, tint=-1)"),
                run("modelWithSprite", true));
        assertEquals(List.of("submitModel(plain, tint=5)", "submitCrumblingOverlay(model, tint=5)"),
                run("modelPlain", true));
    }

    @Test
    @DisplayName("model parts keep their tint and outline when the overlay is taken out of the middle")
    void modelPartsKeepTintAndOutline() throws Exception {
        assertEquals(List.of("submitModelPart(uv=sprite, tint=4, outline=9)", "submitCrumblingOverlay(part, tint=4)"),
                run("partWithOutline", true));
        assertEquals(List.of("submitModelPart(uv=sprite, tint=4)"), run("partWithTint", false));
    }

    @Test
    @DisplayName("a host that still takes a sprite and an overlay keeps the mod's calls")
    void gatedOnTheHostShape() {
        assertTrue(hostTakesUvMapping(collector(false)), "26.3 takes a UvMapping");
        assertFalse(hostTakesUvMapping(collector(true)), "26.2 still has the sprite form");
    }

    @Test
    @DisplayName("the generated helper passes the stack verifier")
    void helperVerifies() throws Exception {
        for (byte[] helper : List.of(generateHelper(), generateSpriteIdHelper())) {
            ClassNode node = new ClassNode();
            new ClassReader(helper).accept(node, 0);
            for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    @Test
    @DisplayName("only a layer that calls the sprite id form carries the helper that names SpriteId")
    void spriteIdFormHasItsOwnHelper() {
        byte[] transformed = transformer.transformClass(layer(), LAYER);
        String names = new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(names.contains(HELPER), "the sprite and part forms use the shared helper");
        assertFalse(names.contains(SPRITE_ID_HELPER), "this layer never calls the sprite id form");
        assertFalse(new String(generateHelper(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("SpriteId"));
    }

    private static ClassNode collector(boolean oldForms) {
        ClassNode node = new ClassNode();
        node.name = ORDERED;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "submitModel", oldForms ? MODEL_SPRITE : NEW_MODEL_UV, null, null));
        if (!oldForms) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "submitCrumblingOverlay", CRUMBLE_MODEL, null, null));
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "submitCrumblingOverlay", CRUMBLE_PART, null, null));
        }
        return node;
    }

    /** Runs one layer method against the recording collector and returns what it recorded. */
    @SuppressWarnings("unchecked")
    private List<String> run(String method, boolean withOverlay) throws Exception {
        ClassLoader host = hostWith(transformer.transformClass(layer(), LAYER));
        Object collector = host.loadClass("test.host.Recorder").getConstructor().newInstance();
        Object overlay = withOverlay ? host.loadClass(CRUMBLING.replace('/', '.')).getConstructor().newInstance() : null;
        host.loadClass(LAYER.replace('/', '.'))
                .getMethod(method, host.loadClass(ORDERED.replace('/', '.')), Object.class)
                .invoke(null, collector, overlay);
        return (List<String>) collector.getClass().getField("calls").get(collector);
    }

    /**
     * A 26.2 entity layer:
     * <pre>
     * static void modelWithSprite(OrderedSubmitNodeCollector c, Object overlay) {
     *     c.submitModel(new Model(), null, new PoseStack(), new RenderType(), 15, 0, -1, new TextureAtlasSprite(), 7, overlay); }
     * static void modelPlain(OrderedSubmitNodeCollector c, Object overlay) {
     *     c.submitModel(new Model(), null, new PoseStack(), new RenderType(), 15, 0, 5, overlay); }
     * static void partWithTint(OrderedSubmitNodeCollector c, Object overlay) {
     *     c.submitModelPart(new ModelPart(), new PoseStack(), new RenderType(), 15, 0, new TextureAtlasSprite(), 4, overlay); }
     * static void partWithOutline(OrderedSubmitNodeCollector c, Object overlay) {
     *     c.submitModelPart(new ModelPart(), new PoseStack(), new RenderType(), 15, 0, new TextureAtlasSprite(), 4, overlay, 9); }
     * </pre>
     */
    private static byte[] layer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LAYER, null, "java/lang/Object", null);

        MethodVisitor mv = begin(cw, "modelWithSprite");
        pushModelArgs(mv);
        push(mv, -1);
        create(mv, SPRITE);
        push(mv, 7);
        pushOverlay(mv);
        callOrdered(mv, "submitModel", MODEL_SPRITE);
        end(mv);

        mv = begin(cw, "modelPlain");
        pushModelArgs(mv);
        push(mv, 5);
        pushOverlay(mv);
        callOrdered(mv, "submitModel", MODEL_PLAIN);
        end(mv);

        for (boolean outline : new boolean[] {false, true}) {
            mv = begin(cw, outline ? "partWithOutline" : "partWithTint");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            create(mv, PART);
            create(mv, POSE);
            create(mv, RENDER_TYPE);
            push(mv, 15);
            push(mv, 0);
            create(mv, SPRITE);
            push(mv, 4);
            pushOverlay(mv);
            if (outline) push(mv, 9);
            callOrdered(mv, "submitModelPart", outline ? PART_OUTLINE : PART_TINT);
            end(mv);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor begin(ClassWriter cw, String name) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "(L" + ORDERED + ";Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Pushes the collector, a model, a null state, a pose, a render type, light 15 and overlay 0. */
    private static void pushModelArgs(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        create(mv, MODEL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        create(mv, POSE);
        create(mv, RENDER_TYPE);
        push(mv, 15);
        push(mv, 0);
    }

    private static void pushOverlay(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CRUMBLING);
    }

    private static void create(MethodVisitor mv, String type) {
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
    }

    private static void push(MethodVisitor mv, int value) {
        mv.visitLdcInsn(value);
    }

    private static void callOrdered(MethodVisitor mv, String name, String desc) {
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ORDERED, name, desc, true);
    }

    private ClassLoader hostWith(byte[] layerClass) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(compile263StandIns());
        classes.put(LAYER.replace('/', '.'), layerClass);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** The 26.3 collector shape, checked against the 26.3 client jar with javap, and a recorder. */
    private Map<String, byte[]> compile263StandIns() throws Exception {
        Map<String, String> sources = new HashMap<>();
        String renderer = "net/minecraft/client/renderer/";
        sources.put("net/minecraft/client/model/Model.java",
                "package net.minecraft.client.model; public class Model { public Model() {} }");
        sources.put("net/minecraft/client/model/geom/ModelPart.java",
                "package net.minecraft.client.model.geom; public class ModelPart { public ModelPart() {} }");
        sources.put("com/mojang/blaze3d/vertex/PoseStack.java",
                "package com.mojang.blaze3d.vertex; public class PoseStack { public PoseStack() {} }");
        sources.put(renderer + "rendertype/RenderType.java",
                "package net.minecraft.client.renderer.rendertype; public class RenderType { public RenderType() {} }");
        sources.put(renderer + "texture/UvMapping.java",
                "package net.minecraft.client.renderer.texture; public interface UvMapping {}");
        sources.put(renderer + "texture/TextureAtlasSprite.java", "package net.minecraft.client.renderer.texture;"
                + " public class TextureAtlasSprite implements UvMapping { public TextureAtlasSprite() {} }");
        sources.put(renderer + "feature/ModelFeatureRenderer.java", "package net.minecraft.client.renderer.feature;"
                + " public class ModelFeatureRenderer { public static class CrumblingOverlay { public CrumblingOverlay() {} } }");
        sources.put(renderer + "OrderedSubmitNodeCollector.java", """
                package net.minecraft.client.renderer;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.model.Model;
                import net.minecraft.client.model.geom.ModelPart;
                import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.renderer.texture.UvMapping;
                public interface OrderedSubmitNodeCollector {
                    <S> void submitModel(Model model, S state, PoseStack pose, RenderType type, int light,
                            int overlay, int tint, UvMapping uv, int outline);
                    <S> void submitModel(Model model, S state, PoseStack pose, RenderType type, int light,
                            int overlay, int tint);
                    <S> void submitCrumblingOverlay(Model model, S state, PoseStack pose, RenderType type,
                            int light, int overlay, int tint, CrumblingOverlay crumbling);
                    void submitCrumblingOverlay(ModelPart part, PoseStack pose, RenderType type, int light,
                            int overlay, int tint, CrumblingOverlay crumbling);
                    void submitModelPart(ModelPart part, PoseStack pose, RenderType type, int light, int overlay,
                            UvMapping uv, int tint);
                    void submitModelPart(ModelPart part, PoseStack pose, RenderType type, int light, int overlay,
                            UvMapping uv, int tint, int outline);
                }""");
        sources.put("test/host/Recorder.java", """
                package test.host;
                import com.mojang.blaze3d.vertex.PoseStack;
                import java.util.ArrayList;
                import java.util.List;
                import net.minecraft.client.model.Model;
                import net.minecraft.client.model.geom.ModelPart;
                import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
                import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                import net.minecraft.client.renderer.texture.UvMapping;
                public class Recorder implements OrderedSubmitNodeCollector {
                    public final List<String> calls = new ArrayList<>();
                    public Recorder() {}
                    private static String uv(UvMapping uv) { return uv instanceof TextureAtlasSprite ? "sprite" : String.valueOf(uv); }
                    public <S> void submitModel(Model m, S s, PoseStack p, RenderType t, int l, int o, int tint, UvMapping uv, int outline) {
                        calls.add("submitModel(uv=" + uv(uv) + ", tint=" + tint + ", outline=" + outline + ")");
                    }
                    public <S> void submitModel(Model m, S s, PoseStack p, RenderType t, int l, int o, int tint) {
                        calls.add("submitModel(plain, tint=" + tint + ")");
                    }
                    public <S> void submitCrumblingOverlay(Model m, S s, PoseStack p, RenderType t, int l, int o, int tint, CrumblingOverlay c) {
                        calls.add("submitCrumblingOverlay(model, tint=" + tint + ")");
                    }
                    public void submitCrumblingOverlay(ModelPart part, PoseStack p, RenderType t, int l, int o, int tint, CrumblingOverlay c) {
                        calls.add("submitCrumblingOverlay(part, tint=" + tint + ")");
                    }
                    public void submitModelPart(ModelPart part, PoseStack p, RenderType t, int l, int o, UvMapping uv, int tint) {
                        calls.add("submitModelPart(uv=" + uv(uv) + ", tint=" + tint + ")");
                    }
                    public void submitModelPart(ModelPart part, PoseStack p, RenderType t, int l, int o, UvMapping uv, int tint, int outline) {
                        calls.add("submitModelPart(uv=" + uv(uv) + ", tint=" + tint + ", outline=" + outline + ")");
                    }
                }""");

        Path src = tempDir.resolve("src");
        Path out = tempDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "the 26.3 stand-ins must compile");

        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
