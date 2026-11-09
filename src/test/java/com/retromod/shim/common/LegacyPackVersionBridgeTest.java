/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.testsupport.fakemc.packver.FakeComponent;
import com.retromod.testsupport.fakemc.packver.FakeInclusiveRange;
import com.retromod.testsupport.fakemc.packver.FakePackFormat;
import com.retromod.testsupport.fakemc.packver.FakePackMetadataSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.retromod.shim.common.LegacyPackVersionBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A host whose pack versions have a major and minor number answers the old single number with the major one. */
class LegacyPackVersionBridgeTest {

    private static final String MOD = "mod/PackVersion";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void clean() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old call becomes a call to the helper embedded in the mod")
    void oldCallTakesTheHelper() {
        LegacyPackVersionBridge.register(transformer, worldVersion(false));
        transformer.setModSourceVersion("1.21.1");

        MethodInsnNode call = onlyCall(node(transformer.transformClass(modClass(), MOD + ".class")));

        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(HELPER + ".getPackVersion" + HELPER_DESC, call.owner + "." + call.name + call.desc);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER));
    }

    @Test
    @DisplayName("A host that still has the old method, and an offline target before 1.21.11, keep the call")
    void hostsWithTheMethodKeepIt() {
        LegacyPackVersionBridge.register(transformer, worldVersion(true));
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "1.21.1 still has getPackVersion");

        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyPackVersionBridge.register(transformer, name -> null);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "offline, 1.21.1");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyPackVersionBridge.register(transformer, name -> null);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "offline, 26.3");
    }

    @ParameterizedTest(name = "NeoForge's Minecraft {0}")
    @ValueSource(strings = {"21.11.42", "26.1.2.60-beta", "26.2.0.0-beta", "26.3.0.43-beta"})
    @DisplayName("The real client jars from 1.21.11 to 26.3 have packVersion and no getPackVersion")
    void realClientJars(String version) throws Exception {
        Path jar = Path.of(System.getProperty("user.home"), "Library", "Application Support", "PrismLauncher",
                "libraries", "net", "neoforged", "minecraft-client-patched", version,
                "minecraft-client-patched-" + version + ".jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar), "no Minecraft " + version + " jar");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Function<String, ClassNode> host = name -> {
                ZipEntry entry = zip.getEntry(name + ".class");
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return node(in.readAllBytes());
                } catch (IOException unreadable) {
                    return null;
                }
            };
            assertTrue(hostReplacedIt(host), version);
        }
    }

    @Test
    @DisplayName("The helper returns the major number of the host's pack format")
    void helperReturnsTheMajorNumber() throws Exception {
        ClassLoader game = gameLoader();
        Class<?> worldVersion = Class.forName(WORLD_VERSION.replace('/', '.'), true, game);
        Class<?> packType = Class.forName(PACK_TYPE.replace('/', '.'), true, game);
        Object version = Class.forName("test.Version", true, game).getConstructor().newInstance();
        Object type = packType.getConstructor().newInstance();

        Object major = Class.forName(HELPER.replace('/', '.'), true, game)
                .getMethod("getPackVersion", worldVersion, packType).invoke(null, version, type);

        assertEquals(94, major, "26.3's server data format 94.1 was 94 to an old mod");
    }

    @Test
    @DisplayName("An old pack metadata constructor becomes a call to the helper")
    void oldMetadataTakesTheHelper() {
        LegacyPackVersionBridge.register(transformer, worldVersion(false));
        transformer.setModSourceVersion("1.21.1");

        ClassNode out = node(transformer.transformClass(metadataClass(), MOD + ".class"));

        MethodNode build = out.methods.stream().filter(m -> m.name.equals("build")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : build.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) calls.add("new " + type.desc);
        }
        assertEquals(List.of("java/util/Optional.empty()Ljava/util/Optional;",
                HELPER + ".packMetadata(L" + COMPONENT + ";ILjava/util/Optional;)L" + METADATA + ";"), calls);
    }

    @Test
    @DisplayName("Old metadata becomes the range of formats its number and supported range named, with every minor version")
    void metadataBecomesARange() throws Exception {
        ClassLoader game = gameLoader();
        Class<?> component = Class.forName(COMPONENT.replace('/', '.'), true, game);
        Class<?> range = Class.forName(RANGE.replace('/', '.'), true, game);
        Object description = component.getConstructor().newInstance();
        Object supported = range.getConstructor(Comparable.class, Comparable.class).newInstance(40, 50);
        java.lang.reflect.Method packMetadata = Class.forName(HELPER.replace('/', '.'), true, game)
                .getMethod("packMetadata", component, int.class, java.util.Optional.class);

        assertEquals(List.of(40, 0, 50, Integer.MAX_VALUE),
                formats(packMetadata.invoke(null, description, 48, java.util.Optional.of(supported))));
        assertEquals(List.of(48, 0, 48, Integer.MAX_VALUE),
                formats(packMetadata.invoke(null, description, 48, java.util.Optional.empty())));
    }

    /** min major, min minor, max major, max minor of a metadata's supported range. */
    private static List<Integer> formats(Object metadata) throws Exception {
        Object supported = metadata.getClass().getMethod("supported").invoke(metadata);
        List<Integer> numbers = new ArrayList<>();
        for (String end : List.of("minInclusive", "maxInclusive")) {
            Object format = supported.getClass().getMethod(end).invoke(supported);
            numbers.add((Integer) format.getClass().getMethod("major").invoke(format));
            numbers.add((Integer) format.getClass().getMethod("minor").invoke(format));
        }
        return numbers;
    }

    /** {@code static PackMetadataSection build(Component c) { return new PackMetadataSection(c, 48, Optional.empty()); }} */
    private static byte[] metadataClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build", "(L" + COMPONENT + ";)L" + METADATA + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, METADATA);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitIntInsn(BIPUSH, 48);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false);
        mv.visitMethodInsn(INVOKESPECIAL, METADATA, "<init>", OLD_METADATA, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    /** {@code static int read(WorldVersion version, PackType type) { return version.getPackVersion(type); }} */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(L" + WORLD_VERSION + ";L" + PACK_TYPE + ";)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, WORLD_VERSION, "getPackVersion", OLD_DESC, true);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A host WorldVersion with the new method, and the old one too when {@code old}. */
    private static Function<String, ClassNode> worldVersion(boolean old) {
        ClassNode node = node(worldVersionInterface());
        if (old) node.methods.add(new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, "getPackVersion", OLD_DESC, null, null));
        return name -> name.equals(WORLD_VERSION) ? node : null;
    }

    private static byte[] worldVersionInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, WORLD_VERSION, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "packVersion", "(L" + PACK_TYPE + ";)L" + PACK_FORMAT + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A game with the helper, the host types it names, and a WorldVersion whose server data format is 94.1. */
    private static ClassLoader gameLoader() throws IOException {
        Map<String, String> names = Map.of(
                FakeComponent.class.getName().replace('.', '/'), COMPONENT,
                FakePackFormat.class.getName().replace('.', '/'), PACK_FORMAT,
                FakeInclusiveRange.class.getName().replace('.', '/'), RANGE,
                FakePackMetadataSection.class.getName().replace('.', '/'), METADATA);
        SimpleRemapper remapper = new SimpleRemapper(names);
        Map<String, byte[]> classes = new HashMap<>();
        for (Class<?> fake : List.of(FakeComponent.class, FakePackFormat.class, FakeInclusiveRange.class,
                FakePackMetadataSection.class)) {
            classes.put(names.get(fake.getName().replace('.', '/')).replace('/', '.'), remap(bytesOf(fake), remapper));
        }
        classes.put(HELPER.replace('/', '.'), helper());
        classes.put(WORLD_VERSION.replace('/', '.'), worldVersionInterface());
        classes.put(PACK_TYPE.replace('/', '.'), emptyClass(PACK_TYPE));
        classes.put("test.Version", versionImpl());
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** A WorldVersion that answers every pack type with format 94.1. */
    private static byte[] versionImpl() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Version", null, "java/lang/Object", new String[] {WORLD_VERSION});
        constructor(cw);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "packVersion", "(L" + PACK_TYPE + ";)L" + PACK_FORMAT + ";", null, null);
        mv.visitCode();
        mv.visitIntInsn(BIPUSH, 94);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESTATIC, PACK_FORMAT, "of", "(II)L" + PACK_FORMAT + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        constructor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
    }

    private static MethodInsnNode onlyCall(ClassNode node) {
        MethodNode read = node.methods.stream().filter(m -> m.name.equals("read")).findFirst().orElseThrow();
        for (AbstractInsnNode insn : read.instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call in read");
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
