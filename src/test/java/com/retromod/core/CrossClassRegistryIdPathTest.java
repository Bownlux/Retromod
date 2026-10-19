/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.aot.AotCompiler;
import com.retromod.cli.RetromodCli;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.api.forge.ForgeRegistryApiShim;
import com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Longwings 0.9.5 (Fabric 1.20.1) on Fabric 26.3: {@code LongwingsBlocks.<clinit>} builds every
 * block into a static field, and only {@code LongwingsBlocksFabric} names their ids, through
 * {@code registerBlock("name", LongwingsBlocks.FIELD)}. The block class initializes first and died
 * with {@code NullPointerException: Block id not set}. Every transform path, outer jar and
 * jar-in-jar alike, has to index the whole jar before it stamps the common class.
 */
class CrossClassRegistryIdPathTest {

    private static final String BLOCKS = "xclass/ModBlocks";
    private static final String ITEMS = "xclass/ModItems";
    private static final String REGISTRAR = "xclass/ModRegistrar";
    private static final String NESTED = "META-INF/jars/nested.jar";
    private static final String NEOFORGE_NESTED = "META-INF/jarjar/nested.jar";

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

    @Test
    @DisplayName("Fabric 26.3: every Fabric path stamps the common class's blocks and items with the loader class's ids")
    void fabricPaths(@TempDir Path directory) throws Exception {
        target("26.3");
        IntermediaryToMojangMapper.applyTo(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        Map<String, byte[]> mod = fabricMod();
        mod.put("fabric.mod.json", bytes("{\"schemaVersion\":1,\"id\":\"xclass\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"}}"));
        Path input = withNested(directory, mod, NESTED);

        Map<String, Map<String, byte[]>> outputs = new LinkedHashMap<>();
        outputs.put("CLI", cli(input, directory));
        outputs.put("AOT", aot(input, directory));
        outputs.put("Fabric runtime", read(new FabricModTransformer("26.3")
                .transformMod(input, Files.createDirectories(directory.resolve("fabric")))));

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertStamped(output.getKey(), output.getValue(), "FabricRegistryIdBridge");
            assertStamped(output.getKey() + " nested jar",
                    read(output.getValue().get(NESTED), directory), "FabricRegistryIdBridge");
        }
    }

    @Test
    @DisplayName("Quilt 26.3: the Quilt runtime runs the same Fabric pass")
    void quiltPath(@TempDir Path directory) throws Exception {
        target("26.3");
        IntermediaryToMojangMapper.applyTo(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        Map<String, byte[]> mod = fabricMod();
        mod.put("quilt.mod.json", bytes("{\"schema_version\":1,\"quilt_loader\":{\"group\":\"fixture\","
                + "\"id\":\"xclass\",\"version\":\"1.0\",\"depends\":[{\"id\":\"minecraft\","
                + "\"versions\":\"~1.20.1\"}]}}"));
        Path input = Files.write(directory.resolve("quilt-input.jar"), jar(mod));

        Path output = new QuiltModTransformer("26.3")
                .transformMod(input, Files.createDirectories(directory.resolve("quilt")));
        assertStamped("Quilt runtime", read(output), "FabricRegistryIdBridge");
    }

    @Test
    @DisplayName("Fabric 26.3: the full precompile workers and the hybrid background pass index the jar too")
    void precompilePaths(@TempDir Path directory) throws Exception {
        directory = directory.toRealPath();
        target("26.3");
        IntermediaryToMojangMapper.applyTo(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        Map<String, byte[]> mod = fabricMod();
        mod.put("fabric.mod.json", bytes("{\"schemaVersion\":1,\"id\":\"xclass\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"}}"));
        Path input = Files.write(directory.resolve("input.jar"), jar(mod));

        resetFullAot();
        try {
            com.retromod.aot.FullAotCompiler.getInstance(directory, "26.3")
                    .runFullCompilation(List.of(input)).get();
        } finally {
            resetFullAot();
        }
        Path cache = directory.resolve("retromod-cache/full-aot/xclass");
        Method cacheName = com.retromod.aot.FullAotCompiler.class
                .getDeclaredMethod("safeClassCacheFileName", String.class);
        cacheName.setAccessible(true);
        Map<String, byte[]> fullAot = new LinkedHashMap<>();
        for (String name : new String[]{BLOCKS, ITEMS}) {
            fullAot.put(name + ".class", Files.readAllBytes(cache.resolve((String) cacheName.invoke(null, name))));
        }
        assertStamped("full precompile", fullAot, "FabricRegistryIdBridge");

        var ids = transformer.indexJarRegistryIds(mod.values());
        var mixins = new com.retromod.mixin.MixinCompatibilityTransformer(transformer);
        Map<String, byte[]> hybrid = new LinkedHashMap<>();
        for (String name : new String[]{BLOCKS, ITEMS}) {
            hybrid.put(name + ".class", HybridTransformationEngine.transformWithMixinRepairs(transformer, mixins,
                    mod.get(name + ".class"), name, entry -> mod.get(entry + ".class"), ids));
        }
        assertStamped("hybrid background pass", hybrid, "FabricRegistryIdBridge");
    }

    private static void resetFullAot() throws Exception {
        Field instance = com.retromod.aot.FullAotCompiler.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    @DisplayName("NeoForge 26.3: a RegisterEvent consumer's ids reach the common class on every path")
    void neoForgePaths(@TempDir Path directory) throws Exception {
        target("26.3");
        McReflect.setForceNeoForge(true);
        new ForgeRegistryApiShim().registerRedirects(transformer);
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        Map<String, byte[]> mod = neoForgeMod();
        mod.put("META-INF/neoforge.mods.toml", bytes("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                + "license=\"MIT\"\n[[mods]]\nmodId=\"xclass\"\nversion=\"1.0\"\n"
                + "[[dependencies.xclass]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                + "versionRange=\"[1.21.1,1.21.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"));
        Path input = withNested(directory, mod, NEOFORGE_NESTED);

        Map<String, Map<String, byte[]>> outputs = new LinkedHashMap<>();
        outputs.put("CLI", cli(input, directory));
        outputs.put("AOT", aot(input, directory));
        outputs.put("NeoForge runtime", read(new ForgeModTransformer("26.3")
                .transformMod(input, Files.createDirectories(directory.resolve("neoforge")))));

        for (Map.Entry<String, Map<String, byte[]>> output : outputs.entrySet()) {
            assertStamped(output.getKey(), output.getValue(), "/RegistryIdBridge");
            assertStamped(output.getKey() + " nested jar",
                    read(output.getValue().get(NEOFORGE_NESTED), directory), "/RegistryIdBridge");
        }
    }

    @Test
    @DisplayName("NeoForge 26.3: a Forge 1.20.1 RegisterEvent consumer is read after the Forge key holder is renamed")
    void forgeModOnNeoForgeIsIndexed() {
        target("26.3");
        McReflect.setForceNeoForge(true);
        new ForgeRegistryApiShim().registerRedirects(transformer);
        Map<String, byte[]> mod = neoForgeMod();
        mod.put(REGISTRAR + ".class", forgeRegistrar());

        var ids = transformer.indexJarRegistryIds(mod.values());

        assertArrayEquals(new String[]{"xclass", "foo"}, ids.fieldsOf(BLOCKS).get("FOO"),
                "ForgeRegistries.Keys.BLOCKS reads as NeoForgeRegistries.Keys.BLOCKS after the rename");
    }

    /** {@code event.register(ForgeRegistries.Keys.BLOCKS, h -> h.register(new ResourceLocation("xclass", "foo"), ModBlocks.FOO))} */
    private static byte[] forgeRegistrar() {
        String event = "net/minecraftforge/registries/RegisterEvent";
        String helper = "net/minecraftforge/registries/RegisterEvent$RegisterHelper";
        String keys = "net/minecraftforge/registries/ForgeRegistries$Keys";
        String key = "net/minecraft/resources/ResourceKey";
        String location = "net/minecraft/resources/ResourceLocation";
        String block = "net/minecraft/world/level/block/Block";
        return classWith(REGISTRAR, cw -> {
            MethodVisitor onRegister = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onRegister",
                    "(L" + event + ";)V", null, null);
            onRegister.visitCode();
            onRegister.visitVarInsn(Opcodes.ALOAD, 0);
            onRegister.visitFieldInsn(Opcodes.GETSTATIC, keys, "BLOCKS", "L" + key + ";");
            onRegister.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory(),
                    Type.getType("(Ljava/lang/Object;)V"),
                    new Handle(Opcodes.H_INVOKESTATIC, REGISTRAR, "lambda$blocks", "(L" + helper + ";)V", false),
                    Type.getType("(L" + helper + ";)V"));
            onRegister.visitMethodInsn(Opcodes.INVOKEVIRTUAL, event, "register",
                    "(L" + key + ";Ljava/util/function/Consumer;)V", false);
            onRegister.visitInsn(Opcodes.RETURN);
            onRegister.visitMaxs(0, 0);
            onRegister.visitEnd();
            MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    "lambda$blocks", "(L" + helper + ";)V", null, null);
            lambda.visitCode();
            lambda.visitVarInsn(Opcodes.ALOAD, 0);
            lambda.visitTypeInsn(Opcodes.NEW, location);
            lambda.visitInsn(Opcodes.DUP);
            lambda.visitLdcInsn("xclass");
            lambda.visitLdcInsn("foo");
            lambda.visitMethodInsn(Opcodes.INVOKESPECIAL, location, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            lambda.visitFieldInsn(Opcodes.GETSTATIC, BLOCKS, "FOO", "L" + block + ";");
            lambda.visitMethodInsn(Opcodes.INVOKEINTERFACE, helper, "register",
                    "(L" + location + ";Ljava/lang/Object;)V", true);
            lambda.visitInsn(Opcodes.RETURN);
            lambda.visitMaxs(0, 0);
            lambda.visitEnd();
        });
    }

    @Test
    @DisplayName("a host that needs no id gets no index and no stamp")
    void olderHostIsLeftAlone() {
        target("1.21.1");
        Map<String, byte[]> mod = fabricMod();
        assertTrue(transformer.indexJarRegistryIds(mod.values()).isEmpty(),
                "without a registry id bridge there is nothing to stamp with");
    }

    // ---- assertions ----

    /**
     * {@code ModBlocks.FOO} is filled inside {@code enter("xclass:foo")} and {@code leave}, and so
     * is {@code ModItems.FOO_ITEM}, whose statement initializes {@code ModBlocks} first.
     */
    private static void assertStamped(String path, Map<String, byte[]> jar, String bridge) {
        assertStampedStore(path, jar.get(BLOCKS + ".class"), "FOO", bridge);
        assertStampedStore(path, jar.get(ITEMS + ".class"), "FOO_ITEM", bridge);
    }

    private static void assertStampedStore(String path, byte[] classBytes, String field, String bridge) {
        assertNotNull(classBytes, path + " kept the class that fills " + field);
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        List<String> code = new ArrayList<>();
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof LdcInsnNode ldc) code.add("ldc " + ldc.cst);
            if (insn instanceof MethodInsnNode call && call.owner.endsWith(bridge)) code.add(call.name);
            if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC) code.add("put " + put.name);
        }
        int enter = code.indexOf("enter");
        int store = code.indexOf("put " + field);
        assertTrue(enter >= 2 && code.get(enter - 2).equals("ldc xclass") && code.get(enter - 1).equals("ldc foo"),
                path + ": " + field + " must be built under xclass:foo, got " + code);
        assertTrue(store > enter && code.indexOf("leave") > store,
                path + ": the id must stay set until " + field + " is stored, got " + code);
    }

    // ---- paths ----

    private Map<String, byte[]> cli(Path input, Path directory) throws Exception {
        Path output = directory.resolve("cli-output.jar");
        ModVersionInfo info = new ModVersionDetector().detectVersion(input);
        assertNotNull(info, "the CLI reads this jar's metadata");
        Method transform = RetromodCli.class.getDeclaredMethod("transformJar", Path.class,
                Path.class, RetromodTransformer.class, ModVersionInfo.class);
        transform.setAccessible(true);
        transform.invoke(null, input, output, transformer, info);
        return read(output);
    }

    private Map<String, byte[]> aot(Path input, Path directory) throws Exception {
        Path output = directory.resolve("aot-output.jar");
        ModVersionInfo info = new ModVersionDetector().detectVersion(input);
        assertNotNull(info, "AOT reads this jar's metadata");
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), RetromodVersion.TARGET_MC_VERSION);
        Method compile = AotCompiler.class.getDeclaredMethod("compileJar", Path.class, Path.class,
                ModVersionInfo.class);
        compile.setAccessible(true);
        compile.invoke(compiler, input, output, info);
        return read(output);
    }

    private void target(String version) {
        try {
            cliTarget.set(null, version);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        RetromodVersion.TARGET_MC_VERSION = version;
    }

    // ---- fixtures ----

    /**
     * A Fabric 1.20.1 jar in intermediary names, laid out like Longwings: {@code ModBlocks} and
     * {@code ModItems} fill static fields, and {@code ModRegistrar} names them, items first so the
     * item statement is the one that initializes the block class.
     */
    private static Map<String, byte[]> fabricMod() {
        String block = "net/minecraft/class_2248";
        String blockProps = "net/minecraft/class_4970$class_2251";
        String item = "net/minecraft/class_1792";
        String itemProps = "net/minecraft/class_1792$class_1793";
        String blockItem = "net/minecraft/class_1747";
        String registry = "net/minecraft/class_2378";
        String id = "net/minecraft/class_2960";
        String builtIn = "net/minecraft/class_7923";
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(BLOCKS + ".class", holder(BLOCKS, "FOO", block, init -> {
            init.visitTypeInsn(Opcodes.NEW, block);
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESTATIC, blockProps, "method_9637", "()L" + blockProps + ";", false);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, block, "<init>", "(L" + blockProps + ";)V", false);
        }));
        mod.put(ITEMS + ".class", holder(ITEMS, "FOO_ITEM", item, init -> {
            init.visitTypeInsn(Opcodes.NEW, blockItem);
            init.visitInsn(Opcodes.DUP);
            init.visitFieldInsn(Opcodes.GETSTATIC, BLOCKS, "FOO", "L" + block + ";");
            init.visitTypeInsn(Opcodes.NEW, itemProps);
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, itemProps, "<init>", "()V", false);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, blockItem, "<init>",
                    "(L" + block + ";L" + itemProps + ";)V", false);
        }));
        mod.put(REGISTRAR + ".class", classWith(REGISTRAR, cw -> {
            fabricHelper(cw, "registerBlock", block, builtIn, "field_41175", registry, id);
            fabricHelper(cw, "registerItem", item, builtIn, "field_41178", registry, id);
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()V", null, null);
            register.visitCode();
            register.visitLdcInsn("foo");
            register.visitFieldInsn(Opcodes.GETSTATIC, ITEMS, "FOO_ITEM", "L" + item + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRAR, "registerItem",
                    "(Ljava/lang/String;L" + item + ";)L" + item + ";", false);
            register.visitInsn(Opcodes.POP);
            register.visitLdcInsn("foo");
            register.visitFieldInsn(Opcodes.GETSTATIC, BLOCKS, "FOO", "L" + block + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRAR, "registerBlock",
                    "(Ljava/lang/String;L" + block + ";)L" + block + ";", false);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        }));
        return mod;
    }

    /** {@code static T name(String path, T value) { return Registry.register(HOLDER, new Identifier("xclass", path), value); }} */
    private static void fabricHelper(ClassWriter cw, String name, String type, String builtIn, String field,
                                     String registry, String id) {
        MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name,
                "(Ljava/lang/String;L" + type + ";)L" + type + ";", null, null);
        helper.visitCode();
        helper.visitFieldInsn(Opcodes.GETSTATIC, builtIn, field, "Lnet/minecraft/class_7922;");
        helper.visitTypeInsn(Opcodes.NEW, id);
        helper.visitInsn(Opcodes.DUP);
        helper.visitLdcInsn("xclass");
        helper.visitVarInsn(Opcodes.ALOAD, 0);
        helper.visitMethodInsn(Opcodes.INVOKESPECIAL, id, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        helper.visitVarInsn(Opcodes.ALOAD, 1);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, registry, "method_10230",
                "(L" + registry + ";L" + id + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
        helper.visitTypeInsn(Opcodes.CHECKCAST, type);
        helper.visitInsn(Opcodes.ARETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
    }

    /**
     * A NeoForge 1.21.1 jar in Mojang names: the same common classes, and a loader class that
     * registers them from {@code RegisterEvent} consumers with literal ids.
     */
    private static Map<String, byte[]> neoForgeMod() {
        String block = "net/minecraft/world/level/block/Block";
        String blockProps = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        String item = "net/minecraft/world/item/Item";
        String itemProps = "net/minecraft/world/item/Item$Properties";
        String blockItem = "net/minecraft/world/item/BlockItem";
        String event = "net/neoforged/neoforge/registries/RegisterEvent";
        String helper = "net/neoforged/neoforge/registries/RegisterEvent$RegisterHelper";
        String key = "net/minecraft/resources/ResourceKey";
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(BLOCKS + ".class", holder(BLOCKS, "FOO", block, init -> {
            init.visitTypeInsn(Opcodes.NEW, block);
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESTATIC, blockProps, "of", "()L" + blockProps + ";", false);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, block, "<init>", "(L" + blockProps + ";)V", false);
        }));
        mod.put(ITEMS + ".class", holder(ITEMS, "FOO_ITEM", item, init -> {
            init.visitTypeInsn(Opcodes.NEW, blockItem);
            init.visitInsn(Opcodes.DUP);
            init.visitFieldInsn(Opcodes.GETSTATIC, BLOCKS, "FOO", "L" + block + ";");
            init.visitTypeInsn(Opcodes.NEW, itemProps);
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, itemProps, "<init>", "()V", false);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, blockItem, "<init>",
                    "(L" + block + ";L" + itemProps + ";)V", false);
        }));
        mod.put(REGISTRAR + ".class", classWith(REGISTRAR, cw -> {
            MethodVisitor onRegister = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onRegister",
                    "(L" + event + ";)V", null, null);
            onRegister.visitCode();
            String[][] registries = {{"ITEM", "lambda$items"}, {"BLOCK", "lambda$blocks"}};
            for (String[] registry : registries) {
                onRegister.visitVarInsn(Opcodes.ALOAD, 0);
                onRegister.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/Registries",
                        registry[0], "L" + key + ";");
                onRegister.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory(),
                        Type.getType("(Ljava/lang/Object;)V"),
                        new Handle(Opcodes.H_INVOKESTATIC, REGISTRAR, registry[1], "(L" + helper + ";)V", false),
                        Type.getType("(L" + helper + ";)V"));
                onRegister.visitMethodInsn(Opcodes.INVOKEVIRTUAL, event, "register",
                        "(L" + key + ";Ljava/util/function/Consumer;)V", false);
            }
            onRegister.visitInsn(Opcodes.RETURN);
            onRegister.visitMaxs(0, 0);
            onRegister.visitEnd();
            neoForgeConsumer(cw, "lambda$items", helper, ITEMS, "FOO_ITEM", item);
            neoForgeConsumer(cw, "lambda$blocks", helper, BLOCKS, "FOO", block);
        }));
        return mod;
    }

    /** {@code helper -> helper.register(ResourceLocation.fromNamespaceAndPath("xclass", "foo"), OWNER.FIELD)} */
    private static void neoForgeConsumer(ClassWriter cw, String name, String helper, String owner, String field,
                                         String type) {
        String location = "net/minecraft/resources/ResourceLocation";
        MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "(L" + helper + ";)V", null, null);
        lambda.visitCode();
        lambda.visitVarInsn(Opcodes.ALOAD, 0);
        lambda.visitLdcInsn("xclass");
        lambda.visitLdcInsn("foo");
        lambda.visitMethodInsn(Opcodes.INVOKESTATIC, location, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + location + ";", false);
        lambda.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "L" + type + ";");
        lambda.visitMethodInsn(Opcodes.INVOKEINTERFACE, helper, "register",
                "(L" + location + ";Ljava/lang/Object;)V", true);
        lambda.visitInsn(Opcodes.RETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
    }

    /** A class with one {@code public static final} field filled by {@code value} in its initializer. */
    private static byte[] holder(String name, String field, String type, Consumer<MethodVisitor> value) {
        return classWith(name, cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field,
                    "L" + type + ";", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            value.accept(init);
            init.visitFieldInsn(Opcodes.PUTSTATIC, name, field, "L" + type + ";");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        });
    }

    private static byte[] classWith(String name, Consumer<ClassWriter> members) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        members.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Handle metafactory() {
        return new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    /** The mod with a copy of itself nested, so the jar-in-jar pass is checked beside the outer one. */
    private static Path withNested(Path directory, Map<String, byte[]> mod, String nestedName) throws IOException {
        Map<String, byte[]> outer = new LinkedHashMap<>(mod);
        outer.put(nestedName, jar(mod));
        return Files.write(directory.resolve("input.jar"), jar(outer));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
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
}
