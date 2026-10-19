/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.2 and 26.3 widened some parameters from JOML's concrete vector and quaternion classes to their
 * read-only interfaces and dropped the old overloads. A mod built for 26.1 opened a screen with a
 * player preview through {@code GuiGraphicsExtractor.entity} and crashed with
 * {@code NoSuchMethodError} on 26.2 and 26.3 (#349). The 26.3 armor glint render type was renamed in
 * the same pass that mod needs for armor drawn by its own layer.
 */
class JomlInterfaceWidening26xTest {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String GUI_ENTITY_STATE =
            "net/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState";
    private static final String MTH = "net/minecraft/util/Mth";
    private static final String AXIS = "com/mojang/math/Axis";
    private static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String RENDER_TYPE_GETTER =
            "()Lnet/minecraft/client/renderer/rendertype/RenderType;";

    private static final String ENTITY_STATE =
            "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;";
    private static final String RECT = "Lnet/minecraft/client/gui/navigation/ScreenRectangle;";
    private static final String OLD_POSE = "Lorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;";
    private static final String NEW_POSE = "Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Quaternionfc;";

    private static final String OLD_ENTITY = "(" + ENTITY_STATE + "F" + OLD_POSE + "IIII)V";
    private static final String NEW_ENTITY = "(" + ENTITY_STATE + "F" + NEW_POSE + "IIII)V";
    private static final String OLD_STATE = "(" + ENTITY_STATE + OLD_POSE + "IIIIF" + RECT + ")V";
    private static final String NEW_STATE = "(" + ENTITY_STATE + NEW_POSE + "IIIIF" + RECT + ")V";
    private static final String OLD_STATE_SCISSOR = "(" + ENTITY_STATE + OLD_POSE + "IIIIF" + RECT + RECT + ")V";
    private static final String NEW_STATE_SCISSOR = "(" + ENTITY_STATE + NEW_POSE + "IIIIF" + RECT + RECT + ")V";
    private static final String OLD_ROTATION =
            "(Lorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;)Lorg/joml/Quaternionf;";
    private static final String NEW_ROTATION =
            "(Lorg/joml/Vector3fc;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;)Lorg/joml/Quaternionf;";
    private static final String OLD_AXIS = "(Lorg/joml/Vector3f;)Lcom/mojang/math/Axis;";
    private static final String NEW_AXIS = "(Lorg/joml/Vector3fc;)Lcom/mojang/math/Axis;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A screen's entity preview call takes the widened 26.2 descriptor")
    void entityPreviewIsWidenedOn26_2() {
        Mc26_1To26_2CoreMoves.register(transformer);

        MethodInsnNode call = onlyCall(transform(this::callEntityPreview), GUI, "entity");

        assertEquals(INVOKEVIRTUAL, call.getOpcode(), "the receiver is still the GUI extractor");
        assertEquals(NEW_ENTITY, call.desc, "26.2 only has the Vector3fc/Quaternionfc overload");
    }

    @Test
    @DisplayName("The picture-in-picture entity state constructors take the widened descriptors")
    void guiEntityStateConstructorsAreWidened() {
        Mc26_1To26_2CoreMoves.register(transformer);

        for (String[] pair : new String[][]{{OLD_STATE, NEW_STATE}, {OLD_STATE_SCISSOR, NEW_STATE_SCISSOR}}) {
            List<MethodInsnNode> calls = transform(mv -> {
                mv.visitTypeInsn(NEW, GUI_ENTITY_STATE);
                mv.visitInsn(DUP);
                pushArguments(mv, pair[0]);
                mv.visitMethodInsn(INVOKESPECIAL, GUI_ENTITY_STATE, "<init>", pair[0], false);
                mv.visitInsn(POP);
                mv.visitInsn(RETURN);
            });
            assertEquals(pair[1], onlyCall(calls, GUI_ENTITY_STATE, "<init>").desc,
                    "the 26.1 constructor " + pair[0] + " must take its widened form");
        }
    }

    @Test
    @DisplayName("Mth.rotationAroundAxis takes the widened axis parameter")
    void rotationAroundAxisIsWidened() {
        Mc26_1To26_2CoreMoves.register(transformer);

        List<MethodInsnNode> calls = transform(mv -> {
            pushArguments(mv, OLD_ROTATION);
            mv.visitMethodInsn(INVOKESTATIC, MTH, "rotationAroundAxis", OLD_ROTATION, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        assertEquals(NEW_ROTATION, onlyCall(calls, MTH, "rotationAroundAxis").desc);
    }

    @Test
    @DisplayName("Axis.of takes Vector3fc on 26.3 and stays an interface call")
    void axisOfIsWidenedOn26_3() {
        Mc26_2To26_3CoreMoves.register(transformer);

        MethodInsnNode call = onlyCall(transform(this::callAxisOf), AXIS, "of");

        assertEquals(NEW_AXIS, call.desc);
        assertTrue(call.itf, "Axis is an interface, so its static method needs an interface reference");
    }

    @Test
    @DisplayName("A 26.1 mod moved to 26.3 gets both widenings through the whole chain")
    void fullChainReaches26_3() {
        Mc26_1To26_2CoreMoves.register(transformer);
        Mc26_2To26_3CoreMoves.register(transformer);

        List<MethodInsnNode> calls = transform(mv -> {
            callEntityPreviewBody(mv);
            callAxisOfBody(mv);
            mv.visitInsn(RETURN);
        });

        assertEquals(NEW_ENTITY, onlyCall(calls, GUI, "entity").desc);
        assertEquals(NEW_AXIS, onlyCall(calls, AXIS, "of").desc);
    }

    @Test
    @DisplayName("The armor glint render type keeps working under its 26.3 name")
    void armorGlintIsRenamedOn26_3() {
        Mc26_2To26_3CoreMoves.register(transformer);

        List<MethodInsnNode> calls = transform(mv -> {
            mv.visitMethodInsn(INVOKESTATIC, RENDER_TYPES, "armorEntityGlint", RENDER_TYPE_GETTER, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        assertEquals(RENDER_TYPE_GETTER, onlyCall(calls, RENDER_TYPES, "trimmedArmorGlint").desc);
        assertTrue(calls.stream().noneMatch(c -> c.name.equals("armorEntityGlint")),
                "26.3 has no armorEntityGlint");
    }

    @Test
    @DisplayName("Every widening and rename matches the real 26.1.2, 26.2, and 26.3 jars")
    void tableMatchesTheClientJars() throws Exception {
        Path v2612 = clientJar("26.1.2");
        Path v262 = clientJar("26.2");
        Path v263 = clientJar("26.3");
        Assumptions.assumeTrue(v2612 != null && v262 != null && v263 != null,
                "the 26.1.2, 26.2, and 26.3 client jars are present");

        String[][] widenedOn26_2 = {
                {GUI, "entity", OLD_ENTITY, NEW_ENTITY},
                {GUI_ENTITY_STATE, "<init>", OLD_STATE, NEW_STATE},
                {GUI_ENTITY_STATE, "<init>", OLD_STATE_SCISSOR, NEW_STATE_SCISSOR},
                {MTH, "rotationAroundAxis", OLD_ROTATION, NEW_ROTATION}};
        for (String[] change : widenedOn26_2) {
            String label = change[0] + "." + change[1];
            assertTrue(declares(v2612, change[0], change[1], change[2]), "26.1.2 has the old " + label);
            assertFalse(declares(v262, change[0], change[1], change[2]), "26.2 dropped the old " + label);
            assertTrue(declares(v262, change[0], change[1], change[3]), "26.2 has the widened " + label);
            assertTrue(declares(v263, change[0], change[1], change[3]), "26.3 kept the widened " + label);
        }

        assertTrue(declares(v262, AXIS, "of", OLD_AXIS), "26.2 has Axis.of(Vector3f)");
        assertFalse(declares(v263, AXIS, "of", OLD_AXIS), "26.3 dropped Axis.of(Vector3f)");
        assertTrue(declares(v263, AXIS, "of", NEW_AXIS), "26.3 has Axis.of(Vector3fc)");

        assertTrue(declares(v262, RENDER_TYPES, "armorEntityGlint", RENDER_TYPE_GETTER));
        assertFalse(declares(v263, RENDER_TYPES, "armorEntityGlint", RENDER_TYPE_GETTER));
        assertTrue(declares(v263, RENDER_TYPES, "trimmedArmorGlint", RENDER_TYPE_GETTER));
    }

    private void callEntityPreview(MethodVisitor mv) {
        callEntityPreviewBody(mv);
        mv.visitInsn(RETURN);
    }

    private void callEntityPreviewBody(MethodVisitor mv) {
        mv.visitInsn(ACONST_NULL);
        pushArguments(mv, OLD_ENTITY);
        mv.visitMethodInsn(INVOKEVIRTUAL, GUI, "entity", OLD_ENTITY, false);
    }

    private void callAxisOf(MethodVisitor mv) {
        callAxisOfBody(mv);
        mv.visitInsn(RETURN);
    }

    private void callAxisOfBody(MethodVisitor mv) {
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, AXIS, "of", OLD_AXIS, true);
        mv.visitInsn(POP);
    }

    /** Pushes a placeholder of the right kind for every parameter of {@code desc}. */
    private static void pushArguments(MethodVisitor mv, String desc) {
        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            switch (arg.getSort()) {
                case org.objectweb.asm.Type.FLOAT -> mv.visitInsn(FCONST_0);
                case org.objectweb.asm.Type.INT -> mv.visitInsn(ICONST_0);
                default -> mv.visitInsn(ACONST_NULL);
            }
        }
    }

    private List<MethodInsnNode> transform(Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ScreenPreview", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/ScreenPreview");
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static MethodInsnNode onlyCall(List<MethodInsnNode> calls, String owner, String name) {
        List<MethodInsnNode> matching = calls.stream()
                .filter(c -> c.owner.equals(owner) && c.name.equals(name)).toList();
        assertEquals(1, matching.size(), "expected one call to " + owner + "." + name + ", saw " + matching.size());
        return matching.get(0);
    }

    private static Path clientJar(String version) {
        Path jar = Path.of(System.getProperty("user.home", ""),
                "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft",
                version, "minecraft-" + version + "-client.jar");
        return Files.isRegularFile(jar) ? jar : null;
    }

    private static boolean declares(Path jar, String owner, String name, String desc) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(owner + ".class");
            if (entry == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
        }
    }
}
