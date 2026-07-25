/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #157 (nekomasfixed) regression pack: four distinct ways an intermediary selector or name
 * survived the transform while its siblings were rewritten, each fatal or inert at runtime:
 * <ol>
 *   <li>FIELD-pattern @At target selectors ({@code Lowner;name:Ldesc;}) were parsed as method
 *       names and left unmapped;</li>
 *   <li>{@code @Slice(from/to=@At(...))} inner @At nodes were never walked;</li>
 *   <li>{@code @ModifyArgs}/{@code @ModifyConstant} were missing from the sponge injector
 *       dispatch whitelist (now a family-prefix match, mirroring the MixinExtras branch);</li>
 *   <li>invokedynamic SAM names (the lambda's functional-interface method) were not remapped,
 *       so a lambda over an intermediary-named MC interface died LambdaConversionException.</li>
 * </ol>
 */
class MixinSelectorRemapRegressionTest {

    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARGS = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String SLICE = "Lorg/spongepowered/asm/mixin/injection/Slice;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // Minimal intermediary world mirroring the nekomasfixed shapes.
        transformer.registerClassRedirect("net/minecraft/class_2246", "net/minecraft/world/level/block/Blocks");
        transformer.registerClassRedirect("net/minecraft/class_2248", "net/minecraft/world/level/block/Block");
        transformer.registerClassRedirect("net/minecraft/class_3341",
                "net/minecraft/world/level/levelgen/structure/BoundingBox");
        transformer.registerIntermediaryNameMappings(
                Map.of("method_14667", "orientBox", "method_18303", "test"),
                Map.of("field_46283", "HEAVY_CORE"));
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    private static AnnotationNode handlerAnnotation(byte[] classBytes, String annDesc) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        MethodNode m = cn.methods.stream().filter(x -> x.name.equals("handler")).findFirst().orElseThrow();
        for (List<AnnotationNode> anns : List.of(
                m.visibleAnnotations != null ? m.visibleAnnotations : List.<AnnotationNode>of(),
                m.invisibleAnnotations != null ? m.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) if (annDesc.equals(a.desc)) return a;
        }
        throw new AssertionError("annotation not found: " + annDesc);
    }

    private static Object annVal(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        return null;
    }

    /** Base mixin skeleton whose handler carries {@code annDesc} built by {@code body}. */
    private static byte[] mixinWith(String annDesc, java.util.function.Consumer<AnnotationVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/RegressionMixin", null, "java/lang/Object", null);
        AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor mav = ma.visitArray("value");
        mav.visit(null, Type.getObjectType("net/minecraft/class_2246"));
        mav.visitEnd();
        ma.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
        AnnotationVisitor av = mv.visitAnnotation(annDesc, false);
        body.accept(av);
        av.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("FIELD-pattern @At target (Lowner;name:Ldesc;) is remapped like its method siblings")
    void fieldSelectorRemapped() {
        byte[] in = mixinWith(REDIRECT, av -> {
            AnnotationVisitor arr = av.visitArray("method");
            arr.visit(null, "someHandler");
            arr.visitEnd();
            AnnotationVisitor at = av.visitAnnotation("at", AT);
            at.visit("value", "FIELD");
            at.visit("target", "Lnet/minecraft/class_2246;field_46283:Lnet/minecraft/class_2248;");
            at.visitEnd();
        });
        byte[] out = new MixinCompatibilityTransformer(transformer).transformMixinClass(in);
        AnnotationNode ann = handlerAnnotation(out, REDIRECT);
        AnnotationNode at = (AnnotationNode) annVal(ann, "at");
        assertEquals("Lnet/minecraft/world/level/block/Blocks;HEAVY_CORE:Lnet/minecraft/world/level/block/Block;",
                annVal(at, "target"), "owner, field name, and type desc must all remap");
    }

    @Test
    @DisplayName("@Slice(to=@At(FIELD,...)) inner target is walked and remapped")
    void sliceInnerAtRemapped() {
        byte[] in = mixinWith(REDIRECT, av -> {
            AnnotationVisitor arr = av.visitArray("method");
            arr.visit(null, "someHandler");
            arr.visitEnd();
            AnnotationVisitor at = av.visitAnnotation("at", AT);
            at.visit("value", "INVOKE");
            at.visit("target", "Lnet/minecraft/class_3341;method_14667()V");
            at.visitEnd();
            AnnotationVisitor slice = av.visitAnnotation("slice", SLICE);
            AnnotationVisitor to = slice.visitAnnotation("to", AT);
            to.visit("value", "FIELD");
            to.visit("target", "Lnet/minecraft/class_2246;field_46283:Lnet/minecraft/class_2248;");
            to.visitEnd();
            slice.visitEnd();
        });
        byte[] out = new MixinCompatibilityTransformer(transformer).transformMixinClass(in);
        AnnotationNode ann = handlerAnnotation(out, REDIRECT);
        AnnotationNode slice = (AnnotationNode) annVal(ann, "slice");
        AnnotationNode to = (AnnotationNode) annVal(slice, "to");
        assertEquals("Lnet/minecraft/world/level/block/Blocks;HEAVY_CORE:Lnet/minecraft/world/level/block/Block;",
                annVal(to, "target"), "the slice's inner @At must be remapped too");
    }

    @Test
    @DisplayName("@ModifyArgs dispatches through the family-prefix branch and gets its @At remapped")
    void modifyArgsDispatched() {
        byte[] in = mixinWith(MODIFY_ARGS, av -> {
            AnnotationVisitor arr = av.visitArray("method");
            arr.visit(null, "someHandler");
            arr.visitEnd();
            AnnotationVisitor at = av.visitAnnotation("at", AT);
            at.visit("value", "INVOKE");
            at.visit("target",
                    "Lnet/minecraft/class_3341;method_14667(IIIIIIIIILnet/minecraft/class_2350;)Lnet/minecraft/class_3341;");
            at.visitEnd();
        });
        byte[] out = new MixinCompatibilityTransformer(transformer).transformMixinClass(in);
        AnnotationNode ann = handlerAnnotation(out, MODIFY_ARGS);
        AnnotationNode at = (AnnotationNode) annVal(ann, "at");
        String target = (String) annVal(at, "target");
        assertTrue(target.startsWith("Lnet/minecraft/world/level/levelgen/structure/BoundingBox;orientBox("),
                "the @ModifyArgs @At owner+name must remap, got: " + target);
        assertFalse(target.contains("class_3341"), "no intermediary residue allowed: " + target);
    }

    @Test
    @DisplayName("invokedynamic SAM name is remapped (lambda over an intermediary-named interface)")
    void indySamNameRemapped() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Lambda", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                        + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("method_18303", "()Ljava/lang/Object;", bsm,
                Type.getMethodType("(Ljava/lang/Object;)Z"),
                new Handle(Opcodes.H_INVOKESTATIC, "test/Lambda", "impl", "(Ljava/lang/Object;)Z", false),
                Type.getMethodType("(Ljava/lang/Object;)Z"));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        MethodVisitor impl = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "impl",
                "(Ljava/lang/Object;)Z", null, null);
        impl.visitCode();
        impl.visitInsn(Opcodes.ICONST_0);
        impl.visitInsn(Opcodes.IRETURN);
        impl.visitMaxs(1, 1);
        impl.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/Lambda");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        String indyName = null;
        for (MethodNode m : cn.methods) {
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof InvokeDynamicInsnNode indy) indyName = indy.name;
            }
        }
        assertEquals("test", indyName,
                "the SAM name must remap so LambdaMetafactory resolves against the renamed interface");
    }
}
