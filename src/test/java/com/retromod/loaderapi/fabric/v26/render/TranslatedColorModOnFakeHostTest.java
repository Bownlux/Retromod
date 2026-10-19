/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.loaderapi.fabric.v26.net.FakeHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old 1.20.1 render layer and color mods, translated by the CLI, run on the fake 26.x host. */
class TranslatedColorModOnFakeHostTest {

    private static final String API = "net/fabricmc/fabric/api/";
    private static final String COLORS_API = API + "client/rendering/v1/ColorProviderRegistry";
    private static final String LAYERS_API = API + "blockrenderlayer/v1/BlockRenderLayerMap";
    private static final String STATIC_LAYERS_API = API + "client/rendering/v1/BlockRenderLayerMap";
    private static final String BLOCK = "Lnet/minecraft/class_2248;";
    private static final String ITEM_LIKE = "Lnet/minecraft/class_1935;";
    private static final String BLOCK_COLOR = "net/minecraft/class_322";
    private static final String ITEM_COLOR = "net/minecraft/class_326";
    private static final String BLOCK_SAM = "(Lnet/minecraft/class_2680;Lnet/minecraft/class_1920;Lnet/minecraft/class_2338;I)I";
    private static final String ITEM_SAM = "(Lnet/minecraft/class_1799;I)I";
    private static final String MOD = "fixture/r1mod/Colors";
    private static final String MOD_COLOR = "fixture/r1mod/OwnColor";
    private static final String LIB = "fixture/r1lib/Renderers";
    private static final String DEP = "fixture/r1dep/Main";
    private static final String DEP_COLOR = "fixture/r1dep/DepColor";
    private static final String LAYERS_MOD = "fixture/r1layers/Layers";
    private static final String SECTION_LAYER = "net/minecraft/class_11515";

    private AutoCloseable runtime;
    private String savedHost;

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("The host's block colors ask an old block color provider, tint index by tint index")
    void blockColorsReachTheHost(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1mod", Map.of(MOD + ".class", colorsClass(),
                MOD_COLOR + ".class", ownColor()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object block = newBlock(fake, "leafy");
            mod.getField("BLOCK").set(null, block);
            run(mod, "registerBlock");

            Object state = invoke(block, "defaultBlockState");
            List<?> sources = tintSources(fake, state);
            assertEquals(128, sources.size(), "one host tint source per tint index");
            Object level = fake.type("fixture.FakeLevel").getConstructor().newInstance();
            Object pos = fake.type("net.minecraft.core.BlockPos").getConstructor(int.class).newInstance(1);
            // Outside a world the old provider gets no level or position, and the color turns opaque.
            assertEquals(0xFF112200, invoke(sources.get(0), "color", state));
            assertEquals(0xFF113303, invoke(sources.get(3), "colorInWorld", state, level, pos));
            assertEquals(0xFF113300 + 107, invoke(sources.get(107), "colorAsTerrainParticle", state, level, pos));

            // get() answers the provider the mod registered, which the mod can call through the old type.
            assertEquals(Boolean.TRUE, run(mod, "getReturnsTheSameProvider"));
            assertEquals(0x112202, run(mod, "callThroughTheOldType"));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("An old get() of a host block, registered for a mod block, shares the host's tint sources")
    void hostColorsCopyToAModBlock(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1mod", Map.of(MOD + ".class", colorsClass(),
                MOD_COLOR + ".class", ownColor()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object vanilla = newBlock(fake, "oak_leaves");
            Object modded = newBlock(fake, "mod_leaves");
            Object constant = fake.type("fixture.ConstantTint").getConstructor(int.class).newInstance(0xFF48B518);
            Object colors = blockColors(fake);
            Object blocks = java.lang.reflect.Array.newInstance(fake.type("net.minecraft.world.level.block.Block"), 1);
            java.lang.reflect.Array.set(blocks, 0, vanilla);
            colors.getClass().getMethod("register", List.class, blocks.getClass()).invoke(colors, List.of(constant), blocks);
            mod.getField("BLOCK").set(null, modded);
            mod.getField("SOURCE").set(null, vanilla);

            run(mod, "copyColorFromSource");
            List<?> sources = tintSources(fake, invoke(modded, "defaultBlockState"));
            assertEquals(List.of(constant), sources, "the mod block reuses the host's own tint source");
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("A baked item model gets one tint per layer that asks the old item color")
    void itemColorsTintItemModels(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1mod", Map.of(MOD + ".class", colorsClass(),
                MOD_COLOR + ".class", ownColor()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object item = fake.type("net.minecraft.world.item.Item").getConstructor(String.class).newInstance("gem");
            Object itemId = id(fake, "r1mod:gem");
            Object registry = fake.type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
            registry.getClass().getMethod("put", itemId.getClass(), Object.class).invoke(registry, itemId, item);
            mod.getField("ITEM").set(null, item);
            run(mod, "registerItem");
            resources(fake).put("r1mod:models/item/gem.json",
                    "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"r1mod:item/gem\",\"layer1\":\"r1mod:item/gem_overlay\"}}");
            resources(fake).put("minecraft:models/item/generated.json", "{\"parent\":\"builtin/generated\"}");

            Object model = bakeItem(fake, itemId, id(fake, "r1mod:item/gem"));
            List<?> tints = (List<?>) invoke(model, "tints");
            assertEquals(2, tints.size(), "one tint per generated layer");
            Object stack = fake.type("net.minecraft.world.item.ItemStack")
                    .getConstructor(fake.type("net.minecraft.world.item.Item")).newInstance(item);
            assertEquals(0xFF00AA01, invoke(tints.get(1), "calculate", stack, null, null));
            assertSame(item, run(mod, "itemProviderOwner"), "ITEM.get() answers for the item");
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("Old render layer calls of both shapes are recorded on the host")
    void renderLayersAreRecorded(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1mod", Map.of(MOD + ".class", colorsClass(),
                MOD_COLOR + ".class", ownColor()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object block = newBlock(fake, "glassy");
            Object pane = newBlock(fake, "pane");
            mod.getField("BLOCK").set(null, block);
            mod.getField("SOURCE").set(null, pane);
            run(mod, "layers");
            Class<?> layers = fake.type("com.retromod.loaderapi.fabric.v26.render.LegacyRenderLayers");
            assertEquals(Boolean.TRUE, layers.getMethod("hasLayer", Object.class).invoke(null, block));
            assertEquals(Boolean.TRUE, layers.getMethod("hasLayer", Object.class).invoke(null, pane));
            assertEquals(fake.type("net.minecraft.client.renderer.chunk.ChunkSectionLayer").getEnumConstants()[1],
                    layers.getMethod("layerOf", Object.class).invoke(null, pane));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("Static layer calls that name a section layer 26.x removed still record")
    void removedSectionLayersRecord(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1layers", "1.21.10",
                Map.of(LAYERS_MOD + ".class", sectionLayersClass()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(LAYERS_MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object mipped = newBlock(fake, "mipped");
            Object tripwire = newBlock(fake, "tripwire");
            mod.getField("MIPPED").set(null, mipped);
            mod.getField("TRIPWIRE").set(null, tripwire);
            run(mod, "layers");
            Class<?> layers = fake.type("com.retromod.loaderapi.fabric.v26.render.LegacyRenderLayers");
            Object[] hostLayers = fake.type("net.minecraft.client.renderer.chunk.ChunkSectionLayer").getEnumConstants();
            assertEquals(hostLayers[1], layers.getMethod("layerOf", Object.class).invoke(null, mipped),
                    "CUTOUT_MIPPED reads as CUTOUT");
            assertEquals(hostLayers[2], layers.getMethod("layerOf", Object.class).invoke(null, tripwire),
                    "TRIPWIRE reads as TRANSLUCENT");
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("A native mod's dynamic block color keeps its block, whichever mod colors it first")
    void dynamicBlockColorsKeepTheirBlock(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1mod", Map.of(MOD + ".class", colorsClass(),
                MOD_COLOR + ".class", ownColor()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object nativeFirst = newBlock(fake, "native_first");
            Object oldFirst = newBlock(fake, "old_first");
            startup(fake, "begin");

            // The native mod colors a block, then the old mod colors it too.
            Object nativeFirstFactory = registerFactory(fake, nativeFirst);
            mod.getField("BLOCK").set(null, nativeFirst);
            run(mod, "registerBlock");
            // The old mod colors a block, then the native mod colors it too.
            mod.getField("BLOCK").set(null, oldFirst);
            run(mod, "registerBlock");
            Object oldFirstFactory = registerFactory(fake, oldFirst);
            startup(fake, "finish");

            assertSame(nativeFirstFactory, factoryOf(fake, nativeFirst));
            assertSame(oldFirstFactory, factoryOf(fake, oldFirst));
            assertEquals(List.of(), tintSources(fake, invoke(nativeFirst, "defaultBlockState")));
            assertEquals(List.of(), tintSources(fake, invoke(oldFirst, "defaultBlockState")));
            assertEquals(Boolean.TRUE, run(mod, "getReturnsTheSameProvider"), "get() still answers the old provider");
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("An old block color registered while client mods initialize applies when the client starts")
    void oldBlockColorsApplyWhenTheClientStarts(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1mod", Map.of(MOD + ".class", colorsClass(),
                MOD_COLOR + ".class", ownColor()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object block = newBlock(fake, "leafy");
            startup(fake, "begin");
            mod.getField("BLOCK").set(null, block);
            run(mod, "registerBlock");
            assertNull(blockColors(fake), "the client has no block colors yet");
            startup(fake, "finish");

            Object state = invoke(block, "defaultBlockState");
            List<?> sources = tintSources(fake, state);
            assertEquals(128, sources.size(), "one host tint source per tint index");
            assertEquals(0xFF112200, invoke(sources.get(0), "color", state));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("After the client starts, an old color skips only the blocks a native mod colors dynamically")
    void oldColorSkipsOnlyDynamicBlocks(String host) throws Throwable {
        Map<String, byte[]> classes = translate("r1mod", Map.of(MOD + ".class", colorsClass(),
                MOD_COLOR + ".class", ownColor()), host);
        try (FakeHost fake = FakeHost.start()) {
            Class<?> mod = Class.forName(MOD.replace('/', '.'), true, fake.defineMod(classes));
            Object dynamic = newBlock(fake, "dynamic");
            Object plain = newBlock(fake, "plain");
            Object factory = registerFactory(fake, dynamic);
            mod.getField("BLOCK").set(null, dynamic);
            mod.getField("SOURCE").set(null, plain);
            run(mod, "registerBothBlocks");

            assertSame(factory, factoryOf(fake, dynamic));
            assertEquals(List.of(), tintSources(fake, invoke(dynamic, "defaultBlockState")));
            assertEquals(128, tintSources(fake, invoke(plain, "defaultBlockState")).size());
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("A library that takes the old BlockColor serves a mod translated apart from it")
    void libraryTakesTheOldTypeAcrossJars(String host) throws Throwable {
        Map<String, byte[]> classes = new HashMap<>(translate("r1lib", Map.of(LIB + ".class", libraryClass()), host));
        classes.putAll(translate("r1dep", Map.of(DEP + ".class", dependentClass(),
                DEP_COLOR + ".class", depColor()), host));
        try (FakeHost fake = FakeHost.start()) {
            Class<?> dep = Class.forName(DEP.replace('/', '.'), true, fake.defineMod(classes));
            Object lambdaBlock = newBlock(fake, "lambda_block");
            Object classBlock = newBlock(fake, "class_block");
            dep.getField("FIRST").set(null, lambdaBlock);
            dep.getField("SECOND").set(null, classBlock);
            run(dep, "run");
            assertEquals(0xFF000005, invoke(tintSources(fake, invoke(lambdaBlock, "defaultBlockState")).get(5),
                    "color", invoke(lambdaBlock, "defaultBlockState")));
            assertEquals(0xFFABCDEF, invoke(tintSources(fake, invoke(classBlock, "defaultBlockState")).get(0),
                    "color", invoke(classBlock, "defaultBlockState")));
        }
    }

    // ---- Host access ------------------------------------------------------------------------

    private static Object newBlock(FakeHost fake, String name) throws Exception {
        return fake.type("net.minecraft.world.level.block.Block").getConstructor(String.class).newInstance(name);
    }

    /** A native mod's {@code BlockColorRegistry.register(BlockTintsFactory, block)}. */
    private static Object registerFactory(FakeHost fake, Object block) throws Throwable {
        Class<?> factoryType = fake.type("net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory");
        Object factory = java.lang.reflect.Proxy.newProxyInstance(fake.loader(), new Class<?>[]{factoryType},
                (proxy, method, args) -> method.getName().equals("equals") ? proxy == args[0]
                        : method.getName().equals("hashCode") ? System.identityHashCode(proxy) : "native factory");
        Class<?> blockType = fake.type("net.minecraft.world.level.block.Block");
        Object blocks = java.lang.reflect.Array.newInstance(blockType, 1);
        java.lang.reflect.Array.set(blocks, 0, block);
        try {
            fake.type("net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry")
                    .getMethod("register", factoryType, blocks.getClass()).invoke(null, factory, blocks);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        return factory;
    }

    private static Object factoryOf(FakeHost fake, Object block) throws Throwable {
        Object state = invoke(block, "defaultBlockState");
        return fake.type("net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry")
                .getMethod("getFactory", state.getClass()).invoke(null, state);
    }

    /** Moves the fake client to the start of mod initialization, or finishes its start. */
    private static void startup(FakeHost fake, String step) throws Exception {
        fake.type("fixture.FakeClientStartup").getMethod(step).invoke(null);
    }

    private static Object id(FakeHost fake, String text) throws Exception {
        String[] parts = text.split(":", 2);
        return fake.type("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath",
                String.class, String.class).invoke(null, parts[0], parts[1]);
    }

    private static Object blockColors(FakeHost fake) throws Exception {
        Object minecraft = fake.type("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
        return minecraft.getClass().getMethod("getBlockColors").invoke(minecraft);
    }

    private static List<?> tintSources(FakeHost fake, Object state) throws Exception {
        Object colors = blockColors(fake);
        return (List<?>) colors.getClass().getMethod("getTintSources", state.getClass()).invoke(colors, state);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> resources(FakeHost fake) throws Exception {
        return (Map<String, String>) fake.type("fixture.FakeResources").getField("FILES").get(null);
    }

    private static Object bakeItem(FakeHost fake, Object itemId, Object modelId) throws Exception {
        Class<?> unbaked = fake.type("net.minecraft.client.renderer.item.CuboidItemModelWrapper$Unbaked");
        Object model = unbaked.getConstructors()[0].newInstance(modelId, Optional.empty(), List.of());
        Class<?> itemModel = fake.type("net.minecraft.client.renderer.item.ItemModel$Unbaked");
        return fake.type("fixture.FakeModelBake").getMethod("bakeItem", itemId.getClass(), itemModel)
                .invoke(null, itemId, model);
    }

    /** Calls a public method by name, whatever its declared parameter types. */
    private static Object invoke(Object target, String name, Object... args) throws Throwable {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            method.setAccessible(true);
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Object run(Class<?> owner, String method) throws Throwable {
        try {
            return owner.getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ---- Translation ------------------------------------------------------------------------

    /** Translates one small 1.20.1 mod through the CLI path and returns its classes. */
    private Map<String, byte[]> translate(String id, Map<String, byte[]> classes, String host) throws Exception {
        return translate(id, "1.20.1", classes, host);
    }

    /** Translates one small mod built for {@code minecraft} through the CLI path. */
    private Map<String, byte[]> translate(String id, String minecraft, Map<String, byte[]> classes, String host)
            throws Exception {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        if (runtime == null) runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"" + minecraft + "\",\"fabric-api\":\"*\"}}")
                .getBytes(StandardCharsets.UTF_8));
        entries.putAll(classes);
        Path input = dir.resolve(id + ".jar");
        Files.write(input, LoaderApiFixtures.jar(entries));
        Path output = dir.resolve(id + "-" + host + ".jar");
        CliHarness.transform(input, output, host);
        Map<String, byte[]> translated = new HashMap<>();
        try (JarFile file = new JarFile(output.toFile())) {
            for (var it = file.entries(); it.hasMoreElements(); ) {
                var entry = it.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                translated.put(entry.getName().substring(0, entry.getName().length() - 6),
                        file.getInputStream(entry).readAllBytes());
            }
        }
        return translated;
    }

    // ---- The old mod ------------------------------------------------------------------------

    /** Registers colors and render layers the way a 1.20.1 mod did. */
    private static byte[] colorsClass() {
        // Branches need stack map frames; the old types do not exist here, so every merge is Object.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD, null, "java/lang/Object", null);
        for (String field : List.of("BLOCK", "SOURCE")) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, BLOCK, null, null).visitEnd();
        }
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ITEM", "Lnet/minecraft/class_1792;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "PROVIDER", "L" + BLOCK_COLOR + ";", null, null).visitEnd();

        // registerBlock(): ColorProviderRegistry.BLOCK.register(lambda, BLOCK), keeping the lambda.
        MethodVisitor mv = LoaderApiFixtures.method(cw, "registerBlock", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS_API, "BLOCK", "L" + COLORS_API + ";");
        lambda(mv, BLOCK_COLOR, "getColor", BLOCK_SAM, "blockBody");
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, MOD, "PROVIDER", "L" + BLOCK_COLOR + ";");
        blockArray(mv, "BLOCK");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS_API, "register", "(Ljava/lang/Object;[Ljava/lang/Object;)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // The provider: the tint index, plus 0x100 with a level and 0x1000 with a position.
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "blockBody", BLOCK_SAM, null, null);
        mv.visitCode();
        mv.visitLdcInsn(0x112200);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IADD);
        addIfPresent(mv, 1, 0x100);
        addIfPresent(mv, 2, 0x1000);
        LoaderApiFixtures.end(mv, Opcodes.IRETURN);

        // registerBothBlocks(): ColorProviderRegistry.BLOCK.register(lambda, BLOCK, SOURCE).
        mv = LoaderApiFixtures.method(cw, "registerBothBlocks", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS_API, "BLOCK", "L" + COLORS_API + ";");
        lambda(mv, BLOCK_COLOR, "getColor", BLOCK_SAM, "blockBody");
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/class_2248");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "BLOCK", BLOCK);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "SOURCE", BLOCK);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS_API, "register", "(Ljava/lang/Object;[Ljava/lang/Object;)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // getReturnsTheSameProvider(): BLOCK.get(BLOCK) == PROVIDER.
        mv = LoaderApiFixtures.method(cw, "getReturnsTheSameProvider", "()Ljava/lang/Boolean;");
        getProvider(mv, "BLOCK");
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "PROVIDER", "L" + BLOCK_COLOR + ";");
        org.objectweb.asm.Label same = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, same);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(same);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitLabel(done);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        LoaderApiFixtures.end(mv, Opcodes.ARETURN);

        // callThroughTheOldType(): ((BlockColor) BLOCK.get(BLOCK)).getColor(null, null, null, 2).
        mv = LoaderApiFixtures.method(cw, "callThroughTheOldType", "()Ljava/lang/Integer;");
        getProvider(mv, "BLOCK");
        mv.visitTypeInsn(Opcodes.CHECKCAST, BLOCK_COLOR);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, BLOCK_COLOR, "getColor", BLOCK_SAM, true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        LoaderApiFixtures.end(mv, Opcodes.ARETURN);

        // copyColorFromSource(): BLOCK.register(BLOCK.get(SOURCE), BLOCK), as mods copied vanilla leaves.
        mv = LoaderApiFixtures.method(cw, "copyColorFromSource", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS_API, "BLOCK", "L" + COLORS_API + ";");
        getProvider(mv, "SOURCE");
        mv.visitTypeInsn(Opcodes.CHECKCAST, BLOCK_COLOR);
        blockArray(mv, "BLOCK");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS_API, "register", "(Ljava/lang/Object;[Ljava/lang/Object;)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        // registerItem(): ColorProviderRegistry.ITEM.register(lambda, ITEM).
        mv = LoaderApiFixtures.method(cw, "registerItem", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS_API, "ITEM", "L" + COLORS_API + ";");
        lambda(mv, ITEM_COLOR, "getColor", ITEM_SAM, "itemBody");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/class_1935");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "ITEM", "Lnet/minecraft/class_1792;");
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS_API, "register", "(Ljava/lang/Object;[Ljava/lang/Object;)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "itemBody", ITEM_SAM, null, null);
        mv.visitCode();
        mv.visitLdcInsn(0x00AA00);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.IADD);
        LoaderApiFixtures.end(mv, Opcodes.IRETURN);

        // itemProviderOwner(): the item whose provider ITEM.get(ITEM) returns, or null.
        mv = LoaderApiFixtures.method(cw, "itemProviderOwner", "()Ljava/lang/Object;");
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS_API, "ITEM", "L" + COLORS_API + ";");
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "ITEM", "Lnet/minecraft/class_1792;");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS_API, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        org.objectweb.asm.Label none = new org.objectweb.asm.Label();
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "ITEM", "Lnet/minecraft/class_1792;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.ACONST_NULL);
        LoaderApiFixtures.end(mv, Opcodes.ARETURN);

        // layers(): BlockRenderLayerMap.INSTANCE.putBlock(BLOCK, null), then the 1.21.6 static putBlocks.
        mv = LoaderApiFixtures.method(cw, "layers", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, LAYERS_API, "INSTANCE", "L" + LAYERS_API + ";");
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, "BLOCK", BLOCK);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LAYERS_API, "putBlock",
                "(" + BLOCK + "Lnet/minecraft/class_1921;)V", true);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_11515", "field_60925", "Lnet/minecraft/class_11515;");
        blockArray(mv, "SOURCE");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STATIC_LAYERS_API, "putBlocks",
                "(Lnet/minecraft/class_11515;[" + BLOCK + ")V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A 1.21.10 mod's static layer calls with CUTOUT_MIPPED ({@code field_60924}, gone in 1.21.11)
     * and TRIPWIRE ({@code field_60927}), neither of which 26.x has.
     */
    private static byte[] sectionLayersClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LAYERS_MOD, null, "java/lang/Object", null);
        for (String field : List.of("MIPPED", "TRIPWIRE")) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, BLOCK, null, null).visitEnd();
        }
        MethodVisitor mv = LoaderApiFixtures.method(cw, "layers", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, LAYERS_MOD, "MIPPED", BLOCK);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SECTION_LAYER, "field_60924", "L" + SECTION_LAYER + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STATIC_LAYERS_API, "putBlock",
                "(" + BLOCK + "L" + SECTION_LAYER + ";)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SECTION_LAYER, "field_60927", "L" + SECTION_LAYER + ";");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/class_2248");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, LAYERS_MOD, "TRIPWIRE", BLOCK);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STATIC_LAYERS_API, "putBlocks",
                "(L" + SECTION_LAYER + ";[" + BLOCK + ")V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that implements the old {@code BlockColor} itself. */
    private static byte[] ownColor() {
        return colorClass(MOD_COLOR, 0x00ABCDEF);
    }

    private static byte[] depColor() {
        return colorClass(DEP_COLOR, 0x00ABCDEF);
    }

    private static byte[] colorClass(String name, int color) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object",
                new String[]{BLOCK_COLOR});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        LoaderApiFixtures.end(init, Opcodes.RETURN);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getColor", BLOCK_SAM, null, null);
        mv.visitCode();
        mv.visitLdcInsn(color);
        LoaderApiFixtures.end(mv, Opcodes.IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A library method that takes the old {@code BlockColor}, as Balm's color registrars do. */
    private static byte[] libraryClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LIB, null, "java/lang/Object", null);
        MethodVisitor mv = LoaderApiFixtures.method(cw, "registerBlockColor", "(L" + BLOCK_COLOR + ";" + BLOCK + ")V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS_API, "BLOCK", "L" + COLORS_API + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/class_2248");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS_API, "register", "(Ljava/lang/Object;[Ljava/lang/Object;)V", true);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod that hands the library a lambda and an instance of its own color class. */
    private static byte[] dependentClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, DEP, null, "java/lang/Object", null);
        for (String field : List.of("FIRST", "SECOND")) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, BLOCK, null, null).visitEnd();
        }
        MethodVisitor mv = LoaderApiFixtures.method(cw, "run", "()V");
        mv.visitInvokeDynamicInsn("getColor", "()L" + BLOCK_COLOR + ";", metafactory(), Type.getType(BLOCK_SAM),
                new Handle(Opcodes.H_INVOKESTATIC, DEP, "body", BLOCK_SAM, false), Type.getType(BLOCK_SAM));
        mv.visitFieldInsn(Opcodes.GETSTATIC, DEP, "FIRST", BLOCK);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LIB, "registerBlockColor", "(L" + BLOCK_COLOR + ";" + BLOCK + ")V", false);
        mv.visitTypeInsn(Opcodes.NEW, DEP_COLOR);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, DEP_COLOR, "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, DEP, "SECOND", BLOCK);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LIB, "registerBlockColor", "(L" + BLOCK_COLOR + ";" + BLOCK + ")V", false);
        LoaderApiFixtures.end(mv, Opcodes.RETURN);
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "body", BLOCK_SAM, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        LoaderApiFixtures.end(mv, Opcodes.IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void lambda(MethodVisitor mv, String iface, String sam, String samDesc, String body) {
        mv.visitInvokeDynamicInsn(sam, "()L" + iface + ";", metafactory(), Type.getType(samDesc),
                new Handle(Opcodes.H_INVOKESTATIC, MOD, body, samDesc, false), Type.getType(samDesc));
    }

    private static Handle metafactory() {
        return new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    /** Pushes {@code new Block[]{field}}. */
    private static void blockArray(MethodVisitor mv, String field) {
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/class_2248");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, field, BLOCK);
        mv.visitInsn(Opcodes.AASTORE);
    }

    /** Pushes {@code ColorProviderRegistry.BLOCK.get(field)}. */
    private static void getProvider(MethodVisitor mv, String field) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS_API, "BLOCK", "L" + COLORS_API + ";");
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD, field, BLOCK);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS_API, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
    }

    /** Adds {@code amount} to the int on the stack when local {@code slot} is not null. */
    private static void addIfPresent(MethodVisitor mv, int slot, int amount) {
        org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitJumpInsn(Opcodes.IFNULL, skip);
        mv.visitLdcInsn(amount);
        mv.visitInsn(Opcodes.IADD);
        mv.visitLabel(skip);
    }
}
