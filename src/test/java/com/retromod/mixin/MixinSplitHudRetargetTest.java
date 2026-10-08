/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 1.21.x HUD mixin ({@code @Mixin(Gui.class)} injecting into {@code render} and shadowing
 * {@code getFont}) follows the 26.1 extract rename and the 26.2 move onto {@code Hud}. This is the
 * shape of Hold My Items' debug text overlay (#281/#312).
 */
class MixinSplitHudRetargetTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String HUD = "net/minecraft/client/gui/Hud";
    private static final String GRAPHICS = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
    private static final String DELTA = "Lnet/minecraft/client/DeltaTracker;";
    private static final String HUD_DESC = "(" + GRAPHICS + DELTA + ")V";
    private static final String FONT_DESC = "()Lnet/minecraft/client/gui/Font;";
    private static final String HANDLER_DESC = "(" + GRAPHICS + DELTA + CI + ")V";
    private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";
    private static final String FOOD_DESC = "(" + GRAPHICS + PLAYER + "II)V";
    private static final String MODIFY_EXPRESSION =
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";

    @TempDir
    Path tempDir;

    @Test
    void renamesABareRenderSelectorToTheExtractMethodOn26_1() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> {
                    method(c, "extractRenderState", HUD_DESC);
                    method(c, "getFont", FONT_DESC);
                })));

        ClassNode repaired = read(repair.apply(hudMixin(c -> {})));

        assertEquals(GUI, target(repaired), "26.1 still has the HUD on Gui");
        assertEquals(List.of("extractRenderState" + HUD_DESC), selectors(method(repaired, "debugText")),
                "the bare selector must name the renamed method with its descriptor");
    }

    @Test
    void movesAHudMixinOntoHudWhenEveryMemberLeftGuiOn26_2() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> method(c, "extractRenderState", "(" + DELTA + "ZZ)V")),
                hostClass(HUD, c -> {
                    method(c, "extractRenderState", HUD_DESC);
                    method(c, "getFont", FONT_DESC);
                })));

        ClassNode repaired = read(repair.apply(hudMixin(c -> {})));

        assertEquals(HUD, target(repaired), "the HUD members moved to Hud on 26.2");
        assertEquals(List.of("extractRenderState" + HUD_DESC), selectors(method(repaired, "debugText")));
        assertTrue(method(repaired, "getFont") != null, "the getFont shadow keeps its name on Hud");
    }

    @Test
    void leavesAGuiMixinAloneWhenOneMemberStayedOnGui() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> {
                    method(c, "extractRenderState", "(" + DELTA + "ZZ)V");
                    method(c, "toastManager", "()Ljava/lang/Object;");
                }),
                hostClass(HUD, c -> {
                    method(c, "extractRenderState", HUD_DESC);
                    method(c, "getFont", FONT_DESC);
                })));
        byte[] mixin = hudMixin(c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "toastManager", "()Ljava/lang/Object;", null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));

        assertEquals(GUI, target(repaired),
                "a mixin that still needs a Gui member must not move to Hud halfway");
    }

    @Test
    void movesAMixinWhoseOtherShadowBothClassesDeclare() throws IOException {
        String minecraft = "Lnet/minecraft/client/Minecraft;";
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> {
                    method(c, "extractRenderState", "(" + DELTA + "ZZ)V");
                    c.visitField(Opcodes.ACC_PRIVATE, "minecraft", minecraft, null, null).visitEnd();
                }),
                hostClass(HUD, c -> {
                    method(c, "extractRenderState", HUD_DESC);
                    method(c, "getFont", FONT_DESC);
                    c.visitField(Opcodes.ACC_PRIVATE, "minecraft", minecraft, null, null).visitEnd();
                })));
        byte[] mixin = hudMixin(c -> {
            FieldVisitor shadow = c.visitField(Opcodes.ACC_PRIVATE, "minecraft", minecraft, null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();
        });

        assertEquals(HUD, target(read(repair.apply(mixin))),
                "a shadow both classes declare resolves on Hud too, so it must not block the move");
    }

    @Test
    void leavesAGuiMixinWithAnAccessorAlone() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> method(c, "extractRenderState", "(" + DELTA + "ZZ)V")),
                hostClass(HUD, c -> {
                    method(c, "extractRenderState", HUD_DESC);
                    method(c, "getFont", FONT_DESC);
                })));
        byte[] mixin = hudMixin(c -> {
            MethodVisitor accessor = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "getTickCount", "()I", null, null);
            accessor.visitAnnotation(ACCESSOR, true).visitEnd();
            accessor.visitEnd();
        });

        assertEquals(GUI, target(read(repair.apply(mixin))),
                "an accessor is not proven by the split retarget");
    }

    @Test
    void leavesAGuiMixinThatAddsAnInterfaceAlone() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> method(c, "extractRenderState", "(" + DELTA + "ZZ)V")),
                hostClass(HUD, c -> {
                    method(c, "extractRenderState", HUD_DESC);
                    method(c, "getFont", FONT_DESC);
                })));
        ClassNode duck = read(hudMixin(c -> {}));
        duck.interfaces.add("test/mixin/GuiDuck");
        ClassWriter writer = new ClassWriter(0);
        duck.accept(writer);

        ClassNode repaired = read(repair.apply(writer.toByteArray()));

        assertEquals(GUI, target(repaired),
                "mod code casts minecraft.gui to the interface, so the mixin must stay on Gui");
        assertEquals(List.of("render"), selectors(method(repaired, "debugText")),
                "26.2 Gui.extractRenderState takes (DeltaTracker, boolean, boolean); the HUD "
                        + "handler must not be pointed at it by name alone");
    }

    @Test
    void leavesAMixinBuiltForTheHostByteIdentical() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> method(c, "extractRenderState", "(" + DELTA + "ZZ)V")),
                hostClass(HUD, c -> {
                    method(c, "extractRenderState", HUD_DESC);
                    method(c, "getFont", FONT_DESC);
                })));
        byte[] native26_2 = mixinOn(HUD, "extractRenderState", c -> {});

        assertArrayEquals(native26_2, repair.apply(native26_2));
    }

    @Test
    void followsThePrivateHudStepRenamesOn26_1() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(hostClass(GUI, c -> {
            method(c, "extractRenderState", HUD_DESC);
            method(c, "extractFood", FOOD_DESC);
            method(c, "extractCrosshair", HUD_DESC);
        })));

        ClassNode repaired = read(repair.apply(foodMixin(GUI)));

        assertEquals(GUI, target(repaired));
        assertEquals(List.of("extractFood"), selectors(method(repaired, "maxFood"), MODIFY_EXPRESSION),
                "a MixinExtras selector on renderFood must follow the extract rename");
        assertEquals(List.of("extractCrosshair" + HUD_DESC), selectors(method(repaired, "crosshair"), INJECT));
    }

    @Test
    void movesPrivateHudStepMixinsOntoHudOn26_2() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> method(c, "extractRenderState", "(" + DELTA + "ZZ)V")),
                hostClass(HUD, c -> {
                    method(c, "extractFood", FOOD_DESC);
                    method(c, "extractCrosshair", HUD_DESC);
                })));

        ClassNode repaired = read(repair.apply(foodMixin(GUI)));

        assertEquals(HUD, target(repaired));
        assertEquals(List.of("extractFood"), selectors(method(repaired, "maxFood"), MODIFY_EXPRESSION));
        assertEquals(List.of("extractCrosshair" + HUD_DESC), selectors(method(repaired, "crosshair"), INJECT));
    }

    @Test
    void movesAnInvokeTargetOnGuiWithTheMixin() throws IOException {
        MixinHostDriftRepair repair = repairOn(List.of(
                hostClass(GUI, c -> method(c, "extractRenderState", "(" + DELTA + "ZZ)V")),
                hostClass(HUD, c -> {
                    method(c, "extractFood", FOOD_DESC);
                    method(c, "extractCrosshair", HUD_DESC);
                    method(c, "extractSlot", "(" + GRAPHICS + "II)V");
                })));
        byte[] mixin = foodMixin(GUI, "L" + GUI + ";renderSlot(" + GRAPHICS + "II)V");

        ClassNode repaired = read(repair.apply(mixin));

        assertEquals(HUD, target(repaired));
        assertEquals("L" + HUD + ";extractSlot(" + GRAPHICS + "II)V",
                atTarget(method(repaired, "crosshair")),
                "a call to a moved HUD step must be looked up on Hud under its new name");
    }

    @Test
    void leavesAnUnrelatedRenderSelectorOutsideGuiAlone() throws IOException {
        String screen = "net/minecraft/client/gui/screens/Screen";
        MixinHostDriftRepair repair = repairOn(List.of(hostClass(screen, c -> {
            method(c, "extractFood", FOOD_DESC);
            method(c, "extractCrosshair", HUD_DESC);
        })));
        byte[] mixin = foodMixin(screen);

        assertArrayEquals(mixin, repair.apply(mixin),
                "the render-to-extract rule is proven for Gui only");
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private MixinHostDriftRepair repairOn(List<byte[]> hostClasses) throws IOException {
        Path jar = Files.createTempFile(tempDir, "host", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (byte[] hostClass : hostClasses) {
                out.putNextEntry(new JarEntry(new ClassReader(hostClass).getClassName() + ".class"));
                out.write(hostClass);
                out.closeEntry();
            }
        }
        FuzzyMethodResolver host = new FuzzyMethodResolver();
        host.indexJar(jar);
        // The redirect Common_1_21_11_to_26_1_ClassMoves registers for the HUD entry point.
        return new MixinHostDriftRepair(host,
                Map.of(new RetromodTransformer.MethodKey(GUI, "render", HUD_DESC),
                        new RetromodTransformer.MethodTarget(GUI, "extractRenderState", HUD_DESC, false)),
                Map.of());
    }

    private static byte[] hostClass(String name, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(ClassWriter writer, String name, String desc) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, desc, null, null);
        method.visitEnd();
    }

    /** Hold My Items' InGameHudMixin after the remap: render at HEAD, plus the getFont shadow. */
    private static byte[] hudMixin(Consumer<ClassWriter> extra) {
        return mixinOn(GUI, "render", extra);
    }

    private static byte[] mixinOn(String target, String selector, Consumer<ClassWriter> extra) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "test/mixin/InGameHudMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();

        MethodVisitor font = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getFont", FONT_DESC, null, null);
        font.visitAnnotation(SHADOW, true).visitEnd();
        font.visitEnd();

        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE, "debugText", HANDLER_DESC,
                null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", "HEAD");
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 0);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "test/mixin/InGameHudMixin", "getFont",
                FONT_DESC, false);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(1, 4);
        handler.visitEnd();

        extra.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Minepathy's InGameHudMixin after the remap: a bare {@code renderFood} expression hook and a
     * {@code renderCrosshair} head inject, optionally redirecting a call inside the crosshair step.
     */
    private static byte[] foodMixin(String target) {
        return foodMixin(target, null);
    }

    private static byte[] foodMixin(String target, String invokeTarget) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/FoodHudMixin", null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();

        MethodVisitor food = writer.visitMethod(Opcodes.ACC_PRIVATE, "maxFood",
                "(I" + GRAPHICS + PLAYER + "II)I", null, null);
        AnnotationVisitor expression = food.visitAnnotation(MODIFY_EXPRESSION, true);
        AnnotationVisitor foodMethods = expression.visitArray("method");
        foodMethods.visit(null, "renderFood");
        foodMethods.visitEnd();
        AnnotationVisitor foodAt = expression.visitAnnotation("at", AT);
        foodAt.visit("value", "CONSTANT");
        foodAt.visitEnd();
        expression.visitEnd();
        food.visitCode();
        food.visitVarInsn(Opcodes.ILOAD, 1);
        food.visitInsn(Opcodes.IRETURN);
        food.visitMaxs(1, 6);
        food.visitEnd();

        MethodVisitor crosshair = writer.visitMethod(Opcodes.ACC_PRIVATE, "crosshair", "(" + CI + ")V",
                null, null);
        AnnotationVisitor inject = crosshair.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "renderCrosshair" + HUD_DESC);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", invokeTarget == null ? "HEAD" : "INVOKE");
        if (invokeTarget != null) at.visit("target", invokeTarget);
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        crosshair.visitCode();
        crosshair.visitInsn(Opcodes.RETURN);
        crosshair.visitMaxs(0, 2);
        crosshair.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String atTarget(MethodNode method) {
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (!INJECT.equals(annotation.desc)) continue;
            AnnotationNode at = (AnnotationNode) ((List<?>) value(annotation, "at")).get(0);
            return (String) value(at, "target");
        }
        throw new AssertionError("no @Inject on " + method.name);
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }

    private static String target(ClassNode node) {
        for (AnnotationNode annotation : node.invisibleAnnotations) {
            if (!MIXIN.equals(annotation.desc)) continue;
            List<?> types = (List<?>) value(annotation, "value");
            return ((Type) types.get(0)).getInternalName();
        }
        throw new AssertionError("no @Mixin on " + node.name);
    }

    private static List<?> selectors(MethodNode method) {
        return selectors(method, INJECT);
    }

    private static List<?> selectors(MethodNode method, String injector) {
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (injector.equals(annotation.desc)) return (List<?>) value(annotation, "method");
        }
        throw new AssertionError("no " + injector + " on " + method.name);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("no " + key + " on " + annotation.desc);
    }
}
