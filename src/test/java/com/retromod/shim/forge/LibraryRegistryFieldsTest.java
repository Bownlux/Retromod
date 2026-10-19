/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticLibraries;
import com.retromod.shim.forge.embedded.LibraryRegistryFields;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #302: a Forge 1.20.1 Farmer's Delight addon reads the library's effects and items as
 * {@code RegistryObject} fields, which the library's NeoForge 1.21 build declares as
 * {@code Holder} or {@code Supplier}.
 */
class LibraryRegistryFieldsTest {

    private static final String REGISTRY_OBJECT = "Lnet/minecraftforge/registries/RegistryObject;";
    private static final String DEFERRED_HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";
    private static final String READER = "com/retromod/shim/forge/embedded/LibraryRegistryFields";
    private static final String LIBRARY_EFFECTS = "vectorwing/farmersdelight/common/registry/ModEffects";
    private static final String ADDON_FOODS = "net/count/example/Foods";
    private static final String ADDON_ITEMS = "net/count/example/ModItems";

    private RetromodTransformer transformer;
    private String host;

    @BeforeEach
    void setUp() {
        host = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        McReflect.setForceNeoForge(true);
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceNeoForge(false);
        transformer.clearLinkedLibraries();
        transformer.clearRedirectsForTesting();
    }

    @Test
    void anAddonReadOfALibraryRegistryFieldIsReadByName() {
        MethodNode read = method(transformAddon(), "nourishment");

        assertFalse(reads(read, LIBRARY_EFFECTS), "the typed read fails against the library's Holder field");
        List<String> steps = steps(read);
        int call = steps.indexOf("call " + READER + ".read(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;");
        assertTrue(call >= 2, "the field is read by name: " + steps);
        assertEquals(List.of("ldc " + LIBRARY_EFFECTS, "ldc NOURISHMENT"), steps.subList(call - 2, call), steps.toString());
        assertEquals("cast " + DEFERRED_HOLDER, steps.get(call + 1), "the value is the holder the addon reads");
    }

    @Test
    void theAddonsOwnRegistryFieldsAreReadDirectly() {
        ClassNode foods = transformAddon();

        assertTrue(reads(method(foods, "ownItem"), ADDON_ITEMS), "a class from the addon's own jar keeps its read");
        assertTrue(reads(method(foods, "ownEffect"), ADDON_FOODS), "so does the reading class itself");
        assertFalse(steps(method(foods, "ownItem")).stream().anyMatch(step -> step.contains(READER)));
    }

    @Test
    void aLibraryThatStillDeclaresTheReadTypeIsReadDirectly(@TempDir Path dir) throws IOException {
        transformer.setLinkedLibraries(library(dir, libraryEffects("L" + DEFERRED_HOLDER + ";")));

        assertTrue(reads(method(transformAddon(), "nourishment"), LIBRARY_EFFECTS),
                "a translated Farmer's Delight 1.20.1 has the DeferredHolder field the addon reads");
    }

    @Test
    void aLinkedLibraryThatRetypedTheFieldIsReadByName(@TempDir Path dir) throws IOException {
        transformer.setLinkedLibraries(library(dir, libraryEffects("Lnet/minecraft/core/Holder;")));

        assertFalse(reads(method(transformAddon(), "nourishment"), LIBRARY_EFFECTS),
                "the installed 1.21.1 build declares a Holder");
    }

    @Test
    void theTranslatedAddonRunsAgainstTheLibrarysCurrentFields() throws Exception {
        byte[] foods = transformAddonBytes();
        Map<String, byte[]> classes = fakeHost();
        classes.put(ADDON_FOODS, foods);

        Class<?> addon = load(classes, ADDON_FOODS);

        assertEquals("farmersdelight:nourishment", addon.getMethod("nourishment").invoke(null),
                "the addon's RegistryObject.get() reaches the library's effect");
    }

    @Test
    void theReaderReturnsTheLibrarysEntryWhateverTheFieldType() throws Throwable {
        Class<?> entries = load(fakeHost(), LIBRARY_EFFECTS);
        Class<?> reader = entries.getClassLoader().loadClass(READER.replace('/', '.'));

        for (String field : List.of("KNIFE", "NOURISHMENT", "TOMATO_CROP")) {
            assertSame(entries.getField(field).get(null), read(reader, entries, field),
                    field + " already holds the DeferredHolder the library registered");
        }
        Object comfort = read(reader, entries, "COMFORT");
        assertEquals(DEFERRED_HOLDER.replace('/', '.'), comfort.getClass().getName(),
                "a holder made without a deferred register is looked up by its key");
        Object key = comfort.getClass().getField("key").get(comfort);
        assertEquals("farmersdelight:comfort", key.getClass().getField("id").get(key));
        assertSame(comfort, read(reader, entries, "COMFORT"), "a final field is looked up once");
    }

    @Test
    void theReaderFailsLikeTheJvmWhenTheFieldCannotBeAnEntry() throws Exception {
        Class<?> entries = load(fakeHost(), LIBRARY_EFFECTS);
        Class<?> reader = entries.getClassLoader().loadClass(READER.replace('/', '.'));

        assertThrows(NoSuchFieldError.class, () -> read(reader, entries, "MISSING"));
        assertThrows(IncompatibleClassChangeError.class, () -> read(reader, entries, "perInstance"));
        IncompatibleClassChangeError plain = assertThrows(IncompatibleClassChangeError.class,
                () -> read(reader, entries, "PLAIN"));
        assertTrue(plain.getMessage().contains(LIBRARY_EFFECTS.replace('/', '.') + ".PLAIN"), plain.getMessage());
    }

    // ---- the addon -------------------------------------------------------------------------

    private ClassNode transformAddon() {
        ClassNode node = new ClassNode();
        new ClassReader(transformAddonBytes()).accept(node, 0);
        return node;
    }

    /** Transforms the addon class with its jar's class list in scope, as every real path has. */
    private byte[] transformAddonBytes() {
        byte[] foods = addonFoods();
        try (var scope = transformer.pushJarClassBytesProvider(
                Map.of(ADDON_FOODS, foods, ADDON_ITEMS, addonItems())::get)) {
            return transformer.transformClass(foods, ADDON_FOODS);
        }
    }

    /** Reads its library's effect, its own item and its own effect, each as a Forge RegistryObject. */
    private static byte[] addonFoods() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ADDON_FOODS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "OWN", REGISTRY_OBJECT, null, null).visitEnd();
        readAndGet(cw, "nourishment", LIBRARY_EFFECTS, "NOURISHMENT");
        readAndGet(cw, "ownItem", ADDON_ITEMS, "KNIFE");
        readAndGet(cw, "ownEffect", ADDON_FOODS, "OWN");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] addonItems() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ADDON_ITEMS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "KNIFE", REGISTRY_OBJECT,
                null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void readAndGet(ClassWriter cw, String method, String owner, String field) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method,
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, field, REGISTRY_OBJECT);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/registries/RegistryObject", "get",
                "()Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The library's effects class with one effect field of the given type. */
    private static byte[] libraryEffects(String fieldDescriptor) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, LIBRARY_EFFECTS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "NOURISHMENT",
                fieldDescriptor, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static SyntheticLibraries library(Path dir, byte[] effects) throws IOException {
        Path jar = dir.resolve("FarmersDelight.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(LIBRARY_EFFECTS + ".class"));
            out.write(effects);
            out.closeEntry();
        }
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(jar);
        return libraries;
    }

    // ---- inspection --------------------------------------------------------------------------

    private static boolean reads(MethodNode method, String owner) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> steps(MethodNode method) {
        List<String> steps = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof LdcInsnNode ldc) {
                steps.add("ldc " + (ldc.cst instanceof Type type ? type.getInternalName() : ldc.cst));
            } else if (insn instanceof MethodInsnNode call) {
                steps.add("call " + call.owner + "." + call.name + call.desc);
            } else if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST) {
                steps.add("cast " + type.desc);
            } else if (insn instanceof FieldInsnNode field) {
                steps.add("field " + field.owner + "." + field.name);
            }
        }
        return steps;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    // ---- a NeoForge 1.21 host with the library, on its own -----------------------------------

    private static Object read(Class<?> reader, Class<?> owner, String field) throws Throwable {
        Method read = reader.getMethod("read", Class.class, String.class);
        try {
            return read.invoke(null, owner, field);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The fake registry types under their NeoForge names, the library's effects, and the reader. */
    private static Map<String, byte[]> fakeHost() throws IOException {
        String fakes = Type.getInternalName(LibraryRegistryFieldFakes.class);
        Map<String, String> names = new HashMap<>();
        names.put(fakes + "$Key", "net/minecraft/resources/ResourceKey");
        names.put(fakes + "$Holder", "net/minecraft/core/Holder");
        names.put(fakes + "$DeferredHolder", DEFERRED_HOLDER);
        names.put(fakes + "$ReferenceHolder", "vectorwing/farmersdelight/common/registry/ReferenceHolder");
        names.put(fakes + "$Entries", LIBRARY_EFFECTS);
        SimpleRemapper remapper = new SimpleRemapper(names);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<String, String> fake : names.entrySet()) {
            classes.put(fake.getValue(), standalone(bytesOf(fake.getKey()), remapper));
        }
        classes.put(READER, bytesOf(Type.getInternalName(LibraryRegistryFields.class)));
        return classes;
    }

    /** Renames a nested fake into its own top-level class, without its links to the test's classes. */
    private static byte[] standalone(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitNestHost(String nestHost) {}

            @Override
            public void visitNestMember(String nestMember) {}

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {}
        }, remapper), 0);
        return cw.toByteArray();
    }

    private static byte[] bytesOf(String internalName) throws IOException {
        try (InputStream in = LibraryRegistryFieldsTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            return in.readAllBytes();
        }
    }

    private static Class<?> load(Map<String, byte[]> classes, String internalName) throws ClassNotFoundException {
        ClassLoader loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return Class.forName(internalName.replace('/', '.'), true, loader);
    }
}
