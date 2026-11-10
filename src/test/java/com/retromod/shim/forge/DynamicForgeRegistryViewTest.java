/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.api.forge.ForgeRegistryApiShim;
import com.retromod.shim.common.LegacyEnchantmentBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 dropped ForgeRegistries.ENCHANTMENTS; old reads get a view over the world's enchantment registry. */
class DynamicForgeRegistryViewTest {

    private static final String VIEW = ForgeRegistryApiShim.DYNAMIC_REGISTRY_VIEW;
    private static final String FORGE_REGISTRIES = "net/minecraftforge/registries/ForgeRegistries";
    private static final String DEFERRED_REGISTER = "net/minecraftforge/registries/DeferredRegister";
    private static final String L_FORGE_REGISTRY = "Lnet/minecraftforge/registries/IForgeRegistry;";
    private static final String CREATE = "(" + L_FORGE_REGISTRY + "Ljava/lang/String;)L" + DEFERRED_REGISTER + ";";
    private static final String HOST_CREATE = DEFERRED_REGISTER + ".create" + CREATE;
    private static final String OLD_JAR = "com/retromod/embedded/oldjar/";
    private static final String OTHER_JAR = "com/retromod/embedded/otherjar/";
    private static final String OLD_RANGE = "[1.20.1,1.20.2)";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path tempDir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    // ---- transform ----

    @Test
    @DisplayName("A static initializer's ForgeRegistries.ENCHANTMENTS read calls the view, and DeferredRegister.create goes to the view's factory")
    void staticInitializerReadsTheView() {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        List<String> calls = clinitCalls(transformer.transformClass(enchantmentsClass(), "mod/Enchants"));
        assertEquals(List.of(VIEW + ".enchantments()" + L_FORGE_REGISTRY, VIEW + ".createRegister" + CREATE), calls,
                "a register built from the view must reach the optional factory");
        assertTrue(transformer.getSyntheticClasses().containsKey(VIEW), "the view is embedded with the mod");
        assertTrue(transformer.getSyntheticClasses().containsKey(com.retromod.shim.common.LegacyEnchantmentRegistryShim.HELPER),
                "the view finds the world's registry through BuiltInRegistries.ENCHANTMENT's view, which travels with it");
        assertTrue(transformer.getSyntheticClasses().containsKey("com/retromod/shim/common/embedded/LegacyLog"),
                "the view logs a refused registration through LegacyLog");
    }

    @Test
    @DisplayName("An old mod's create on any registry goes to the view's factory, and a mod built for Forge 26.2 keeps the host's")
    void createIsLimitedToOldMods() {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        // The factory reads the argument at run time, so an old mod's create on ITEMS goes through it too.
        transformer.setModSourceVersion("[1.20.1,1.20.2)");
        assertEquals(List.of("GETSTATIC ITEMS", VIEW + ".createRegister" + CREATE),
                clinitCalls(transformer.transformClass(registerClass("mod/Items", "ITEMS"), "mod/Items")));

        transformer.setModSourceVersion("[26.2,)");
        assertEquals(List.of("GETSTATIC ITEMS", HOST_CREATE),
                clinitCalls(transformer.transformClass(registerClass("mod/NativeItems", "ITEMS"), "mod/NativeItems")),
                "a mod written for a host without ForgeRegistries.ENCHANTMENTS calls the host's create as written");
    }

    @Test
    @DisplayName("Forge 26.1, NeoForge and Fabric targets leave ForgeRegistries.ENCHANTMENTS to their own handling")
    void otherHostsRegisterNoView() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertNoView("Forge 26.1 is not checked to lack the field");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertNoView("NeoForge maps ForgeRegistries in its own shim");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertNoView("a Fabric target never takes a Forge bridge");
    }

    // ---- runtime ----

    @Test
    @DisplayName("Before a world loads, the view names the enchantment registry and finds no entries")
    void beforeAWorld() throws Exception {
        Host host = host("");
        Object view = host.view();
        assertSame(host.registryKey(), call(view, "getRegistryKey"), "DeferredRegister.create reads only this key");
        assertEquals("minecraft:enchantment", call(view, "getRegistryName").toString());
        assertNull(call(view, "getValue", host.id("minecraft:sharpness")));
        assertEquals(true, call(view, "isEmpty"));
        assertEquals(Set.of(), call(view, "getKeys"));
        assertEquals(List.of(), List.copyOf((Collection<?>) call(view, "getValues")));
        assertSame(view, host.view(), "every read of the field is the same registry object");
    }

    @Test
    @DisplayName("With a world loaded, lookups by id, by value and by key read the world's registry")
    void lookupsReadTheWorld() throws Exception {
        Host host = host("");
        host.startWorld("minecraft:sharpness", "minecraft:mending");
        Object view = host.view();
        Object sharpness = call(view, "getValue", host.id("minecraft:sharpness"));
        assertEquals("value minecraft:sharpness", String.valueOf(sharpness));
        assertEquals("minecraft:sharpness", call(view, "getKey", sharpness).toString());
        assertEquals(true, call(view, "containsKey", host.id("minecraft:mending")));
        assertEquals(true, call(view, "containsValue", sharpness));
        assertEquals(false, call(view, "containsKey", host.id("mod:missing")));
        assertEquals("ResourceKey[minecraft:enchantment / minecraft:sharpness]",
                ((Optional<?>) call(view, "getResourceKey", sharpness)).orElseThrow().toString());
        assertEquals(List.of("value minecraft:sharpness", "value minecraft:mending"), strings(call(view, "getValues")));
        assertEquals(sharpness, ((Optional<?>) call(view, "getDelegate", host.id("minecraft:sharpness")))
                .map(DynamicForgeRegistryViewTest::holderValue).orElseThrow());
        assertTrue(((Optional<?>) call(view, "getHolder", sharpness)).isPresent());
        InvocationTargetException missing = assertThrows(InvocationTargetException.class,
                () -> call(view, "getDelegateOrThrow", host.id("mod:missing")));
        assertTrue(missing.getCause().getMessage().contains("data packs"), missing.getCause().getMessage());

        host.stopWorld();
        assertNull(call(view, "getKey", sharpness), "the view never keeps a closed world's registry");
    }

    @Test
    @DisplayName("During a data pack load, before any server exists, lookups read the registries Retromod recorded")
    void dataPackLoad() throws Exception {
        Host host = host("");
        Object view = host.view();
        Object registries = host.loader().loadClass("test.World").getMethod("access", String[].class)
                .invoke(null, (Object) new String[] {"minecraft:sharpness"});
        String slot = com.retromod.shim.common.embedded.LegacyEnchantmentRegistry.RELOAD_SLOT;
        System.getProperties().put(slot, new java.util.concurrent.atomic.AtomicReference<Object>(
                new java.lang.ref.WeakReference<>(registries)));
        try {
            assertEquals("value minecraft:sharpness", String.valueOf(call(view, "getValue", host.id("minecraft:sharpness"))));
            assertEquals(false, call(view, "isEmpty"));
        } finally {
            System.getProperties().remove(slot);
        }
        assertNotNull(registries, "held until here, as the load holds its registries while it runs");
    }

    @Test
    @DisplayName("register logs once and binds nothing")
    void registerLogsOnceAndBindsNothing() throws Exception {
        Host host = host("");
        host.startWorld("minecraft:sharpness");
        Object view = host.view();
        String printed = captureErr(() -> {
            call(view, "register", host.id("mod:custom"), host.value("mod:custom"));
            call(view, "register", host.id("mod:other"), host.value("mod:other"));
            view.getClass().getMethod("register", String.class, Object.class).invoke(view, "third", host.value("third"));
        });
        assertEquals(1, printed.lines().filter(line -> line.contains("from code")).count(), printed);
        assertTrue(printed.contains("mod:custom") && printed.contains("minecraft:enchantment"), printed);
        assertNull(call(view, "getValue", host.id("mod:custom")));
        assertEquals(List.of("value minecraft:sharpness"), strings(call(view, "getValues")));
    }

    @Test
    @DisplayName("createRegister makes a register over any jar's view optional, and leaves every other registry to the host's create")
    void createRegisterPicksTheFactory() throws Exception {
        Host host = host(OLD_JAR, OTHER_JAR);
        Object view = host.view();
        List<String> registers = new ArrayList<>();
        String printed = captureErr(() -> {
            registers.add(host.createRegister(OLD_JAR, view, "enchmod"));
            registers.add(host.createRegister(OLD_JAR, view, "enchmod"));
            registers.add(host.createRegister(OTHER_JAR, view, "libmod"));
            registers.add(host.createRegister(OLD_JAR, host.otherRegistry(), "enchmod"));
        });
        String optional = "createOptional ResourceKey[minecraft:root / minecraft:enchantment] ";
        assertEquals(List.of(optional + "enchmod", optional + "enchmod", optional + "libmod", "create other registry enchmod"),
                registers, "an optional register skips Forge's check for a registry it does not know");
        assertEquals(1, printed.lines().filter(line -> line.contains("Mod enchmod")).count(), printed);
        assertTrue(printed.contains("Registry Object not present"), "the log names the error a later get() throws: " + printed);
    }

    @Test
    @DisplayName("getCodec writes an entry as its id and reads ids from the world loaded when it runs")
    void codecReadsTheLoadedWorld() throws Exception {
        Host host = host("");
        Object view = host.view();
        Object codec = call(view, "getCodec");
        assertSame(codec, call(view, "getCodec"), "one codec, as the old registry kept");
        Function<Object, Object> decode = codecSide(codec, "to");
        Function<Object, Object> encode = codecSide(codec, "from");
        assertTrue(decode.apply(host.id("minecraft:sharpness")).toString().startsWith("error No minecraft:sharpness"),
                "before a world loads, an id has nothing to read");

        host.startWorld("minecraft:sharpness");
        Object sharpness = call(view, "getValue", host.id("minecraft:sharpness"));
        assertEquals("success value minecraft:sharpness", decode.apply(host.id("minecraft:sharpness")).toString());
        assertEquals("success minecraft:sharpness", encode.apply(sharpness).toString());
        assertTrue(encode.apply(host.value("mod:custom")).toString().startsWith("error"),
                "an entry the world does not hold has no id to write");
    }

    @Test
    @DisplayName("In a jar written against the old Enchantment class, entries come back as that jar's stand-ins")
    void oldJarSeesItsStandIns() throws Exception {
        Host host = host(OLD_JAR);
        Object view = host.view();
        Object mending = host.standIn("minecraft:mending");
        assertEquals("minecraft:mending", call(view, "getKey", mending).toString(),
                "a stand-in names its id before any world loads, as Enchantments.MENDING does");

        host.startWorld("minecraft:sharpness", "minecraft:mending");
        Object sharpness = call(view, "getValue", host.id("minecraft:sharpness"));
        assertEquals("stand-in minecraft:sharpness", String.valueOf(sharpness));
        assertEquals(List.of("stand-in minecraft:sharpness", "stand-in minecraft:mending"),
                strings(call(view, "getValues")));
        assertEquals(true, call(view, "containsValue", mending));
        assertEquals(false, call(view, "containsValue", host.standIn("mod:gone")));
    }

    @Test
    @DisplayName("The generated enchantment stand-in keeps the name and members the view looks for")
    void standInContract() throws Exception {
        LegacyEnchantmentBridge.registerSynthetics(transformer);
        ClassNode standIn = node(transformer.getSyntheticClasses().get(LegacyEnchantmentBridge.LEGACY));
        assertTrue(standIn.methods.stream().anyMatch(m -> m.name.equals("fromKey")
                && m.desc.equals("(Lnet/minecraft/resources/ResourceKey;)L" + LegacyEnchantmentBridge.LEGACY + ";")
                && (m.access & ACC_STATIC) != 0 && (m.access & ACC_PUBLIC) != 0));
        assertTrue(standIn.fields.stream().anyMatch(f -> f.name.equals("id") && f.desc.equals("Ljava/lang/String;")
                && (f.access & ACC_PUBLIC) != 0 && (f.access & ACC_STATIC) == 0));

        ForgeRegistryApiShim.registerForgeEnchantmentView(transformer);
        String viewText = new String(transformer.getSyntheticClasses().get(VIEW), StandardCharsets.ISO_8859_1);
        assertTrue(viewText.contains(LegacyEnchantmentBridge.LEGACY.replace('/', '.')),
                "the view looks for the stand-in under the name the bridge generates");
    }

    // ---- every transform path ----

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI transform, CLI batch and AOT paths send the read to the view embedded in the same jar, outer and nested")
    void everyPathCallsTheEmbeddedView(String target, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("enchmod.jar");
        Files.write(input, modJar(OLD_RANGE, "ENCHANTMENTS"));

        for (Map.Entry<String, Path> output : everyPath(input, dir, target).entrySet()) {
            try (JarFile jar = new JarFile(output.getValue().toFile())) {
                assertCallsEmbeddedView(jar, "mod/Enchants", output.getKey());
                try (JarFile lib = new JarFile(nestedLib(jar, dir).toFile())) {
                    assertCallsEmbeddedView(lib, "fixturelib/LibEnchants", output.getKey() + ", nested");
                }
            }
        }
    }

    @Test
    @DisplayName("On every path, a mod built for Forge 26.2 keeps the host's DeferredRegister.create, outer and nested")
    void everyPathLeavesNewerModsTheHostCreate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("enchmod.jar");
        Files.write(input, modJar("[26.2,)", "ITEMS"));

        for (Map.Entry<String, Path> output : everyPath(input, dir, "26.3").entrySet()) {
            try (JarFile jar = new JarFile(output.getValue().toFile())) {
                assertKeepsHostCreate(jar, "mod/Enchants", output.getKey());
                try (JarFile lib = new JarFile(nestedLib(jar, dir).toFile())) {
                    assertKeepsHostCreate(lib, "fixturelib/LibEnchants", output.getKey() + ", nested");
                }
            }
        }
    }

    // ---- helpers: transform ----

    private void assertNoView(String why) {
        RetromodTransformer.FieldTarget target =
                transformer.getFieldRedirects().get(new RetromodTransformer.FieldKey(FORGE_REGISTRIES, "ENCHANTMENTS"));
        assertTrue(target == null || !target.owner().equals(VIEW), why);
        RetromodTransformer.MethodTarget create =
                transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(DEFERRED_REGISTER, "create", CREATE));
        assertTrue(create == null || !create.owner().equals(VIEW), why + ": DeferredRegister.create stays the host's");
        assertFalse(transformer.getSyntheticClasses().containsKey(VIEW), why);
        transformer.clearRedirectsForTesting();
    }

    /** {@code static final DeferredRegister REG = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, "enchmod");} */
    private static byte[] enchantmentsClass() {
        return registerClass("mod/Enchants", "ENCHANTMENTS");
    }

    /** {@code static final DeferredRegister REG = DeferredRegister.create(ForgeRegistries.<field>, "enchmod");} */
    private static byte[] registerClass(String name, String field) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_STATIC | ACC_FINAL, "REG", "L" + DEFERRED_REGISTER + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(GETSTATIC, FORGE_REGISTRIES, field, L_FORGE_REGISTRY);
        clinit.visitLdcInsn("enchmod");
        clinit.visitMethodInsn(INVOKESTATIC, DEFERRED_REGISTER, "create", CREATE, false);
        clinit.visitFieldInsn(PUTSTATIC, name, "REG", "L" + DEFERRED_REGISTER + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The registry reads and register factory calls in a static initializer, leaving out the id stamper's wrapping calls. */
    private static List<String> clinitCalls(byte[] bytes) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node(bytes), "<clinit>").instructions) {
            if (insn instanceof MethodInsnNode call && (call.owner.endsWith(VIEW) || call.owner.equals(DEFERRED_REGISTER))) {
                calls.add(call.owner + "." + call.name + call.desc);
            }
            if (insn instanceof FieldInsnNode read && read.owner.equals(FORGE_REGISTRIES)) calls.add("GETSTATIC " + read.name);
        }
        return calls;
    }

    private static void assertCallsEmbeddedView(JarFile jar, String className, String path) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is in the " + path + " output");
        List<String> calls = clinitCalls(jar.getInputStream(entry).readAllBytes());
        String viewCall = calls.stream().filter(call -> call.contains(VIEW + ".enchantments")).findFirst()
                .orElseThrow(() -> new AssertionError(path + ": no call to the view in " + calls));
        String owner = viewCall.substring(0, viewCall.indexOf(".enchantments"));
        assertTrue(owner.startsWith(SyntheticEmbedder.PREFIX), path + ": the view is embedded, not shared: " + owner);
        assertNotNull(jar.getJarEntry(owner + ".class"), path + ": the jar carries its own view");
        String embeddedRoot = owner.substring(0, owner.length() - VIEW.length());
        assertNotNull(jar.getJarEntry(embeddedRoot + com.retromod.shim.common.LegacyEnchantmentRegistryShim.HELPER + ".class"),
                path + ": the jar carries the view of the world's registry it reads through");
        assertNotNull(jar.getJarEntry(embeddedRoot + "com/retromod/shim/common/embedded/LegacyLog.class"),
                path + ": the jar carries the log helper a refused registration goes through");
        assertFalse(calls.contains("GETSTATIC ENCHANTMENTS"), path + ": the removed field is no longer read");
        // The register is built by the jar's own view, which takes the host's IForgeRegistry and returns its DeferredRegister.
        assertEquals(List.of(owner + ".enchantments()" + L_FORGE_REGISTRY, owner + ".createRegister" + CREATE), calls,
                path + ": DeferredRegister.create goes to the jar's own view factory");
        ClassNode view = node(jar.getInputStream(jar.getJarEntry(owner + ".class")).readAllBytes());
        assertTrue(view.interfaces.contains("net/minecraftforge/registries/IForgeRegistry"),
                path + ": the view implements the host's IForgeRegistry: " + view.interfaces);
        List<String> factoryCalls = new ArrayList<>();
        for (AbstractInsnNode insn : method(view, "createRegister").instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(DEFERRED_REGISTER)) {
                factoryCalls.add(call.name + call.desc);
            }
        }
        assertEquals(List.of("create" + CREATE, "createOptional(Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)L"
                + DEFERRED_REGISTER + ";"), factoryCalls, path + ": the factory calls the host's two DeferredRegister factories");
        assertTrue(jar.stream().noneMatch(e -> e.getName().endsWith("net/minecraftforge/registries/IForgeRegistry.class")),
                path + ": no embedded IForgeRegistry shadows the host's");
    }

    /** What RetromodForge.loadForgeShims registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    /** The Forge runtime, CLI transform, CLI batch and AOT outputs of one input jar. */
    private Map<String, Path> everyPath(Path input, Path dir, String target) throws Exception {
        Map<String, Path> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", forgeRuntime(input, dir, target));
        Path transformed = dir.resolve("transform.jar");
        CliHarness.transform(input, transformed, target);
        outputs.put("CLI transform", transformed);
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve(input.getFileName()));
        CliHarness.batch(batchIn, batchOut, target);
        outputs.put("CLI batch", onlyJar(batchOut));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, target);
        outputs.put("AOT", aot);
        return outputs;
    }

    private static Path nestedLib(JarFile jar, Path dir) throws Exception {
        Path nested = Files.createTempFile(dir, "nested-", ".jar");
        Files.write(nested, jar.getInputStream(jar.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
        return nested;
    }

    private static void assertKeepsHostCreate(JarFile jar, String className, String path) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is in the " + path + " output");
        assertEquals(List.of("GETSTATIC ITEMS", HOST_CREATE), clinitCalls(jar.getInputStream(entry).readAllBytes()),
                path + ": a mod built for Forge 26.2 calls the host's create as written");
        assertTrue(jar.stream().noneMatch(e -> e.getName().endsWith(VIEW + ".class")),
                path + ": nothing in the jar needs the view");
    }

    /** A Forge mod for {@code range} whose static initializer builds a register on {@code field}, with a nested library that does too. */
    private static byte[] modJar(String range, String field) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib", range));
        lib.put("fixturelib/LibEnchants.class", registerClass("fixturelib/LibEnchants", field));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("enchmod", range));
        mod.put("mod/Enchants.class", registerClass("mod/Enchants", field));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    private static byte[] toml(String modId, String range) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"" + range + "\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
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

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    // ---- helpers: runtime ----

    /** The view, loaded beside small Forge 26.2 stand-ins, in a mod jar under {@code prefix} like the embedder writes, plus other jars' copies. */
    private Host host(String prefix, String... otherCopies) throws Exception {
        ForgeRegistryApiShim.registerForgeEnchantmentView(transformer);
        Map<String, byte[]> embedded = new HashMap<>();
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        for (String copy : copies(prefix, otherCopies)) {
            synthetics.forEach((name, bytes) -> embedded.put(copy + name, relocate(bytes, copy, synthetics.keySet())));
        }
        ForgeHostStandIns standIns = hostStandIns();
        if (!prefix.isEmpty()) {
            String pkg = (prefix + "com/retromod/generated").replace('/', '.');
            standIns.add(prefix + "com/retromod/generated/LegacyEnchantment.java", """
                    package %s;
                    public class LegacyEnchantment {
                        public String id;
                        public LegacyEnchantment(String id) { this.id = id; }
                        public static LegacyEnchantment fromKey(net.minecraft.resources.ResourceKey<?> key) {
                            return new LegacyEnchantment(key.identifier().toString());
                        }
                        public String toString() { return "stand-in " + id; }
                    }""".formatted(pkg));
        }
        ClassLoader loader = standIns.loader(tempDir.resolve("host-" + prefix.hashCode()), transformer, embedded);
        return new Host(loader, prefix);
    }

    private static List<String> copies(String prefix, String... otherCopies) {
        List<String> copies = new ArrayList<>(List.of(otherCopies));
        if (!prefix.isEmpty()) copies.add(prefix);
        return copies;
    }

    private static byte[] relocate(byte[] bytes, String prefix, Set<String> synthetics) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                return synthetics.contains(internalName) ? prefix + internalName : internalName;
            }
        }), 0);
        return writer.toByteArray();
    }

    /** One loaded host: the view, the registry key, and a server whose world holds some enchantments. */
    private record Host(ClassLoader loader, String prefix) {
        Object view() throws Exception {
            return loader.loadClass((prefix + VIEW).replace('/', '.')).getMethod("enchantments").invoke(null);
        }

        Object registryKey() throws Exception {
            return loader.loadClass("net.minecraft.core.registries.Registries").getField("ENCHANTMENT").get(null);
        }

        /** What the static factory in the copy under {@code copy} builds, as the register stand-in names it. */
        String createRegister(String copy, Object registry, String modid) throws Exception {
            Class<?> forgeRegistry = loader.loadClass("net.minecraftforge.registries.IForgeRegistry");
            return String.valueOf(loader.loadClass((copy + VIEW).replace('/', '.'))
                    .getMethod("createRegister", forgeRegistry, String.class).invoke(null, registry, modid));
        }

        Object otherRegistry() throws Exception {
            return loader.loadClass("test.OtherRegistry").getConstructor().newInstance();
        }

        Object id(String id) throws Exception {
            return loader.loadClass("net.minecraft.resources.Identifier").getMethod("tryParse", String.class).invoke(null, id);
        }

        Object value(String id) throws Exception {
            return loader.loadClass("test.World").getMethod("value", String.class).invoke(null, id);
        }

        Object standIn(String id) throws Exception {
            return loader.loadClass((prefix + "com/retromod/generated/LegacyEnchantment").replace('/', '.'))
                    .getConstructor(String.class).newInstance(id);
        }

        void startWorld(String... ids) throws Exception {
            loader.loadClass("test.World").getMethod("start", String[].class).invoke(null, (Object) ids);
        }

        void stopWorld() throws Exception {
            loader.loadClass("test.World").getMethod("stop").invoke(null);
        }
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length
                    || Modifier.isStatic(method.getModifiers()) || method.isBridge()) continue;
            if (fits(method.getParameterTypes(), arguments)) return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(name);
    }

    // Overloads differ by type (Identifier, ResourceKey, or a value), so the argument picks the method.
    private static boolean fits(Class<?>[] types, Object[] arguments) {
        for (int i = 0; i < types.length; i++) {
            if (arguments[i] == null) continue;
            boolean exact = types[i].isInstance(arguments[i]) && types[i] != Object.class;
            boolean value = types[i] == Object.class && !arguments[i].getClass().getName().startsWith("net.minecraft.resources.");
            if (!exact && !value) return false;
        }
        return true;
    }

    // The stand-in codec keeps the two functions the view passed to flatXmap.
    @SuppressWarnings("unchecked")
    private static Function<Object, Object> codecSide(Object codec, String side) throws Exception {
        return (Function<Object, Object>) codec.getClass().getMethod(side).invoke(codec);
    }

    private static Object holderValue(Object holder) {
        try {
            return holder.getClass().getMethod("value").invoke(holder);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> strings(Object values) {
        List<String> out = new ArrayList<>();
        for (Object value : (Iterable<?>) values) out.add(String.valueOf(value));
        return out;
    }

    private interface Action {
        void run() throws Exception;
    }

    private static String captureErr(Action action) throws Exception {
        PrintStream saved = System.err;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(saved);
        }
        return printed.toString(StandardCharsets.UTF_8);
    }

    /** Working stand-ins for the Forge 26.2 and Minecraft types the view calls, with a map-backed registry. */
    private static ForgeHostStandIns hostStandIns() {
        return new ForgeHostStandIns()
                .add("net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public final class Identifier {
                            public static final com.mojang.serialization.Codec<Identifier> CODEC =
                                    new com.mojang.serialization.Codec<>() {};
                            private final String id;
                            private Identifier(String id) { this.id = id; }
                            public static Identifier fromNamespaceAndPath(String namespace, String path) {
                                return new Identifier(namespace + ":" + path);
                            }
                            public static Identifier tryParse(String id) { return new Identifier(id); }
                            public boolean equals(Object other) { return other instanceof Identifier o && o.id.equals(id); }
                            public int hashCode() { return id.hashCode(); }
                            public String toString() { return id; }
                        }""")
                .add("net/minecraft/resources/ResourceKey.java", """
                        package net.minecraft.resources;
                        public final class ResourceKey<T> {
                            private final Identifier registry;
                            private final Identifier location;
                            private ResourceKey(Identifier registry, Identifier location) {
                                this.registry = registry;
                                this.location = location;
                            }
                            public static <T> ResourceKey<T> create(ResourceKey<? extends net.minecraft.core.Registry<T>> registry,
                                    Identifier location) {
                                return new ResourceKey<>(registry.location, location);
                            }
                            public static <T> ResourceKey<net.minecraft.core.Registry<T>> createRegistryKey(Identifier location) {
                                return new ResourceKey<>(Identifier.tryParse("minecraft:root"), location);
                            }
                            public Identifier identifier() { return location; }
                            public boolean equals(Object other) {
                                return other instanceof ResourceKey<?> o && o.registry.equals(registry) && o.location.equals(location);
                            }
                            public int hashCode() { return registry.hashCode() * 31 + location.hashCode(); }
                            public String toString() { return "ResourceKey[" + registry + " / " + location + "]"; }
                        }""")
                .add("net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        public interface Holder<T> {
                            T value();
                            class Reference<T> implements Holder<T> {
                                private final T value;
                                public Reference(T value) { this.value = value; }
                                public T value() { return value; }
                            }
                        }""")
                .add("net/minecraft/core/Registry.java", """
                        package net.minecraft.core;
                        import java.util.*;
                        import net.minecraft.resources.*;
                        public interface Registry<T> extends Iterable<T> {
                            T getValue(Identifier id);
                            Identifier getKey(T value);
                            boolean containsKey(Identifier id);
                            Set<Identifier> keySet();
                            Set<Map.Entry<ResourceKey<T>, T>> entrySet();
                            Optional<Holder.Reference<T>> get(ResourceKey<T> key);
                            Optional<Holder.Reference<T>> get(Identifier id);
                            int size();
                        }""")
                .add("net/minecraft/core/RegistryAccess.java", """
                        package net.minecraft.core;
                        public interface RegistryAccess {
                            <E> java.util.Optional<Registry<E>> lookup(
                                    net.minecraft.resources.ResourceKey<? extends Registry<? extends E>> key);
                            interface Frozen extends RegistryAccess {}
                        }""")
                .add("net/minecraft/core/registries/Registries.java", """
                        package net.minecraft.core.registries;
                        import net.minecraft.resources.*;
                        public final class Registries {
                            public static final ResourceKey<net.minecraft.core.Registry<Object>> ENCHANTMENT =
                                    ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("minecraft", "enchantment"));
                        }""")
                .add("net/minecraft/server/MinecraftServer.java", """
                        package net.minecraft.server;
                        public class MinecraftServer {
                            private final net.minecraft.core.RegistryAccess.Frozen access;
                            public MinecraftServer(net.minecraft.core.RegistryAccess.Frozen access) { this.access = access; }
                            public net.minecraft.core.RegistryAccess.Frozen registryAccess() { return access; }
                        }""")
                .add("net/minecraftforge/server/ServerLifecycleHooks.java", """
                        package net.minecraftforge.server;
                        public class ServerLifecycleHooks {
                            public static net.minecraft.server.MinecraftServer current;
                            public static net.minecraft.server.MinecraftServer getCurrentServer() { return current; }
                        }""")
                .add("net/minecraftforge/api/distmarker/Dist.java",
                        "package net.minecraftforge.api.distmarker; public enum Dist { CLIENT, DEDICATED_SERVER }")
                .add("net/minecraftforge/fml/loading/FMLEnvironment.java", """
                        package net.minecraftforge.fml.loading;
                        public class FMLEnvironment {
                            public static final net.minecraftforge.api.distmarker.Dist dist =
                                    net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER;
                        }""")
                .add("net/minecraftforge/registries/IForgeRegistry.java", """
                        package net.minecraftforge.registries;
                        public interface IForgeRegistry<V> extends Iterable<V> {
                            net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<V>> getRegistryKey();
                            net.minecraft.resources.Identifier getRegistryName();
                            record SlaveKey<T>(net.minecraft.resources.Identifier name) {}
                        }""")
                .add("net/minecraftforge/registries/DeferredRegister.java", """
                        package net.minecraftforge.registries;
                        public class DeferredRegister<T> {
                            private final String text;
                            private DeferredRegister(String text) { this.text = text; }
                            public static <B> DeferredRegister<B> create(IForgeRegistry<B> registry, String modid) {
                                return new DeferredRegister<>("create " + registry + " " + modid);
                            }
                            public static <B> DeferredRegister<B> createOptional(
                                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<B>> key,
                                    String modid) {
                                return new DeferredRegister<>("createOptional " + key + " " + modid);
                            }
                            public String toString() { return text; }
                        }""")
                .add("test/OtherRegistry.java", """
                        package test;
                        public final class OtherRegistry implements net.minecraftforge.registries.IForgeRegistry<Object> {
                            public net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<Object>> getRegistryKey() {
                                return null;
                            }
                            public net.minecraft.resources.Identifier getRegistryName() {
                                return net.minecraft.resources.Identifier.tryParse("test:other");
                            }
                            public java.util.Iterator<Object> iterator() { return java.util.Collections.emptyIterator(); }
                            public String toString() { return "other registry"; }
                        }""")
                .add("net/minecraftforge/registries/tags/ITagManager.java",
                        "package net.minecraftforge.registries.tags; public interface ITagManager<V> {}")
                .add("com/mojang/serialization/Codec.java", """
                        package com.mojang.serialization;
                        import java.util.function.Function;
                        public interface Codec<A> {
                            default <S> Codec<S> flatXmap(Function<? super A, ? extends DataResult<? extends S>> to,
                                    Function<? super S, ? extends DataResult<? extends A>> from) {
                                return new Mapped<>(to, from);
                            }
                            record Mapped<S>(Function<?, ?> to, Function<?, ?> from) implements Codec<S> {}
                        }""")
                .add("com/mojang/serialization/DataResult.java", """
                        package com.mojang.serialization;
                        public interface DataResult<R> {
                            static <R> DataResult<R> success(R value) { return new Result<>("success " + value); }
                            static <R> DataResult<R> error(java.util.function.Supplier<String> message) {
                                return new Result<>("error " + message.get());
                            }
                            record Result<R>(String text) implements DataResult<R> {
                                public String toString() { return text; }
                            }
                        }""")
                .add("test/World.java", """
                        package test;
                        import java.util.*;
                        import net.minecraft.core.*;
                        import net.minecraft.core.registries.Registries;
                        import net.minecraft.resources.*;
                        import net.minecraftforge.server.ServerLifecycleHooks;
                        public final class World {
                            public record Value(String id) {
                                public String toString() { return "value " + id; }
                            }
                            public static Object value(String id) { return new Value(id); }
                            public static void start(String... ids) {
                                ServerLifecycleHooks.current = new net.minecraft.server.MinecraftServer(access(ids));
                            }
                            public static RegistryAccess.Frozen access(String... ids) {
                                Map<Identifier, Object> entries = new LinkedHashMap<>();
                                for (String id : ids) entries.put(Identifier.tryParse(id), new Value(id));
                                Registry<Object> registry = new Registry<>() {
                                    public Object getValue(Identifier id) { return entries.get(id); }
                                    public Identifier getKey(Object value) {
                                        for (Map.Entry<Identifier, Object> e : entries.entrySet()) {
                                            if (e.getValue().equals(value)) return e.getKey();
                                        }
                                        return null;
                                    }
                                    public boolean containsKey(Identifier id) { return entries.containsKey(id); }
                                    public Set<Identifier> keySet() { return entries.keySet(); }
                                    public Set<Map.Entry<ResourceKey<Object>, Object>> entrySet() {
                                        Set<Map.Entry<ResourceKey<Object>, Object>> out = new LinkedHashSet<>();
                                        entries.forEach((id, v) -> out.add(Map.entry(ResourceKey.create(Registries.ENCHANTMENT, id), v)));
                                        return out;
                                    }
                                    public Optional<Holder.Reference<Object>> get(ResourceKey<Object> key) {
                                        return get(key.identifier());
                                    }
                                    public Optional<Holder.Reference<Object>> get(Identifier id) {
                                        return Optional.ofNullable(entries.get(id)).map(Holder.Reference::new);
                                    }
                                    public int size() { return entries.size(); }
                                    public Iterator<Object> iterator() { return entries.values().iterator(); }
                                };
                                RegistryAccess.Frozen access = new RegistryAccess.Frozen() {
                                    @SuppressWarnings("unchecked")
                                    public <E> Optional<Registry<E>> lookup(ResourceKey<? extends Registry<? extends E>> key) {
                                        return key.equals(Registries.ENCHANTMENT)
                                                ? Optional.of((Registry<E>) (Registry<?>) registry) : Optional.empty();
                                    }
                                };
                                return access;
                            }
                            public static void stop() { ServerLifecycleHooks.current = null; }
                        }""");
    }
}
