/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.retromod.core.verify.FuzzyBackedSymbolIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The transform verifier is a diagnostic: it must never abort a transform (#102).
 *
 * <p>Its class/method/field resolution probes load referenced classes to check them against the
 * target. When a referenced class lives in a protected {@code @Mixin} package, Mixin throws
 * {@code IllegalClassLoadError} - an {@link Error}, not an {@link Exception} - during the load.
 * The probes used to catch only {@code Exception}, so that Error escaped and killed the whole
 * transform pass (a Mine Mine No Mi addon, Cart's, dragged in a mineminenomi mixin class). The
 * probes now catch {@link Throwable}. That exact Mixin Error needs a live Mixin classloader to
 * reproduce, so it's verified in-game; here we lock in the host-independent invariant: the probes
 * return a boolean and never propagate, including for names that don't resolve.
 */
class TransformVerifierTest {

    private static final String INDEXED_TARGET = "net/minecraft/test/IndexedTarget";

    private static Object probe(String method, Class<?>[] sig, Object... args) throws Exception {
        Method m = TransformVerifier.class.getDeclaredMethod(method, sig);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    @DisplayName("#102 canResolveClass never throws; resolvable name → true, unresolvable → no throw")
    void canResolveClassNeverThrows() {
        Class<?>[] sig = {String.class};
        assertEquals(Boolean.TRUE, assertDoesNotThrow(() -> probe("canResolveClass", sig, "java/lang/String")));
        Object missing = assertDoesNotThrow(() -> probe("canResolveClass", sig, "no/such/Class$Missing"));
        assertInstanceOf(Boolean.class, missing, "must return a boolean, never propagate");
    }

    @Test
    @DisplayName("#102 canResolveMethod never throws for resolvable and unresolvable owners")
    void canResolveMethodNeverThrows() {
        Class<?>[] sig = {String.class, String.class, String.class};
        assertEquals(Boolean.TRUE,
                assertDoesNotThrow(() -> probe("canResolveMethod", sig, "java/lang/String", "length", "()I")));
        assertInstanceOf(Boolean.class,
                assertDoesNotThrow(() -> probe("canResolveMethod", sig, "no/such/Owner", "x", "()V")),
                "unresolvable owner must not propagate");
    }

    @Test
    @DisplayName("#102 canResolveField never throws for resolvable and unresolvable owners")
    void canResolveFieldNeverThrows() {
        Class<?>[] sig = {String.class, String.class};
        assertEquals(Boolean.TRUE,
                assertDoesNotThrow(() -> probe("canResolveField", sig, "java/lang/Integer", "MAX_VALUE")));
        assertInstanceOf(Boolean.class,
                assertDoesNotThrow(() -> probe("canResolveField", sig, "no/such/Owner", "x")),
                "unresolvable owner must not propagate");
    }

    @Test
    @DisplayName("CLI verification uses the supplied Minecraft jar index")
    void suppliedMinecraftIndexPreventsClasspathFalsePositive(@TempDir Path dir)
            throws Exception {
        String target = "net/minecraft/world/entity/EntityTypes";
        Path minecraftJar = dir.resolve("minecraft-26.2-client.jar");
        writeJar(minecraftJar, target + ".class", emptyClass(target));

        Path transformedMod = dir.resolve("transformed-mod.jar");
        writeJar(transformedMod, "test/IndexProbe.class", classReferencing(target));

        TransformVerifier.VerifyResult classpathOnly = TransformVerifier.verify(
                transformedMod, "index-probe.jar", "26.2");
        assertFalse(classpathOnly.passed(),
                "the standalone test classpath deliberately has no Minecraft classes");

        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(minecraftJar);
        TransformVerifier.VerifyResult indexed = TransformVerifier.verify(
                transformedMod, "index-probe.jar", "26.2",
                new FuzzyBackedSymbolIndex(resolver, "26.2"));

        assertTrue(indexed.passed(),
                "a class present in --mc-jar must not be reported as missing");
    }

    @Test
    @DisplayName("CLI verification accepts an exact constructor from the Minecraft jar")
    void indexedConstructorIsAccepted(@TempDir Path dir) throws Exception {
        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClass(INDEXED_TARGET, "java/lang/Object", "()V"));
        Path transformedMod = dir.resolve("valid-constructor.jar");
        writeJar(transformedMod, "test/ConstructorProbe.class",
                classCallingConstructor(INDEXED_TARGET, "()V"));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "valid-constructor.jar", "26.2", index);

        assertTrue(result.passed(),
                "a constructor declared by the indexed target must resolve exactly");
    }

    @Test
    @DisplayName("A missing constructor produces one constructor issue, not a duplicate method issue")
    void missingConstructorIsReportedOnce(@TempDir Path dir) throws Exception {
        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClass(INDEXED_TARGET, "java/util/ArrayList", "(I)V"));
        Path transformedMod = dir.resolve("missing-constructor.jar");
        writeJar(transformedMod, "test/ConstructorProbe.class",
                classCallingConstructor(INDEXED_TARGET, "()V"));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "missing-constructor.jar", "26.2", index);

        assertEquals(1, result.issueCount(),
                "the constructor call must not also enter the ordinary-method report");
        assertEquals(TransformVerifier.IssueType.MISSING_CONSTRUCTOR,
                result.issues().get(0).type());
        assertEquals("()V", result.issues().get(0).descriptor());
    }

    @Test
    @DisplayName("Exact lookup follows an indexed Minecraft class into its JDK ancestors")
    void inheritedJdkMethodIsAccepted(@TempDir Path dir) throws Exception {
        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClass(INDEXED_TARGET, "java/util/AbstractList", "()V"));
        Path transformedMod = dir.resolve("jdk-inherited-method.jar");
        writeJar(transformedMod, "test/InheritedMethodProbe.class",
                classCallingMethod(INDEXED_TARGET, "add", "(Ljava/lang/Object;)Z"));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "jdk-inherited-method.jar", "26.2", index);

        assertTrue(result.passed(),
                "AbstractList.add(Object) is inherited by the indexed Minecraft class: "
                        + result.issues());
    }

    @Test
    @DisplayName("JDK ancestor lookup does not hide a truly missing ordinary method")
    void trulyMissingMethodIsStillReported(@TempDir Path dir) throws Exception {
        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClass(INDEXED_TARGET, "java/util/AbstractList", "()V"));
        Path transformedMod = dir.resolve("missing-method.jar");
        writeJar(transformedMod, "test/MissingMethodProbe.class",
                classCallingMethod(INDEXED_TARGET, "retromodMissing", "()V"));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "missing-method.jar", "26.2", index);

        assertEquals(1, result.issueCount());
        assertEquals(TransformVerifier.IssueType.MISSING_METHOD,
                result.issues().get(0).type());
        assertEquals("retromodMissing", result.issues().get(0).name());
    }

    @Test
    @DisplayName("Exact verification rejects a same-name field with the wrong descriptor")
    void sameNameWrongFieldDescriptorIsReported(@TempDir Path dir) throws Exception {
        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClassWithField(INDEXED_TARGET, "value", "Ljava/lang/String;"));
        Path transformedMod = dir.resolve("wrong-field-descriptor.jar");
        writeJar(transformedMod, "test/FieldProbe.class",
                classReadingField(INDEXED_TARGET, "value", "I"));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "wrong-field-descriptor.jar", "26.2", index);

        assertEquals(1, result.issueCount());
        assertEquals(TransformVerifier.IssueType.MISSING_FIELD,
                result.issues().get(0).type());
        assertEquals("value", result.issues().get(0).name());
        assertEquals("I", result.issues().get(0).descriptor());
    }

    @Test
    @DisplayName("Verification follows classes through recursively bundled libraries")
    void recursivelyBundledClassIsInternal(@TempDir Path dir) throws Exception {
        String bundled = "dev/example/bundled/Helper";
        byte[] secondLevel = jarBytes(bundled + ".class", emptyClass(bundled));
        byte[] firstLevel = jarBytes(
                "META-INF/jars/second-level.jar", secondLevel);
        Path transformedMod = dir.resolve("nested-library.jar");
        writeJar(transformedMod,
                new String[]{
                        "test/IndexProbe.class",
                        "META-INF/jars/first-level.jar"
                },
                new byte[][]{
                        classReferencing(bundled),
                        firstLevel
                });

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "nested-library.jar", "26.2");

        assertTrue(result.passed(),
                "a class packaged in a nested library is part of the mod classpath: "
                        + result.issues());
    }

    @Test
    @DisplayName("Mixin framework references are supplied by the loader")
    void mixinRuntimeClassesAreSafe(@TempDir Path dir) throws Exception {
        Path transformedMod = dir.resolve("mixin-runtime.jar");
        writeJar(transformedMod,
                "test/IndexProbe.class",
                classReferencing(
                        "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "mixin-runtime.jar", "26.2");

        assertTrue(result.passed(),
                        "Fabric Loader provides Sponge Mixin at runtime: " + result.issues());
    }

    @Test
    @DisplayName("runtime library prefixes survive CLI dependency relocation")
    void runtimeLibraryPrefixesStaySafe() throws Exception {
        Class<?>[] signature = {String.class};
        assertEquals(Boolean.TRUE, probe("isSafe", signature, "org/slf4j/Logger"));
        assertEquals(Boolean.TRUE, probe("isSafe", signature, "com/google/gson/JsonObject"));
    }

    @Test
    @DisplayName("nested DataFixerUpper links are not treated as client-jar classes")
    void nestedDataFixerUpperLinkIsASeparateRuntimeLibrary(@TempDir Path dir)
            throws Exception {
        byte[] library = jarBytes(
                "dev/example/bundled/CodecUser.class",
                classReferencing("com/mojang/serialization/Codec"));
        Path transformedMod = dir.resolve("datafixer-library.jar");
        writeJar(transformedMod, "META-INF/jars/codecs.jar", library);

        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClass(INDEXED_TARGET, "java/lang/Object", "()V"));
        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "datafixer-library.jar", "26.2", index);

        assertTrue(result.passed(),
                "DataFixerUpper is a Minecraft runtime library outside the client jar: "
                        + result.issues());
    }

    @Test
    @DisplayName("Optional dependencies used only inside bundled libraries stay library-local")
    void nestedLibraryOptionalDependencyIsNotAnOuterModIssue(@TempDir Path dir)
            throws Exception {
        String bundled = "dev/example/bundled/Helper";
        String optional = "dev/example/optional/FormatBackend";
        byte[] library = jarBytes(
                bundled + ".class", classReferencing(optional));
        Path transformedMod = dir.resolve("optional-nested-dependency.jar");
        writeJar(transformedMod,
                new String[]{
                        "test/IndexProbe.class",
                        "META-INF/jars/library.jar"
                },
                new byte[][]{
                        classReferencing(bundled),
                        library
                });

        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClass(INDEXED_TARGET, "java/lang/Object", "()V"));
        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "optional-nested-dependency.jar", "26.2", index);

        assertTrue(result.passed(),
                "outer bytecode links to the bundled class, while the library owns its optional "
                        + "backend selection: " + result.issues());
    }

    @Test
    @DisplayName("recursively nested target links use exact symbol checks")
    void staleNestedMinecraftLinksAreReported(@TempDir Path dir) throws Exception {
        String missingMojangClass = "com/mojang/blaze3d/test/MissingNestedType";
        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClassWithField(
                        INDEXED_TARGET, "value", "Ljava/lang/String;"));
        byte[] innerLibrary = jarBytes(
                "dev/example/bundled/StaleMinecraftLinks.class",
                classWithStaleNestedTargetLinks(INDEXED_TARGET, missingMojangClass));
        byte[] library = jarBytes(
                "META-INF/jars/inner-compat-library.jar", innerLibrary);
        Path transformedMod = dir.resolve("stale-nested-targets.jar");
        writeJar(transformedMod, "META-INF/jars/compat-library.jar", library);

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "stale-nested-targets.jar", "26.2", index);

        assertEquals(4, result.issueCount(), result.issues().toString());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.type() == TransformVerifier.IssueType.MISSING_CLASS
                        && missingMojangClass.equals(issue.owner())));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.type() == TransformVerifier.IssueType.MISSING_METHOD
                        && INDEXED_TARGET.equals(issue.owner())
                        && "staleMethod".equals(issue.name())
                        && "()V".equals(issue.descriptor())));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.type() == TransformVerifier.IssueType.MISSING_FIELD
                        && INDEXED_TARGET.equals(issue.owner())
                        && "value".equals(issue.name())
                        && "I".equals(issue.descriptor())));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.type() == TransformVerifier.IssueType.MISSING_CONSTRUCTOR
                        && INDEXED_TARGET.equals(issue.owner())
                        && "()V".equals(issue.descriptor())));
    }

    @Test
    @DisplayName("ANEWARRAY descriptors are normalized to their element class")
    void arrayTypeInstructionDoesNotBecomeAClassName(@TempDir Path dir) throws Exception {
        String bundled = "dev/example/bundled/Element";
        Path transformedMod = dir.resolve("array-type.jar");
        writeJar(transformedMod,
                new String[]{"test/ArrayProbe.class", bundled + ".class"},
                new byte[][]{classCreatingArray(bundled), emptyClass(bundled)});

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "array-type.jar", "26.2");

        assertTrue(result.passed(),
                "the verifier must never report an array descriptor as a class: "
                        + result.issues());
    }

    @Test
    @DisplayName("enum-style object array clone checks the element class, not the array owner")
    void objectArrayCloneUsesElementClassForExistence(@TempDir Path dir) throws Exception {
        FuzzyBackedSymbolIndex index = indexTarget(
                dir, targetClass(INDEXED_TARGET, "java/lang/Object", "()V"));
        Path transformedMod = dir.resolve("enum-array-clone.jar");
        writeJar(transformedMod, "test/EnumArrayCloneProbe.class",
                classCloningObjectArray(INDEXED_TARGET));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "enum-array-clone.jar", "26.2", index);

        assertTrue(result.passed(),
                "array clone is inherited from Object and its element exists in the target jar: "
                        + result.issues());
    }

    @Test
    @DisplayName("primitive array clone does not create a class reference")
    void primitiveArrayCloneIsIgnored(@TempDir Path dir) throws Exception {
        Path transformedMod = dir.resolve("primitive-array-clone.jar");
        writeJar(transformedMod, "test/PrimitiveArrayCloneProbe.class",
                classCloningPrimitiveArray());

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "primitive-array-clone.jar", "26.2");

        assertTrue(result.passed(),
                "a primitive array has no element class to resolve: " + result.issues());
    }

    @Test
    @DisplayName("MULTIANEWARRAY reports only its missing object element")
    void multiArrayTracksObjectElementAndIgnoresPrimitiveComponent(@TempDir Path dir)
            throws Exception {
        String missingElement = "no/such/MultiArrayElement";
        Path transformedMod = dir.resolve("multi-array.jar");
        writeJar(transformedMod, "test/MultiArrayProbe.class",
                classCreatingMultiArrays(missingElement));

        TransformVerifier.VerifyResult result = TransformVerifier.verify(
                transformedMod, "multi-array.jar", "26.2");

        assertEquals(1, result.issueCount(),
                "the primitive component must not be treated as a class: " + result.issues());
        assertEquals(TransformVerifier.IssueType.MISSING_CLASS,
                result.issues().get(0).type());
        assertEquals(missingElement, result.issues().get(0).owner());
    }

    private static FuzzyBackedSymbolIndex indexTarget(Path dir, byte[] targetBytes)
            throws Exception {
        Path minecraftJar = dir.resolve("minecraft-26.2-client.jar");
        writeJar(minecraftJar, INDEXED_TARGET + ".class", targetBytes);
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(minecraftJar);
        return new FuzzyBackedSymbolIndex(resolver, "26.2");
    }

    private static void writeJar(Path jar, String entryName, byte[] classBytes)
            throws IOException {
        writeJar(jar, new String[]{entryName}, new byte[][]{classBytes});
    }

    private static void writeJar(Path jar, String[] entryNames, byte[][] contents)
            throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < entryNames.length; i++) {
                out.putNextEntry(new JarEntry(entryNames[i]));
                out.write(contents[i]);
                out.closeEntry();
            }
        }
    }

    private static byte[] jarBytes(String entryName, byte[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(contents);
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetClass(String name, String superName, String constructorDescriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", constructorDescriptor, null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetClassWithField(
            String name, String fieldName, String fieldDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, fieldName, fieldDescriptor, null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classCallingConstructor(String target, String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ConstructorProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, target);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", descriptor, false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classCallingMethod(String target, String name, String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/MethodProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "(L" + target + ";Ljava/lang/Object;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        if (descriptor.startsWith("(Ljava/lang/Object;)")) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
        }
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target, name, descriptor, false);
        if (!descriptor.endsWith(")V")) {
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classReadingField(
            String target, String fieldName, String fieldDescriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/FieldProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "(L" + target + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, target, fieldName, fieldDescriptor);
        method.visitInsn(Type.getType(fieldDescriptor).getSize() == 2
                ? Opcodes.POP2 : Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classReferencing(String target) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/IndexProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "(Ljava/lang/Object;)V",
                null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitTypeInsn(Opcodes.CHECKCAST, target);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classCreatingArray(String elementType) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ArrayProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "[L" + elementType + ";");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classCloningObjectArray(String elementType) {
        String arrayDescriptor = "[L" + elementType + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/EnumArrayCloneProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "(" + arrayDescriptor + ")" + arrayDescriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, arrayDescriptor,
                "clone", "()Ljava/lang/Object;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, arrayDescriptor);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classCloningPrimitiveArray() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/PrimitiveArrayCloneProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "([I)[I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[I",
                "clone", "()Ljava/lang/Object;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, "[I");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classCreatingMultiArrays(String objectElement) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/MultiArrayProbe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMultiANewArrayInsn("[[L" + objectElement + ";", 2);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMultiANewArrayInsn("[[I", 2);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithStaleNestedTargetLinks(
            String target, String missingMojangClass) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "dev/example/bundled/StaleMinecraftLinks", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "(Ljava/lang/Object;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitTypeInsn(Opcodes.CHECKCAST, missingMojangClass);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target,
                "staleMethod", "()V", false);
        method.visitFieldInsn(Opcodes.GETSTATIC, target, "value", "I");
        method.visitInsn(Opcodes.POP);
        method.visitTypeInsn(Opcodes.NEW, target);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", "()V", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
