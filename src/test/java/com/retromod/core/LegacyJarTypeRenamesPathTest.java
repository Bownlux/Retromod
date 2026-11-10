/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.aot.AotCompiler;
import com.retromod.cli.RetromodCli;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.LegacyEnchantmentBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A Forge 1.20.1 enchantment class extends {@code Enchantment}, which 1.21 made a final record.
 * The Forge runtime renames it to the generated stand-in before its class pass; the CLI and AOT
 * paths have to make the same rename, and serve the renamed classes to the class hierarchy.
 */
class LegacyJarTypeRenamesPathTest {

    private static final String OLD = "net/minecraft/world/item/enchantment/Enchantment";
    private static final String RARITY = "Lnet/minecraft/world/item/enchantment/Enchantment$Rarity;";
    private static final String CATEGORY = "Lnet/minecraft/world/item/enchantment/EnchantmentCategory;";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String ARCHEOLOGY = "ench/ArcheologyEnchantment";
    private static final String MOD_ENCHANTMENTS = "ench/ModEnchantments";
    private static final String NESTED = "META-INF/jarjar/nested.jar";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private Field cliTarget;
    private String savedCliTarget;
    private String savedTarget;

    @BeforeEach
    void save() throws Exception {
        cliTarget = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        cliTarget.setAccessible(true);
        savedCliTarget = (String) cliTarget.get(null);
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() throws Exception {
        cliTarget.set(null, savedCliTarget);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
        transformer.clearJarRegistryIds();
    }

    @ParameterizedTest(name = "target {0}, NeoForge {1}")
    @CsvSource({"26.3, true", "26.3, false", "1.21.1, true"})
    @DisplayName("every path rebases the enchantment and keeps its factory, outer jar and bundled jar")
    void everyPathRebasesTheEnchantment(String target, boolean neoForge, @TempDir Path directory)
            throws Exception {
        host(target, neoForge);
        LegacyEnchantmentBridge.register(transformer);
        Path input = withNested(directory, forgeMod());

        Map<String, Map<String, byte[]>> outputs = new LinkedHashMap<>();
        outputs.put("CLI", cli(input, directory));
        outputs.put("AOT", aot(input, directory));
        outputs.put("Forge runtime", read(new ForgeModTransformer(target)
                .transformMod(input, Files.createDirectories(directory.resolve("runtime")))));

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertRebased(output.getKey(), output.getValue());
            assertRebased(output.getKey() + " nested jar", read(output.getValue().get(NESTED), directory));
        }
    }

    @Test
    @DisplayName("a host before 1.21 keeps the enchantment class as it is")
    void olderHostKeepsTheSuperclass(@TempDir Path directory) throws Exception {
        host("1.20.4", true);
        LegacyEnchantmentBridge.register(transformer);
        Map<String, byte[]> cli = cli(Files.write(directory.resolve("input.jar"), jar(forgeMod())), directory);

        assertEquals(OLD, node(cli.get(ARCHEOLOGY + ".class")).superName,
                "Enchantment is still an open class on 1.20.4");
    }

    @Test
    @DisplayName("a 1.21 jar with no old enchantment markers passes through unchanged")
    void modernJarIsLeftAlone() {
        host("26.3", true);
        LegacyEnchantmentBridge.register(transformer);
        byte[] modern = classWith("ench/Modern", "java/lang/Object", cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "describe", "(L" + OLD + ";)V", null, null);
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        LegacyJarTypeRenames renames = LegacyJarTypeRenames.forJar(transformer, List.of(modern));
        Function<String, byte[]> provider = name -> modern;

        assertSame(modern, renames.apply(modern), "a record user must keep the record type");
        assertSame(provider, renames.over(provider), "no rename means the original provider");
    }

    // ---- assertions ----

    private static void assertRebased(String path, Map<String, byte[]> jar) {
        ClassNode archeology = node(jar.get(ARCHEOLOGY + ".class"));
        assertTrue(archeology.superName.endsWith(LegacyEnchantmentBridge.LEGACY),
                path + ": the record cannot be extended, so the class must extend the stand-in, got "
                        + archeology.superName);
        ClassNode enchantments = node(jar.get(MOD_ENCHANTMENTS + ".class"));
        assertTrue(calls(enchantments, "level").stream().anyMatch(call ->
                        call.owner.endsWith("LegacyEnchantmentSupport") && call.name.equals("level")),
                path + ": a level lookup must match the item's enchantments by id");
        // The hierarchy saw the renamed class, so the factory still builds the enchantment.
        MethodNode create = method(enchantments, "create");
        assertTrue(instructions(create).stream().anyMatch(insn -> insn.getOpcode() == NEW
                        && ((TypeInsnNode) insn).desc.equals(ARCHEOLOGY)),
                path + ": new ArcheologyEnchantment() must survive, not become a thrower");
    }

    // ---- paths ----

    private Map<String, byte[]> cli(Path input, Path directory) throws Exception {
        Path output = directory.resolve("cli-output.jar");
        ModVersionInfo info = new ModVersionDetector().detectVersion(input);
        Method transform = RetromodCli.class.getDeclaredMethod("transformJar", Path.class,
                Path.class, RetromodTransformer.class, ModVersionInfo.class);
        transform.setAccessible(true);
        transform.invoke(null, input, output, transformer, info);
        return read(output);
    }

    private Map<String, byte[]> aot(Path input, Path directory) throws Exception {
        Path output = directory.resolve("aot-output.jar");
        ModVersionInfo info = new ModVersionDetector().detectVersion(input);
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), RetromodVersion.TARGET_MC_VERSION);
        Method compile = AotCompiler.class.getDeclaredMethod("compileJar", Path.class, Path.class,
                ModVersionInfo.class);
        compile.setAccessible(true);
        compile.invoke(compiler, input, output, info);
        return read(output);
    }

    private void host(String version, boolean neoForge) {
        try {
            cliTarget.set(null, version);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        RetromodVersion.TARGET_MC_VERSION = version;
        McReflect.setForceNeoForge(neoForge);
    }

    // ---- fixtures ----

    /** Fossils and Archeology's shape: an enchantment class, and a helper that reads and builds it. */
    private static Map<String, byte[]> forgeMod() {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"ench\"\nversion=\"1.0\"\n[[dependencies.ench]]\nmodId=\"minecraft\"\n"
                + "mandatory=true\nversionRange=\"[1.20.1,1.21)\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
                .getBytes(StandardCharsets.UTF_8));
        mod.put(ARCHEOLOGY + ".class", archeology());
        mod.put(MOD_ENCHANTMENTS + ".class", modEnchantments());
        return mod;
    }

    /** {@code super(VERY_RARE, DIGGER, new EquipmentSlot[0])}. */
    private static byte[] archeology() {
        return classWith(ARCHEOLOGY, OLD, cw -> {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitFieldInsn(GETSTATIC, OLD + "$Rarity", "VERY_RARE", RARITY);
            init.visitFieldInsn(GETSTATIC, "net/minecraft/world/item/enchantment/EnchantmentCategory",
                    "DIGGER", CATEGORY);
            init.visitInsn(ICONST_0);
            init.visitTypeInsn(ANEWARRAY, "net/minecraft/world/entity/EquipmentSlot");
            init.visitMethodInsn(INVOKESPECIAL, OLD, "<init>",
                    "(" + RARITY + CATEGORY + "[Lnet/minecraft/world/entity/EquipmentSlot;)V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        });
    }

    /** {@code level(enchantment, stack)} reads a level; {@code create()} returns the enchantment as Object. */
    private static byte[] modEnchantments() {
        return classWith(MOD_ENCHANTMENTS, "java/lang/Object", cw -> {
            MethodVisitor level = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "level",
                    "(L" + OLD + ";L" + STACK + ";)I", null, null);
            level.visitCode();
            level.visitVarInsn(ALOAD, 0);
            level.visitVarInsn(ALOAD, 1);
            level.visitMethodInsn(INVOKESTATIC, "net/minecraft/world/item/enchantment/EnchantmentHelper",
                    "getItemEnchantmentLevel", "(L" + OLD + ";L" + STACK + ";)I", false);
            level.visitInsn(IRETURN);
            level.visitMaxs(0, 0);
            level.visitEnd();
            MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", "()Ljava/lang/Object;",
                    null, null);
            create.visitCode();
            create.visitTypeInsn(NEW, ARCHEOLOGY);
            create.visitInsn(DUP);
            create.visitMethodInsn(INVOKESPECIAL, ARCHEOLOGY, "<init>", "()V", false);
            create.visitInsn(ARETURN);
            create.visitMaxs(0, 0);
            create.visitEnd();
        });
    }

    private static byte[] classWith(String name, String superName,
            java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path withNested(Path directory, Map<String, byte[]> mod) throws IOException {
        Map<String, byte[]> outer = new LinkedHashMap<>(mod);
        outer.put(NESTED, jar(mod));
        return Files.write(directory.resolve("input.jar"), jar(outer));
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> read(byte[] jar, Path directory) throws IOException {
        assertNotNull(jar, "the nested jar is kept");
        return read(Files.write(Files.createTempFile(directory, "nested-", ".jar"), jar));
    }

    private static Map<String, byte[]> read(Path jar) throws IOException {
        assertNotNull(jar, "the path produced a jar");
        Map<String, byte[]> entries = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            var all = file.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;
                try (var input = file.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static ClassNode node(byte[] bytes) {
        assertNotNull(bytes, "the class is kept");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name + " in " + node.name));
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        return List.of(method.instructions.toArray());
    }

    private static List<MethodInsnNode> calls(ClassNode node, String methodName) {
        return instructions(method(node, methodName)).stream()
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }
}
