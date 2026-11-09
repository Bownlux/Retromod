/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Sophisticated Core's ItemStackKey finds Forge 1.20.1's ItemStack.capNBT by reflection in its static initializer. */
class LegacyCapNbtFieldAdapterTest {

    private static final String KEY_CLASS = "mod/ItemStackKey";
    private static final String ITEM_STACK = LegacyCapNbtFieldAdapter.ITEM_STACK;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the capNBT lookup goes to the helper and any other getDeclaredField call stays")
    void rewritesOnlyTheCapNbtLookup() {
        ClassNode out = read(LegacyCapNbtFieldAdapter.apply(itemStackKey()));

        List<MethodInsnNode> lookups = new ArrayList<>();
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("getDeclaredField")) lookups.add(call);
            }
        }
        assertEquals(2, lookups.size());
        assertEquals(LegacyCapNbtFieldAdapter.HELPER, lookups.get(0).owner, "the capNBT lookup asks the helper");
        assertEquals(Opcodes.INVOKESTATIC, lookups.get(0).getOpcode());
        assertEquals("java/lang/Class", lookups.get(1).owner, "the count lookup is left alone");
    }

    @Test
    @DisplayName("a class that never names capNBT comes back as the same bytes")
    void leavesOtherClassesAlone() {
        byte[] other = classWith("count");
        assertSame(other, LegacyCapNbtFieldAdapter.apply(other));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"forge 26.2", "forge 26.3", "forge 1.21.1", "neoforge 26.2", "fabric 26.2"})
    @DisplayName("only a Forge target from 26.2 turns the adapter on when its host cannot be read")
    void registersOnForgeFrom262Only(String target) {
        String[] parts = target.split(" ");
        target(parts[0], parts[1]);

        LegacyCapNbtFieldAdapter.register(transformer);

        boolean expected = parts[0].equals("forge") && !parts[1].equals("1.21.1");
        assertEquals(expected, transformer.getSyntheticClasses().containsKey(LegacyCapNbtFieldAdapter.HELPER), target);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"runtime", "transform"})
    @DisplayName("the embedded helper answers a null capability tag where ItemStack has no capNBT, and the real field where it has one")
    void embeddedHelperAnswersBothHosts(String path) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("sophisticatedcore.jar");
        Files.write(input, LoaderApiFixtures.jar(Map.of(
                "META-INF/mods.toml", modsToml(),
                KEY_CLASS + ".class", itemStackKey())));

        Map<String, byte[]> classes = classes(translate(path, input));

        String helper = classes.keySet().stream()
                .filter(name -> name.endsWith("/" + LegacyCapNbtFieldAdapter.HELPER)).findFirst()
                .orElseGet(() -> fail("the helper was not embedded: " + classes.keySet()));
        assertTrue(helper.startsWith("com/retromod/embedded/"), "the mod carries its own copy: " + helper);
        // On Forge 26.2 the field is gone, so the stack reads no capability tag.
        assertNull(capNbtOf(classes, hostItemStack(false), "anything"));
        // A host that still has the field is read as before.
        assertEquals("tag", capNbtOf(classes, hostItemStack(true), "tag"));
    }

    // ---- fixtures ----

    private void target(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        transformer.clearRedirectsForTesting();
    }

    private Path translate(String path, Path input) throws Exception {
        target("forge", "26.2");
        Path out = Files.createDirectories(dir.resolve("out"));
        if (path.equals("transform")) {
            CliHarness.transform(input, out.resolve("sophisticatedcore.jar"), "26.2");
            return out.resolve("sophisticatedcore.jar");
        }
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            boolean family = shim.getModLoaderType().equals("forge") || shim.getModLoaderType().equals("common");
            if (family && ShimRegistry.isAvailableOnHost(shim, "26.2")) ShimRegistration.register(shim, transformer);
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer("26.2").transformMod(input, out);
    }

    /**
     * ItemStackKey's shape: {@code capNbt(stack)} finds {@code ItemStack.class.getDeclaredField("capNBT")}, makes it
     * accessible and reads it from the stack. {@code count()} looks up another field, which must stay as written.
     */
    private static byte[] itemStackKey() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, KEY_CLASS, null, "java/lang/Object", null);
        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "capNbt",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, new String[] {"java/lang/Exception"});
        read.visitCode();
        read.visitLdcInsn(Type.getObjectType(ITEM_STACK));
        read.visitLdcInsn("capNBT");
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                LegacyCapNbtFieldAdapter.GET_DECLARED_FIELD, false);
        read.visitInsn(Opcodes.DUP);
        read.visitInsn(Opcodes.ICONST_1);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false);
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        MethodVisitor count = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "count",
                "()Ljava/lang/reflect/Field;", null, new String[] {"java/lang/Exception"});
        count.visitCode();
        count.visitLdcInsn(Type.getObjectType(ITEM_STACK));
        count.visitLdcInsn("count");
        count.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                LegacyCapNbtFieldAdapter.GET_DECLARED_FIELD, false);
        count.visitInsn(Opcodes.ARETURN);
        count.visitMaxs(0, 0);
        count.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class looking up one other field of ItemStack, and never naming capNBT. */
    private static byte[] classWith(String field) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Other", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "find", "()Ljava/lang/reflect/Field;",
                null, new String[] {"java/lang/Exception"});
        mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(ITEM_STACK));
        mv.visitLdcInsn(field);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                LegacyCapNbtFieldAdapter.GET_DECLARED_FIELD, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A stand-in host ItemStack: a public constructor, and {@code capNBT} holding its argument when the host has it. */
    private static byte[] hostItemStack(boolean withCapNbt) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, ITEM_STACK, null, "java/lang/Object", null);
        if (withCapNbt) cw.visitField(Opcodes.ACC_PRIVATE, "capNBT", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (withCapNbt) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, ITEM_STACK, "capNBT", "Ljava/lang/Object;");
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Runs the translated {@code capNbt} on a stack built with {@code tag}, in a loader holding the jar and the host. */
    private static Object capNbtOf(Map<String, byte[]> jar, byte[] host, Object tag) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(jar);
        classes.put(ITEM_STACK, host);
        ClassLoader loader = new ClassLoader(LegacyCapNbtFieldAdapterTest.class.getClassLoader().getParent()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Object stack = loader.loadClass(ITEM_STACK.replace('/', '.')).getConstructor(Object.class).newInstance(tag);
        return loader.loadClass(KEY_CLASS.replace('/', '.')).getMethod("capNbt", Object.class).invoke(null, stack);
    }

    private static Map<String, byte[]> classes(Path jarPath) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                classes.put(entry.getName().substring(0, entry.getName().length() - 6),
                        jar.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] modsToml() {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"sophisticatedcore\"\n"
                + "version=\"1.0.0\"\n[[dependencies.sophisticatedcore]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }
}
