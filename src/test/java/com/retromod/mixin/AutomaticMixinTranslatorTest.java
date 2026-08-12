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
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
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
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void abstractMethod(ClassWriter writer, String name, String descriptor) {
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, descriptor, null, null).visitEnd();
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
