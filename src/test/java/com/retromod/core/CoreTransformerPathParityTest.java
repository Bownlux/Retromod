/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.mixin.MixinCompatibilityTransformer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class CoreTransformerPathParityTest {
    private static final String MIXIN_CLASS = "fixture/SelectorMixin";
    private static final String TARGET_CLASS = "fixture/Target";
    private static final String OLD_SELECTOR = "oldName()V";
    private static final String NEW_SELECTOR = "newName()V";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT_DESC =
            "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;

    @BeforeEach
    void configureRedirect() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect(
                TARGET_CLASS, "oldName", "()V",
                TARGET_CLASS, "newName", "()V");
    }

    @AfterEach
    void restoreRedirects() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    void hybridPipelineRepairsSelectorTextBeforeClassRemap() {
        byte[] transformed = HybridTransformationEngine.transformWithMixinRepairs(
                transformer, new MixinCompatibilityTransformer(transformer),
                selectorMixin(), MIXIN_CLASS);

        assertEquals(NEW_SELECTOR, selector(transformed));
    }

    @Test
    void hybridPipelineUsesSiblingClassesForHierarchyProofs() {
        String base = "fixture/Base";
        String child = "fixture/Child";
        String caller = "fixture/Caller";
        transformer.registerInheritedMethodRedirect(
                base, "oldInherited", "()V",
                base, "newInherited", "()V");

        byte[] childBytes = emptyClass(child, base);
        byte[] transformed = HybridTransformationEngine.transformWithMixinRepairs(
                transformer, new MixinCompatibilityTransformer(transformer),
                inheritedCaller(caller, child), caller,
                name -> child.equals(name) ? childBytes : null);

        MethodInsnNode call = onlyMethodCall(transformed, "run");
        assertEquals(base, call.owner);
        assertEquals("newInherited", call.name);
    }

    @Test
    void forgeArchiveRepairsSelectorTextBeforeClassRemap(@TempDir Path directory)
            throws Exception {
        Path source = directory.resolve("forge-selector.jar");
        String metadata = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                + "[[mods]]\nmodId=\"selector_fixture\"\nversion=\"1.0\"\n"
                + "[[dependencies.selector_fixture]]\nmodId=\"minecraft\"\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\n";
        writeJar(source, List.of(
                new Entry("META-INF/mods.toml", metadata.getBytes(StandardCharsets.UTF_8)),
                new Entry(MIXIN_CLASS + ".class", selectorMixin())));

        Path outputDirectory = Files.createDirectory(directory.resolve("output"));
        Path transformed = new ForgeModTransformer("26.2")
                .transformMod(source, outputDirectory);

        assertNotNull(transformed);
        assertEquals(NEW_SELECTOR, selector(readEntry(transformed, MIXIN_CLASS + ".class")));
    }

    @Test
    void fabricNestedArchiveRepairsSelectorTextBeforeClassRemap(@TempDir Path directory)
            throws Exception {
        Path nested = directory.resolve("nested.jar");
        writeJar(nested, List.of(new Entry(MIXIN_CLASS + ".class", selectorMixin())));
        FabricModTransformer fabric = new FabricModTransformer("26.2");
        Method process = FabricModTransformer.class.getDeclaredMethod(
                "processNestedJiJJar", Path.class, String.class, int.class,
                RetromodTransformer.NestedArchiveBudget.class);
        process.setAccessible(true);

        boolean changed = (boolean) process.invoke(
                fabric, nested, "outer.jar!/META-INF/jars/nested.jar", 1,
                RetromodTransformer.NestedArchiveBudget.defaults());

        assertTrue(changed);
        assertEquals(NEW_SELECTOR, selector(readEntry(nested, MIXIN_CLASS + ".class")));
    }

    @Test
    void loaderTransformsUseCheckedDataMigration() throws IOException {
        assertCheckedDataMigration(FabricModTransformer.class, 2);
        assertCheckedDataMigration(ForgeModTransformer.class, 2);
    }

    private static void assertCheckedDataMigration(Class<?> transformerType,
            int minimumCheckedCalls) throws IOException {
        int[] checkedCalls = {0};
        int[] bestEffortCalls = {0};
        new ClassReader(transformerType.getName()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        if ("com/retromod/resources/ModDataMigrator".equals(owner)) {
                            if ("migrateTreeChecked".equals(methodName)) checkedCalls[0]++;
                            if ("migrateTree".equals(methodName)) bestEffortCalls[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(checkedCalls[0] >= minimumCheckedCalls,
                transformerType.getSimpleName() + " must fail closed on data migration errors");
        assertEquals(0, bestEffortCalls[0],
                transformerType.getSimpleName() + " must not use best-effort data migration");
    }

    private static byte[] selectorMixin() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                MIXIN_CLASS, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_DESC, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(TARGET_CLASS));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "retromod$inject", "()V", null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT_DESC, false);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, OLD_SELECTOR);
        methods.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 1);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name, String superName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, null, superName, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] inheritedCaller(String name, String child) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "(L" + child + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, child, "oldInherited", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodInsnNode onlyMethodCall(byte[] classBytes, String methodName) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream()
                .filter(method -> methodName.equals(method.name))
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst().orElseThrow();
    }

    private static String selector(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        MethodNode handler = node.methods.stream()
                .filter(method -> "retromod$inject".equals(method.name))
                .findFirst().orElseThrow();
        for (List<AnnotationNode> annotations : List.of(
                handler.visibleAnnotations != null
                        ? handler.visibleAnnotations : List.<AnnotationNode>of(),
                handler.invisibleAnnotations != null
                        ? handler.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (!INJECT_DESC.equals(annotation.desc) || annotation.values == null) continue;
                for (int index = 0; index < annotation.values.size(); index += 2) {
                    if ("method".equals(annotation.values.get(index))) {
                        Object value = annotation.values.get(index + 1);
                        if (value instanceof List<?> selectors && selectors.size() == 1) {
                            return String.valueOf(selectors.get(0));
                        }
                    }
                }
            }
        }
        throw new AssertionError("missing @Inject method selector");
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(name);
            assertNotNull(entry);
            try (var input = archive.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static void writeJar(Path target, List<Entry> entries) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            for (Entry entry : entries) {
                output.putNextEntry(new JarEntry(entry.name()));
                output.write(entry.bytes());
                output.closeEntry();
            }
        }
    }

    private record Entry(String name, byte[] bytes) {
    }
}
