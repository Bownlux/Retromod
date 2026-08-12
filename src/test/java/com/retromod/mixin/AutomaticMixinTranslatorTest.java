/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The automatic Mixin translator must prove every repair from the target JAR. This fixture uses a
 * small synthetic Minecraft class to exercise successful parameter additions and the refusal rules
 * without relying on a developer's local game installation.
 */
class AutomaticMixinTranslatorTest {

    private static final String TARGET = "net/minecraft/test/AutomaticMixinTarget";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String MODIFY_RETURN =
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
    private static final String MODIFY_EXPRESSION =
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
    private static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String LOCAL =
            "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String START_CONNECTING_OLD_ARGS =
            "Lnet/minecraft/client/gui/screens/Screen;"
            + "Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
            + "Lnet/minecraft/client/multiplayer/ServerData;Z";
    private static final String TRANSFER_STATE =
            "Lnet/minecraft/client/multiplayer/TransferState;";
    private static final String REVAMPED_VEC = "Lnet/minecraft/class_243;";
    private static final String REVAMPED_ENTITY = "Lnet/minecraft/class_1297;";
    private static final String REVAMPED_ATTACHMENTS = "Lnet/minecraft/class_9066;";

    @TempDir
    Path tempDir;

    private AutomaticMixinTranslator translator;

    @BeforeEach
    void indexSyntheticTarget() throws IOException {
        Path jar = tempDir.resolve("target.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(TARGET + ".class"));
            out.write(targetClass());
            out.closeEntry();
        }

        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(jar);
        assertTrue(resolver.isIndexed(), "the synthetic target JAR must be indexed");
        translator = new AutomaticMixinTranslator(resolver);
    }

    @Test
    @DisplayName("A unique prepended target parameter updates the @Inject selector and handler")
    void prependedParameterIsTranslated() {
        String oldSelector = "prepend(Ljava/lang/String;)V";
        String oldHandler = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";

        byte[] output = translator.translate(injectMixin(
                "PrependMixin", oldSelector, oldHandler, false));

        assertEquals("prepend(JLjava/lang/String;)V", injectSelector(output));
        assertEquals("(JLjava/lang/String;" + CALLBACK_INFO + ")V", handler(output).desc,
                "the long target parameter must be inserted before captured arguments");
    }

    @Test
    @DisplayName("A unique appended target parameter lands before CallbackInfo")
    void appendedParameterIsTranslated() {
        String oldSelector = "append(Ljava/lang/String;)V";
        String oldHandler = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";

        byte[] output = translator.translate(injectMixin(
                "AppendMixin", oldSelector, oldHandler, false));

        assertEquals("append(Ljava/lang/String;I)V", injectSelector(output));
        assertEquals("(Ljava/lang/String;I" + CALLBACK_INFO + ")V", handler(output).desc,
                "the appended target parameter must stay ahead of CallbackInfo");
    }

    @Test
    @DisplayName("A refmap-only Yarn source selector can repair its parameter-capturing handler")
    void refmapOnlySourceSelectorRepairsHandler() {
        String sourceSelector = "oldYarnAppend(Ljava/lang/String;)V";
        String oldHandler = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = injectMixin("RefmapLinkedMixin", sourceSelector, oldHandler, false);
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put("test/mixin/RefmapLinkedMixin", sourceSelector,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET, "(Ljava/lang/String;)V",
                                "(Ljava/lang/String;I)V",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                                List.of(new MixinHandlerResignature.ParamInsert(1, "I"))))
                .build();

        byte[] output = translator.translateRefmapHandlers(input, index);

        assertEquals(sourceSelector, injectSelector(output),
                "the source selector remains the refmap lookup key");
        assertEquals("(Ljava/lang/String;I" + CALLBACK_INFO + ")V", handler(output).desc,
                "the exact refmap relationship supplies the added target parameter");
        assertEquals(3, firstVar(handler(output), Opcodes.ALOAD),
                "the existing callback load follows its shifted local slot");
    }

    @Test
    @DisplayName("Multiple matching injector annotations decline a refmap-linked repair")
    void multipleRefmapMatchesAreRefused() {
        String sourceSelector = "oldYarnAppend(Ljava/lang/String;)V";
        String oldHandler = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = duplicateInjectAnnotation(injectMixin(
                "DuplicateRefmapMixin", sourceSelector, oldHandler, false));
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put("test/mixin/DuplicateRefmapMixin", sourceSelector,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET, "(Ljava/lang/String;)V",
                                "(Ljava/lang/String;I)V",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                                List.of(new MixinHandlerResignature.ParamInsert(1, "I"))))
                .build();

        byte[] output = translator.translateRefmapHandlers(input, index);

        assertArrayEquals(input, output,
                "one handler cannot receive two independently discovered layouts");
        assertEquals(oldHandler, handler(output).desc);
    }

    @Test
    @DisplayName("A @ModifyReturnValue handler keeps its value before added target arguments")
    void modifyReturnValueTargetCaptureIsTranslated() {
        String oldSelector = "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String oldHandler =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

        byte[] output = translator.translate(valueModifierMixin(
                "ModifyReturnMixin", MODIFY_RETURN, oldSelector, oldHandler));

        assertEquals("returnValue(ILjava/lang/String;)Ljava/lang/String;",
                injectorSelector(output, MODIFY_RETURN));
        assertEquals("(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                handler(output).desc,
                "the added target int must follow the intercepted return value");
        assertEquals(3, firstVar(handler(output), Opcodes.ALOAD),
                "the captured String load must move past the inserted int slot");
    }

    @Test
    @DisplayName("A grouped injector still receives its proven signature repair")
    void groupedInjectorIsNotCountedAsTwoInjectors() {
        String selector = "prepend(Ljava/lang/String;)V";
        String oldHandler = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = addGroupAnnotation(injectMixin(
                "GroupedMixin", selector, oldHandler, false));

        byte[] output = translator.translate(input);

        assertEquals("prepend(JLjava/lang/String;)V", injectSelector(output));
        assertEquals("(JLjava/lang/String;" + CALLBACK_INFO + ")V", handler(output).desc,
                "@Group is auxiliary metadata, not a second injector layout");
        assertEquals("compatibility", annotationValue(annotation(handler(output), GROUP), "name"),
                "the repair must retain the handler's injector group");
    }

    @Test
    @DisplayName("A @ModifyExpressionValue handler keeps its expression before target arguments")
    void modifyExpressionValueTargetCaptureIsTranslated() {
        String oldSelector = "expression(Ljava/lang/String;)V";
        String oldHandler = "(ZLjava/lang/String;)Z";

        byte[] output = translator.translate(valueModifierMixin(
                "ModifyExpressionMixin", MODIFY_EXPRESSION, oldSelector, oldHandler));

        assertEquals("expression(JLjava/lang/String;)V",
                injectorSelector(output, MODIFY_EXPRESSION));
        assertEquals("(ZJLjava/lang/String;)Z", handler(output).desc,
                "the added target long must follow the intercepted expression value");
        assertEquals(4, firstVar(handler(output), Opcodes.ALOAD),
                "the captured String load must move by both long slots");
    }

    @Test
    @DisplayName("A value-only MixinExtras handler needs only its target selector updated")
    void valueOnlyMixinExtrasHandlerKeepsDescriptor() {
        String oldSelector = "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String handlerDescriptor = "(Ljava/lang/String;)Ljava/lang/String;";

        byte[] output = translator.translate(valueModifierMixin(
                "ValueOnlyMixin", MODIFY_RETURN, oldSelector, handlerDescriptor));

        assertEquals("returnValue(ILjava/lang/String;)Ljava/lang/String;",
                injectorSelector(output, MODIFY_RETURN));
        assertEquals(handlerDescriptor, handler(output).desc,
                "a handler that captures no target arguments stays valid unchanged");
    }

    @Test
    @DisplayName("A partial MixinExtras target-argument capture is not inferred")
    void partialValueModifierCaptureIsRefused() {
        String selector = "returnPair(Ljava/lang/String;J)Ljava/lang/String;";
        String handlerDescriptor =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "PartialValueCaptureMixin", MODIFY_RETURN, selector, handlerDescriptor);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "omitting an old target argument makes the captured layout ambiguous");
        assertEquals(selector, injectorSelector(output, MODIFY_RETURN));
        assertEquals(handlerDescriptor, handler(output).desc);
    }

    @Test
    @DisplayName("A semantic parameter annotation blocks MixinExtras descriptor repair")
    void unsafeValueModifierParameterAnnotationIsRefused() {
        String selector = "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String handlerDescriptor =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "AnnotatedValueMixin", MODIFY_RETURN, selector, handlerDescriptor,
                Opcodes.ACC_PRIVATE, 1);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "moving a @Local parameter could change which local it captures");
        assertEquals(selector, injectorSelector(output, MODIFY_RETURN));
        assertEquals(handlerDescriptor, handler(output).desc);
    }

    @Test
    @DisplayName("A value parameter that differs from the handler return is refused")
    void valueModifierArgumentReturnMismatchIsRefused() {
        String selector = "expression(Ljava/lang/String;)V";
        String handlerDescriptor = "(ILjava/lang/String;)Z";
        byte[] input = valueModifierMixin(
                "ValueReturnMismatchMixin", MODIFY_EXPRESSION, selector, handlerDescriptor);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "the intercepted value and replacement value must have the same type");
        assertEquals(selector, injectorSelector(output, MODIFY_EXPRESSION));
        assertEquals(handlerDescriptor, handler(output).desc);
    }

    @Test
    @DisplayName("A @ModifyReturnValue type that differs from its target return is refused")
    void modifyReturnValueTargetReturnMismatchIsRefused() {
        String selector = "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String handlerDescriptor = "(ILjava/lang/String;)I";
        byte[] input = valueModifierMixin(
                "TargetReturnMismatchMixin", MODIFY_RETURN, selector, handlerDescriptor);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "ModifyReturnValue must consume the enclosing target's return type");
        assertEquals(selector, injectorSelector(output, MODIFY_RETURN));
        assertEquals(handlerDescriptor, handler(output).desc);
    }

    @Test
    @DisplayName("A static value modifier may target an instance method")
    void staticValueModifierCanTargetInstanceMethod() {
        String selector = "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String handlerDescriptor =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "StaticValueMixin", MODIFY_RETURN, selector, handlerDescriptor,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, -1);

        byte[] output = translator.translate(input);

        assertEquals("returnValue(ILjava/lang/String;)Ljava/lang/String;",
                injectorSelector(output, MODIFY_RETURN));
        assertEquals("(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                handler(output).desc);
        assertEquals(2, firstVar(handler(output), Opcodes.ALOAD),
                "the captured String moves while a static handler has no receiver slot");
    }

    @Test
    @DisplayName("A nonstatic value modifier cannot target a static method")
    void nonstaticValueModifierCannotTargetStaticMethod() {
        String selector = "returnValueStatic(Ljava/lang/String;)Ljava/lang/String;";
        String handlerDescriptor =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "NonstaticValueMixin", MODIFY_RETURN, selector, handlerDescriptor);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "MixinExtras cannot load a receiver for a static target method");
        assertEquals(selector, injectorSelector(output, MODIFY_RETURN));
        assertEquals(handlerDescriptor, handler(output).desc);
    }

    @Test
    @DisplayName("A shared handler is not retyped when an unchanged target rejects its layout")
    void incompatibleUnchangedMultiTargetRefusesHandlerRepair() {
        List<String> selectors = List.of(
                "returnValueUnchanged(Ljava/lang/String;)Ljava/lang/String;",
                "returnValue(Ljava/lang/String;)Ljava/lang/String;");
        String handlerDescriptor =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "IncompatibleMultiTargetMixin", MODIFY_RETURN, selectors, handlerDescriptor);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "the repaired two-argument capture would no longer match the unchanged target");
        assertEquals(selectors, injectorSelectors(output, MODIFY_RETURN));
        assertEquals(handlerDescriptor, handler(output).desc);
    }

    @Test
    @DisplayName("Compatible changed targets share one atomic handler layout")
    void compatibleChangedMultiTargetsShareHandlerRepair() {
        List<String> selectors = List.of(
                "returnValue(Ljava/lang/String;)Ljava/lang/String;",
                "returnValueAlsoChanged(Ljava/lang/String;)Ljava/lang/String;");
        String handlerDescriptor =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

        byte[] output = translator.translate(valueModifierMixin(
                "CompatibleMultiTargetMixin", MODIFY_RETURN, selectors, handlerDescriptor));

        assertEquals(List.of(
                "returnValue(ILjava/lang/String;)Ljava/lang/String;",
                "returnValueAlsoChanged(ILjava/lang/String;)Ljava/lang/String;"),
                injectorSelectors(output, MODIFY_RETURN));
        assertEquals("(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                handler(output).desc);
    }

    @Test
    @DisplayName("Revamped Phantoms full @ModifyReturnValue capture gains a leading long")
    void revampedPhantomsFullModifyReturnValueShapeIsTranslated() {
        String oldTargetArgs = REVAMPED_ENTITY + REVAMPED_ENTITY + REVAMPED_ATTACHMENTS;
        String oldSelector = "method_55665(" + oldTargetArgs + ")" + REVAMPED_VEC;
        String oldHandler = "(" + REVAMPED_VEC + oldTargetArgs + ")" + REVAMPED_VEC;

        byte[] output = translator.translate(valueModifierMixin(
                "RevampedPhantomsEntityMixin", MODIFY_RETURN, oldSelector, oldHandler));

        assertEquals("method_55665(J" + oldTargetArgs + ")" + REVAMPED_VEC,
                injectorSelector(output, MODIFY_RETURN));
        assertEquals("(" + REVAMPED_VEC + "J" + oldTargetArgs + ")" + REVAMPED_VEC,
                handler(output).desc,
                "the complete target capture must remain after the intercepted Vec3 value");
        assertEquals(4, firstVar(handler(output), Opcodes.ALOAD),
                "the first captured Entity must move past both slots of the added long");
    }

    @Test
    @DisplayName("A refmap-linked @ModifyReturnValue handler gains target arguments")
    void refmapModifyReturnValueCaptureIsTranslated() {
        String sourceSelector = "oldYarnReturn(Ljava/lang/String;)Ljava/lang/String;";
        String oldHandler =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "RefmapModifyReturnMixin", MODIFY_RETURN, sourceSelector, oldHandler);
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put("test/mixin/RefmapModifyReturnMixin", sourceSelector,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET, "(Ljava/lang/String;)Ljava/lang/String;",
                                "(ILjava/lang/String;)Ljava/lang/String;",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                                List.of(new MixinHandlerResignature.ParamInsert(0, "I"))))
                .build();

        byte[] output = translator.translateRefmapHandlers(input, index);

        assertEquals(sourceSelector, injectorSelector(output, MODIFY_RETURN),
                "the annotation must retain its refmap lookup key");
        assertEquals("(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                handler(output).desc);
        assertEquals(3, firstVar(handler(output), Opcodes.ALOAD));
    }

    @Test
    @DisplayName("A refmap-linked @ModifyExpressionValue handler gains target arguments")
    void refmapModifyExpressionValueCaptureIsTranslated() {
        String sourceSelector = "oldYarnExpression(Ljava/lang/String;)V";
        String oldHandler = "(ZLjava/lang/String;)Z";
        byte[] input = valueModifierMixin(
                "RefmapModifyExpressionMixin", MODIFY_EXPRESSION, sourceSelector, oldHandler);
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put("test/mixin/RefmapModifyExpressionMixin", sourceSelector,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET, "(Ljava/lang/String;)V",
                                "(JLjava/lang/String;)V",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                                List.of(new MixinHandlerResignature.ParamInsert(0, "J"))))
                .build();

        byte[] output = translator.translateRefmapHandlers(input, index);

        assertEquals(sourceSelector, injectorSelector(output, MODIFY_EXPRESSION),
                "the annotation must retain its refmap lookup key");
        assertEquals("(ZJLjava/lang/String;)Z", handler(output).desc);
        assertEquals(4, firstVar(handler(output), Opcodes.ALOAD));
    }

    @Test
    @DisplayName("A refmap handler is not retyped with an unproven shared selector")
    void refmapSharedSelectorWithoutLayoutProofIsRefused() {
        String changedSource = "oldYarnReturn(Ljava/lang/String;)Ljava/lang/String;";
        String unchangedSource = "oldYarnUnchanged(Ljava/lang/String;)Ljava/lang/String;";
        String oldHandler =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "RefmapSharedMixin", MODIFY_RETURN,
                List.of(changedSource, unchangedSource), oldHandler);
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put("test/mixin/RefmapSharedMixin", changedSource,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET, "(Ljava/lang/String;)Ljava/lang/String;",
                                "(ILjava/lang/String;)Ljava/lang/String;",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                                List.of(new MixinHandlerResignature.ParamInsert(0, "I"))))
                .build();

        byte[] output = translator.translateRefmapHandlers(input, index);

        assertArrayEquals(input, output,
                "every refmap source selector needs an exact compatible target relationship");
        assertEquals(List.of(changedSource, unchangedSource),
                injectorSelectors(output, MODIFY_RETURN));
        assertEquals(oldHandler, handler(output).desc);
    }

    @Test
    @DisplayName("Direct and refmap selectors can prove one shared MixinExtras layout")
    void mixedDirectAndRefmapSelectorsShareHandlerRepair() {
        String directSelector =
                "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String sourceSelector =
                "oldYarnReturn(Ljava/lang/String;)Ljava/lang/String;";
        String oldHandler =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "MixedDirectRefmapMixin", MODIFY_RETURN,
                List.of(directSelector, sourceSelector), oldHandler);
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put("test/mixin/MixedDirectRefmapMixin", sourceSelector,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET, "(Ljava/lang/String;)Ljava/lang/String;",
                                "(ILjava/lang/String;)Ljava/lang/String;",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                                List.of(new MixinHandlerResignature.ParamInsert(0, "I"))))
                .build();

        byte[] output = translator.translate(input, index);

        assertEquals(List.of(
                "returnValue(ILjava/lang/String;)Ljava/lang/String;",
                sourceSelector), injectorSelectors(output, MODIFY_RETURN));
        assertEquals("(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                handler(output).desc);
        assertEquals(3, firstVar(handler(output), Opcodes.ALOAD));
    }

    @Test
    @DisplayName("Mixed selector layouts are refused before changing a shared handler")
    void mixedDirectAndRefmapLayoutMismatchIsRefused() {
        String directSelector =
                "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String sourceSelector =
                "oldYarnReturn(Ljava/lang/String;)Ljava/lang/String;";
        String oldHandler =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "MixedLayoutMismatchMixin", MODIFY_RETURN,
                List.of(directSelector, sourceSelector), oldHandler);
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put("test/mixin/MixedLayoutMismatchMixin", sourceSelector,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET, "(Ljava/lang/String;)Ljava/lang/String;",
                                "(JLjava/lang/String;)Ljava/lang/String;",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                                List.of(new MixinHandlerResignature.ParamInsert(0, "J"))))
                .build();

        byte[] output = translator.translate(input, index);

        assertArrayEquals(input, output,
                "different proven layouts must leave the class unchanged");
        assertEquals(List.of(directSelector, sourceSelector),
                injectorSelectors(output, MODIFY_RETURN));
        assertEquals(oldHandler, handler(output).desc);
    }

    @Test
    @DisplayName("Mixed selectors need evidence for every shared target")
    void mixedDirectSelectorWithoutRefmapEvidenceIsRefused() {
        String directSelector =
                "returnValue(Ljava/lang/String;)Ljava/lang/String;";
        String sourceSelector =
                "oldYarnReturn(Ljava/lang/String;)Ljava/lang/String;";
        String oldHandler =
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        byte[] input = valueModifierMixin(
                "MixedMissingEvidenceMixin", MODIFY_RETURN,
                List.of(directSelector, sourceSelector), oldHandler);

        byte[] output = translator.translate(
                input, MixinRefmapRepairIndex.empty());

        assertArrayEquals(input, output,
                "an unproven selector must leave the class unchanged");
        assertEquals(List.of(directSelector, sourceSelector),
                injectorSelectors(output, MODIFY_RETURN));
        assertEquals(oldHandler, handler(output).desc);
    }

    @Test
    @DisplayName("An exact target completes an older prefix-capturing @Inject handler")
    void exactTargetCompletesTrailingParameters() {
        String selector = "playDelayed(Ljava/lang/String;I)V";
        String oldHandler = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";

        byte[] output = translator.translate(injectMixin(
                "PrefixCaptureMixin", selector, oldHandler, false));

        assertEquals(selector, injectSelector(output),
                "an exact host selector must not be changed");
        assertEquals("(Ljava/lang/String;I" + CALLBACK_INFO + ")V", handler(output).desc,
                "the missing exact-target suffix must land before CallbackInfo");
        assertEquals(3, firstVar(handler(output), Opcodes.ALOAD),
                "the callback load must move past the inserted int slot");
    }

    @Test
    @DisplayName("Dynamic FPS multi-target handler follows its exact delayed-play target")
    void dynamicFpsMultiTargetHandlerGainsDelayParameter() {
        String oldHandler = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] output = translator.translate(injectMixin(
                "DynamicFpsSoundMixin",
                List.of("play(Ljava/lang/String;)V", "playDelayed(Ljava/lang/String;I)V"),
                oldHandler, false, true));

        assertEquals("(Ljava/lang/String;I" + CALLBACK_INFO + ")V", handler(output).desc,
                "the stale optional play selector must not hide the exact playDelayed repair");
        assertEquals(3, firstVar(handler(output), Opcodes.ALOAD),
                "the original callback body must follow its shifted local slot");
        assertEquals(List.of(
                "play(Ljava/lang/String;)V", "playDelayed(Ljava/lang/String;I)V"),
                injectSelectors(output),
                "the repair changes only the handler required by the exact target");
    }

    @Test
    @DisplayName("No Chat Reports bare startConnecting selector gains the appended TransferState")
    void bareStartConnectingHandlerGainsAppendedParameter() {
        String oldHandler = "(" + START_CONNECTING_OLD_ARGS + CALLBACK_INFO + ")V";

        byte[] output = translator.translate(injectMixin(
                "NoChatReportsMixin", "startConnecting", oldHandler, false));

        assertEquals("startConnecting", injectSelector(output),
                "a bare selector remains bare because the target name did not change");
        assertEquals("(" + START_CONNECTING_OLD_ARGS + TRANSFER_STATE + CALLBACK_INFO + ")V",
                handler(output).desc,
                "the complete old target arguments gain TransferState before CallbackInfo");
    }

    @Test
    @DisplayName("A zero-capture HEAD inject can follow a unique same-name descriptor refactor")
    void zeroCaptureHeadReplacesDescriptorWithoutRetypingHandler() {
        String oldHandler = "(" + CALLBACK_INFO + ")V";

        byte[] output = translator.translate(injectMixin(
                "ZeroCaptureMixin", "refactored(Ljava/lang/String;)V", oldHandler, false));

        assertEquals("refactored(IJ)V", injectSelector(output));
        assertEquals(oldHandler, handler(output).desc,
                "a zero-capture callback does not inherit target parameters");
    }

    @Test
    @DisplayName("Two compatible overloads make parameter insertion ambiguous and are refused")
    void ambiguousOverloadsAreRefused() {
        String selector = "ambiguous(Ljava/lang/String;)V";
        String handlerDesc = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = injectMixin("AmbiguousMixin", selector, handlerDesc, false);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output, "an ambiguous target must remain byte-identical");
        assertEquals(selector, injectSelector(output));
        assertEquals(handlerDesc, handler(output).desc);
    }

    @Test
    @DisplayName("A semantic parameter annotation prevents automatic handler re-signaturing")
    void semanticParameterAnnotationIsRefused() {
        String selector = "annotated(Ljava/lang/String;)V";
        String handlerDesc = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = injectMixin("AnnotatedMixin", selector, handlerDesc, true);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "shifting a @Local parameter would change its meaning, so the repair must decline");
        assertEquals(selector, injectSelector(output));
        assertEquals(handlerDesc, handler(output).desc);
    }

    @Test
    @DisplayName("An injector with remap=false is never changed automatically")
    void remapFalseIsRefused() {
        String selector = "prepend(Ljava/lang/String;)V";
        String handlerDesc = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = injectMixin(
                "RemapDisabledMixin", selector, handlerDesc, false, false);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output, "remap=false must preserve the mixin byte-for-byte");
        assertEquals(selector, injectSelector(output));
        assertEquals(handlerDesc, handler(output).desc);
    }

    @Test
    @DisplayName("A class-level @Mixin(remap=false) disables every automatic repair")
    void classLevelRemapFalseIsRefused() {
        String selector = "prepend(Ljava/lang/String;)V";
        String handlerDesc = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = injectMixinWithClassRemapDisabled(
                "ClassRemapDisabledMixin", selector, handlerDesc);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "class-level remap=false must preserve the mixin byte-for-byte");
        assertEquals(selector, injectSelector(output));
        assertEquals(handlerDesc, handler(output).desc);
    }

    @Test
    @DisplayName("A unique semantic @Invoker rename is taken from the indexed target")
    void uniqueInvokerRenameIsTranslated() {
        byte[] output = translator.translate(invokerMixin());
        AnnotationNode invoker = annotation(handler(output), INVOKER);

        assertEquals("getHoveredSlot", annotationValue(invoker, "value"),
                "findSlot and getHoveredSlot share one meaningful target token");
    }

    @Test
    @DisplayName("An owner-qualified @Redirect target and handler gain a call parameter")
    void redirectCallTargetAndHandlerAreTranslated() {
        String oldTarget = "L" + TARGET + ";redirectCall(Ljava/lang/String;)I";

        byte[] output = translator.translate(redirectMixin(oldTarget));
        MethodNode handler = handler(output);

        assertEquals("L" + TARGET + ";redirectCall(ILjava/lang/String;)I",
                atTarget(annotation(handler, REDIRECT)));
        assertEquals("(L" + TARGET + ";ILjava/lang/String;)I", handler.desc,
                "the new call parameter must follow the virtual receiver");
        assertEquals(2, firstVar(handler, Opcodes.ALOAD),
                "the original String argument must move past the inserted int slot");
    }

    @Test
    @DisplayName("An @At target with remap=false is never changed automatically")
    void atRemapFalseIsRefused() {
        String oldTarget = "L" + TARGET + ";redirectCall(Ljava/lang/String;)I";
        byte[] input = redirectMixin(oldTarget, false);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "@At remap=false must preserve the mixin byte-for-byte");
        MethodNode handler = handler(output);
        assertEquals(oldTarget, atTarget(annotation(handler, REDIRECT)));
        assertEquals("(L" + TARGET + ";Ljava/lang/String;)I", handler.desc);
    }

    @Test
    @DisplayName("An @Overwrite gains a target parameter that its body does not use")
    void overwriteGainsUnusedParameter() {
        byte[] output = translator.translate(overwriteMixin());
        MethodNode overwrite = method(output, "overwriteAdded");

        assertEquals("(ILjava/lang/String;)Ljava/lang/String;", overwrite.desc);
        assertEquals(2, firstVar(overwrite, Opcodes.ALOAD),
                "the existing String load must move while the inserted int stays unused");
    }

    @Test
    @DisplayName("A parameter substitution is not treated as a safe insertion")
    void parameterSubstitutionIsRefused() {
        String selector = "substituted(Ljava/lang/String;)V";
        String handlerDesc = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = injectMixin("SubstitutionMixin", selector, handlerDesc, false);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "changing String to int requires a semantic handler rewrite");
        assertEquals(selector, injectSelector(output));
        assertEquals(handlerDesc, handler(output).desc);
    }

    @Test
    @DisplayName("A target return type change is not translated automatically")
    void returnTypeChangeIsRefused() {
        String selector = "changedReturn(Ljava/lang/String;)V";
        String handlerDesc = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
        byte[] input = injectMixin("ReturnChangeMixin", selector, handlerDesc, false);

        byte[] output = translator.translate(input);

        assertArrayEquals(input, output,
                "a return type change requires an injector-specific semantic repair");
        assertEquals(selector, injectSelector(output));
        assertEquals(handlerDesc, handler(output).desc);
    }

    private static byte[] targetClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                TARGET, null, "java/lang/Object", null);
        abstractMethod(writer, "prepend", "(JLjava/lang/String;)V");
        abstractMethod(writer, "append", "(Ljava/lang/String;I)V");
        abstractMethod(writer, "play", "(Ljava/lang/String;)I");
        abstractMethod(writer, "playDelayed", "(Ljava/lang/String;I)V");
        abstractMethod(writer, "startConnecting",
                "(" + START_CONNECTING_OLD_ARGS + TRANSFER_STATE + ")V");
        abstractMethod(writer, "refactored", "(IJ)V");
        abstractMethod(writer, "ambiguous", "(ILjava/lang/String;)V");
        abstractMethod(writer, "ambiguous", "(Ljava/lang/String;I)V");
        abstractMethod(writer, "annotated", "(ILjava/lang/String;)V");
        abstractMethod(writer, "getHoveredSlot", "()I");
        abstractMethod(writer, "container", "()V");
        abstractMethod(writer, "redirectCall", "(ILjava/lang/String;)I");
        abstractMethod(writer, "overwriteAdded",
                "(ILjava/lang/String;)Ljava/lang/String;");
        abstractMethod(writer, "substituted", "(I)V");
        abstractMethod(writer, "changedReturn", "(Ljava/lang/String;)I");
        abstractMethod(writer, "returnValue",
                "(ILjava/lang/String;)Ljava/lang/String;");
        abstractMethod(writer, "returnValueUnchanged",
                "(Ljava/lang/String;)Ljava/lang/String;");
        abstractMethod(writer, "returnValueAlsoChanged",
                "(ILjava/lang/String;)Ljava/lang/String;");
        staticMethod(writer, "returnValueStatic",
                "(ILjava/lang/String;)Ljava/lang/String;");
        abstractMethod(writer, "expression", "(JLjava/lang/String;)V");
        abstractMethod(writer, "returnPair",
                "(ILjava/lang/String;J)Ljava/lang/String;");
        abstractMethod(writer, "method_55665",
                "(J" + REVAMPED_ENTITY + REVAMPED_ENTITY + REVAMPED_ATTACHMENTS + ")"
                        + REVAMPED_VEC);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void abstractMethod(ClassWriter writer, String name, String descriptor) {
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, descriptor, null, null).visitEnd();
    }

    private static void staticMethod(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        emitDefaultReturn(method, Type.getReturnType(descriptor));
        method.visitMaxs(1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
        method.visitEnd();
    }

    private static byte[] injectMixin(String simpleName, String selector,
            String handlerDescriptor, boolean annotateFirstParameter) {
        return injectMixin(simpleName, selector, handlerDescriptor,
                annotateFirstParameter, true);
    }

    private static byte[] injectMixin(String simpleName, String selector,
            String handlerDescriptor, boolean annotateFirstParameter, boolean remap) {
        return injectMixin(simpleName, List.of(selector), handlerDescriptor,
                annotateFirstParameter, remap);
    }

    private static byte[] injectMixin(String simpleName, List<String> selectors,
            String handlerDescriptor, boolean annotateFirstParameter, boolean remap) {
        return injectMixin(simpleName, selectors, handlerDescriptor,
                annotateFirstParameter, remap, true);
    }

    private static byte[] injectMixinWithClassRemapDisabled(
            String simpleName, String selector, String handlerDescriptor) {
        return injectMixin(simpleName, List.of(selector), handlerDescriptor,
                false, true, false);
    }

    private static byte[] injectMixin(String simpleName, List<String> selectors,
            String handlerDescriptor, boolean annotateFirstParameter, boolean remap,
            boolean classRemap) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "test/mixin/" + simpleName, null, "java/lang/Object", null);
        addMixinTarget(writer, classRemap);

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "handler", handlerDescriptor, null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, false);
        AnnotationVisitor methods = inject.visitArray("method");
        for (String selector : selectors) methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor at = inject.visitAnnotation("at", AT);
        at.visit("value", "HEAD");
        at.visitEnd();
        if (!remap) inject.visit("remap", false);
        inject.visitEnd();
        if (annotateFirstParameter) {
            handler.visitParameterAnnotation(0, LOCAL, false).visitEnd();
        }
        handler.visitCode();
        Type[] handlerArgs = Type.getArgumentTypes(handlerDescriptor);
        int callbackSlot = 1;
        for (int i = 0; i < handlerArgs.length - 1; i++) {
            callbackSlot += handlerArgs[i].getSize();
        }
        handler.visitVarInsn(Opcodes.ALOAD, callbackSlot);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] duplicateInjectAnnotation(byte[] classBytes) {
        ClassNode node = readClass(classBytes);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("handler"))
                .findFirst().orElseThrow();
        AnnotationNode original = annotation(method, INJECT);
        AnnotationNode duplicate = new AnnotationNode(original.desc);
        duplicate.values = original.values == null ? null : new ArrayList<>(original.values);
        if (method.invisibleAnnotations == null) method.invisibleAnnotations = new ArrayList<>();
        method.invisibleAnnotations.add(duplicate);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] addGroupAnnotation(byte[] classBytes) {
        ClassNode node = readClass(classBytes);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("handler"))
                .findFirst().orElseThrow();
        if (method.invisibleAnnotations == null) {
            method.invisibleAnnotations = new ArrayList<>();
        }
        AnnotationNode group = new AnnotationNode(GROUP);
        group.values = new ArrayList<>(List.of("name", "compatibility"));
        method.invisibleAnnotations.add(group);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] valueModifierMixin(String simpleName, String injectorDescriptor,
            String selector, String handlerDescriptor) {
        return valueModifierMixin(simpleName, injectorDescriptor, List.of(selector),
                handlerDescriptor, Opcodes.ACC_PRIVATE, -1);
    }

    private static byte[] valueModifierMixin(String simpleName, String injectorDescriptor,
            List<String> selectors, String handlerDescriptor) {
        return valueModifierMixin(simpleName, injectorDescriptor, selectors,
                handlerDescriptor, Opcodes.ACC_PRIVATE, -1);
    }

    private static byte[] valueModifierMixin(String simpleName, String injectorDescriptor,
            String selector, String handlerDescriptor, int handlerAccess,
            int unsafeParameterIndex) {
        return valueModifierMixin(simpleName, injectorDescriptor, List.of(selector),
                handlerDescriptor, handlerAccess, unsafeParameterIndex);
    }

    private static byte[] valueModifierMixin(String simpleName, String injectorDescriptor,
            List<String> selectors, String handlerDescriptor, int handlerAccess,
            int unsafeParameterIndex) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "test/mixin/" + simpleName, null, "java/lang/Object", null);
        addMixinTarget(writer);

        MethodVisitor handler = writer.visitMethod(
                handlerAccess, "handler", handlerDescriptor, null, null);
        AnnotationVisitor injector = handler.visitAnnotation(injectorDescriptor, false);
        AnnotationVisitor methods = injector.visitArray("method");
        for (String selector : selectors) methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor at = injector.visitAnnotation("at", AT);
        at.visit("value", MODIFY_RETURN.equals(injectorDescriptor) ? "RETURN" : "CONSTANT");
        at.visitEnd();
        injector.visitEnd();
        if (unsafeParameterIndex >= 0) {
            handler.visitParameterAnnotation(unsafeParameterIndex, LOCAL, false).visitEnd();
        }

        Type[] args = Type.getArgumentTypes(handlerDescriptor);
        handler.visitCode();
        int firstSlot = (handlerAccess & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        if (args.length > 1) {
            int capturedSlot = firstSlot + args[0].getSize();
            handler.visitVarInsn(args[1].getOpcode(Opcodes.ILOAD), capturedSlot);
            handler.visitInsn(args[1].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        }
        Type returnType = Type.getReturnType(handlerDescriptor);
        if (args[0].equals(returnType)) {
            handler.visitVarInsn(args[0].getOpcode(Opcodes.ILOAD), firstSlot);
            handler.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
        } else {
            emitDefaultReturn(handler, returnType);
        }
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitDefaultReturn(MethodVisitor method, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> method.visitInsn(Opcodes.RETURN);
            case Type.OBJECT, Type.ARRAY -> {
                method.visitInsn(Opcodes.ACONST_NULL);
                method.visitInsn(Opcodes.ARETURN);
            }
            case Type.LONG -> {
                method.visitInsn(Opcodes.LCONST_0);
                method.visitInsn(Opcodes.LRETURN);
            }
            case Type.FLOAT -> {
                method.visitInsn(Opcodes.FCONST_0);
                method.visitInsn(Opcodes.FRETURN);
            }
            case Type.DOUBLE -> {
                method.visitInsn(Opcodes.DCONST_0);
                method.visitInsn(Opcodes.DRETURN);
            }
            default -> {
                method.visitInsn(Opcodes.ICONST_0);
                method.visitInsn(Opcodes.IRETURN);
            }
        }
    }

    private static byte[] invokerMixin() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "test/mixin/InvokerMixin", null, "java/lang/Object", null);
        addMixinTarget(writer);
        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "callFindSlot", "()I", null, null);
        AnnotationVisitor invoker = handler.visitAnnotation(INVOKER, false);
        invoker.visit("value", "findSlot");
        invoker.visitEnd();
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] redirectMixin(String callTarget) {
        return redirectMixin(callTarget, true);
    }

    private static byte[] redirectMixin(String callTarget, boolean atRemap) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "test/mixin/RedirectMixin", null, "java/lang/Object", null);
        addMixinTarget(writer);

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "handler",
                "(L" + TARGET + ";Ljava/lang/String;)I", null, null);
        AnnotationVisitor redirect = handler.visitAnnotation(REDIRECT, false);
        AnnotationVisitor methods = redirect.visitArray("method");
        methods.visit(null, "container()V");
        methods.visitEnd();
        AnnotationVisitor at = redirect.visitAnnotation("at", AT);
        at.visit("value", "INVOKE");
        at.visit("target", callTarget);
        if (!atRemap) at.visit("remap", false);
        at.visitEnd();
        redirect.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 1);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/lang/String", "length", "()I", false);
        handler.visitInsn(Opcodes.IRETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] overwriteMixin() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "test/mixin/OverwriteMixin", null, "java/lang/Object", null);
        addMixinTarget(writer);

        MethodVisitor overwrite = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "overwriteAdded",
                "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        overwrite.visitAnnotation(OVERWRITE, false).visitEnd();
        overwrite.visitCode();
        overwrite.visitVarInsn(Opcodes.ALOAD, 1);
        overwrite.visitInsn(Opcodes.ARETURN);
        overwrite.visitMaxs(0, 0);
        overwrite.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addMixinTarget(ClassWriter writer) {
        addMixinTarget(writer, true);
    }

    private static void addMixinTarget(ClassWriter writer, boolean remap) {
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(TARGET));
        values.visitEnd();
        if (!remap) mixin.visit("remap", false);
        mixin.visitEnd();
    }

    private static MethodNode handler(byte[] classBytes) {
        ClassNode node = readClass(classBytes);
        return node.methods.stream()
                .filter(method -> method.name.equals("handler") || method.name.equals("callFindSlot"))
                .findFirst()
                .orElseThrow();
    }

    private static MethodNode method(byte[] classBytes, String name) {
        return readClass(classBytes).methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ClassNode readClass(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private static String injectSelector(byte[] classBytes) {
        List<String> selectors = injectSelectors(classBytes);
        if (selectors.size() == 1) return selectors.get(0);
        throw new AssertionError("expected one @Inject method selector, got " + selectors);
    }

    private static List<String> injectSelectors(byte[] classBytes) {
        AnnotationNode inject = annotation(handler(classBytes), INJECT);
        Object value = annotationValue(inject, "method");
        if (value instanceof List<?> selectors) {
            return selectors.stream().map(String::valueOf).toList();
        }
        throw new AssertionError("expected @Inject method selectors, got " + value);
    }

    private static String injectorSelector(byte[] classBytes, String injectorDescriptor) {
        List<String> selectors = injectorSelectors(classBytes, injectorDescriptor);
        if (selectors.size() == 1) return selectors.get(0);
        throw new AssertionError("expected one injector method selector, got " + selectors);
    }

    private static List<String> injectorSelectors(
            byte[] classBytes, String injectorDescriptor) {
        AnnotationNode injector = annotation(handler(classBytes), injectorDescriptor);
        Object value = annotationValue(injector, "method");
        if (value instanceof List<?> selectors) {
            return selectors.stream().map(String::valueOf).toList();
        }
        if (value instanceof String selector) return List.of(selector);
        throw new AssertionError("expected injector method selectors, got " + value);
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null
                        ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null
                        ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        throw new AssertionError("annotation not found: " + descriptor);
    }

    private static String atTarget(AnnotationNode injector) {
        Object value = annotationValue(injector, "at");
        if (value instanceof AnnotationNode at) {
            Object target = annotationValue(at, "target");
            if (target instanceof String selector) return selector;
        }
        throw new AssertionError("expected one @At target, got " + value);
    }

    private static int firstVar(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode variable && instruction.getOpcode() == opcode) {
                return variable.var;
            }
        }
        throw new AssertionError("variable instruction not found for opcode " + opcode);
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
}
