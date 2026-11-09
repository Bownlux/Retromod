/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

/**
 * Farmer's Delight, Sophisticated Core and Sophisticated Backpacks 1.20.1, translated by the CLI, then read, matched
 * and assembled by the real 26.x game classes: the cooking pot, cutting board and upgrade recipes.
 */
class LegacyRecipeSerializerHostTest {

    private static final String HOME = System.getProperty("user.home");
    private static final Path LIBRARIES = Path.of(HOME, "Library/Application Support/PrismLauncher/libraries");
    private static final Path MODS = Path.of(System.getProperty("retromod.test.forge1201mods",
            HOME + "/Development/Minecraft/RetroMod/.retromod-work/survey/dl-forge1201"));
    private static final Path FORGE263 = Path.of(System.getProperty("retromod.test.forge263",
            HOME + "/.claude/jobs/3da2a019/tmp/forge263"));

    private static final String FD = "FarmersDelight-1.20.1-1.3.4.jar";
    private static final String SC = "sophisticatedcore-1.20.1-1.5.17.2435.jar";
    private static final String SB = "sophisticatedbackpacks-1.20.1-3.26.17.2231.jar";
    private static final String FD_CRAFTING = "vectorwing.farmersdelight.common.crafting.";
    private static final String CRAFTING = "net.minecraft.world.item.crafting.";

    /** The game libraries both hosts share, then the ones whose versions differ, by host. */
    private static final List<String> SHARED = List.of("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar",
            "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar", "com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar",
            "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar", "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar",
            "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar", "com/mojang/logging/1.7.12/logging-1.7.12.jar",
            "org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar", "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
            "com/ibm/icu/icu4j/78.3/icu4j-78.3.jar", "commons-io/commons-io/2.20.0/commons-io-2.20.0.jar");
    private static final Map<String, List<String>> BY_HOST = Map.of(
            "26.2", List.of("com/mojang/authlib/9.0.75/authlib-9.0.75.jar", "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
                    "com/mojang/jtracy/1.0.37/jtracy-1.0.37.jar", "org/joml/joml/1.10.8/joml-1.10.8.jar", "netty 4.2.15.Final"),
            "26.3", List.of("com/mojang/authlib/10.0.77/authlib-10.0.77.jar", "com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar",
                    "com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar", "org/joml/joml/1.10.9/joml-1.10.9.jar", "netty 4.2.16.Final"));

    /** The mods' own items, which the test game does not register, read as vanilla items. */
    private static final Map<String, String> STAND_INS = Map.of(
            "sophisticatedbackpacks:pickup_upgrade", "minecraft:paper",
            "sophisticatedbackpacks:advanced_pickup_upgrade", "minecraft:map",
            "sophisticatedbackpacks:backpack", "minecraft:bundle",
            "sophisticatedbackpacks:copper_backpack", "minecraft:chest");

    /** The tags the recipes name, as a world's data would fill them. */
    private static final Map<String, List<String>> TAGS = Map.of(
            "minecraft:shovels", List.of("minecraft:iron_shovel"),
            "forge:gems/diamond", List.of("minecraft:diamond"),
            "forge:ingots/gold", List.of("minecraft:gold_ingot"),
            "forge:dusts/redstone", List.of("minecraft:redstone"),
            "forge:ingots/copper", List.of("minecraft:copper_ingot"));

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, 26.2", "forge, 26.3", "neoforge, 26.3"})
    @DisplayName("Cooking pot, cutting board and upgrade recipes load, match their inputs and assemble their results")
    void oldRecipesWorkOnTheHost(String loader, String version) throws Exception {
        List<Path> host = hostJars(loader, version);
        for (String mod : List.of(FD, SC, SB)) {
            assumeTrue(Files.isRegularFile(MODS.resolve(mod)), "no local " + mod + "; the 1.20.1 mods are local test material");
        }
        boolean forge = loader.equals("forge");
        Path fd = translate(MODS.resolve(FD), loader, version);
        List<URL> urls = new ArrayList<>();
        for (Path jar : List.of(fd, translate(MODS.resolve(SC), loader, version), translate(MODS.resolve(SB), loader, version))) {
            urls.add(jar.toUri().toURL());
        }
        // Forge's own registries need a running Forge; its jar is copied with a stand-in that reads the game's items.
        if (forge) host.set(host.size() - 1, withRegistriesStandIn(host.get(host.size() - 1)));
        for (Path jar : host) urls.add(jar.toUri().toURL());

        try (URLClassLoader game = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Game mc = new Game(game);

            // Every recipe is read first, as a world's first load reads them before it binds item components or tags.
            Object cookingSerializer = mc.newInstance(FD_CRAFTING + "CookingPotRecipe$Serializer");
            Object stew = mc.decode(cookingSerializer, recipe(FD, "data/farmersdelight/recipes/cooking/mushroom_stew.json"));
            Object clay = mc.decode(mc.newInstance(FD_CRAFTING + "CuttingBoardRecipe$Serializer"),
                    withShovelTool(recipe(FD, "data/farmersdelight/recipes/cutting/clay.json")));
            assertEquals(FD_CRAFTING + "CookingPotRecipe", stew.getClass().getName());
            assertEquals(FD_CRAFTING + "CuttingBoardRecipe", clay.getClass().getName());
            assertSame(mc.type(CRAFTING + "PlacementInfo").getField("NOT_PLACEABLE").get(null), mc.recipeCall(stew, "placementInfo"),
                    "the recipe book cannot place a cooking pot recipe in a crafting grid");
            assertNotNull(mc.recipeCall(clay, "recipeBookCategory"));

            if (forge) {
                // The cooking pot and cutting board hold Forge's item handler wrapper, which NeoForge does not have.
                assertEquals(200, mc.call(stew, "getCookTime"));
                assertEquals(2, ((List<?>) mc.call(stew, "getIngredients")).size(), "a brown and a red mushroom");
                assertEquals(mc.item("minecraft:bowl"), mc.call(mc.call(stew, "getOutputContainer"), "getItem"));
                mc.bindWorld("minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:mushroom_stew", "minecraft:bowl",
                        "minecraft:clay", "minecraft:dirt", "minecraft:clay_ball");
                cookAndCut(mc, new Handlers(mc, fd), stew, clay, cookingSerializer);
                return;
            }

            // The upgrades read Forge's shaped recipe size getters, which only Forge's patched game jar has, so they run on NeoForge.
            Object pickup = mc.decode(mc.newInstance("net.p3pp3rf1y.sophisticatedcore.crafting.UpgradeNextTierRecipe$Serializer"),
                    recipe(SB, "data/sophisticatedbackpacks/recipes/advanced_pickup_upgrade.json"));
            Object copper = mc.decode(mc.newInstance("net.p3pp3rf1y.sophisticatedbackpacks.crafting.BackpackUpgradeRecipe$Serializer"),
                    recipe(SB, "data/sophisticatedbackpacks/recipes/copper_backpack.json"));
            assertTrue(mc.type(CRAFTING + "ShapedRecipe").isInstance(pickup), "the upgrade recipe is still the host's shaped recipe");
            assertTrue(mc.type(CRAFTING + "ShapedRecipe").isInstance(copper), "the backpack upgrade is still the host's shaped recipe");
            assertEquals(3, mc.call(copper, "getWidth"));
            assertEquals(3, mc.call(copper, "getHeight"));

            mc.bindWorld("minecraft:diamond", "minecraft:gold_ingot", "minecraft:redstone", "minecraft:paper", "minecraft:map");
            List<Object> grid = new ArrayList<>(Arrays.asList(mc.empty(), mc.stack("minecraft:diamond", 1), mc.empty(),
                    mc.stack("minecraft:gold_ingot", 1), mc.stack("minecraft:paper", 1), mc.stack("minecraft:gold_ingot", 1),
                    mc.stack("minecraft:redstone", 1), mc.stack("minecraft:redstone", 1), mc.stack("minecraft:redstone", 1)));
            Object table = mc.craftingInput(grid);
            assertTrue(mc.matches(pickup, table), "the upgrade pattern matches its tagged ingredients");
            grid.set(1, mc.empty());
            assertFalse(mc.matches(pickup, mc.craftingInput(grid)));
            Object upgrade = mc.assemble(pickup, table);
            assertEquals(mc.item("minecraft:map"), mc.call(upgrade, "getItem"), "the mod's own assemble runs and returns the result");
        }
    }

    /** The cooking pot and cutting board recipes matched and assembled through the blocks' own inventory wrapper. */
    private static void cookAndCut(Game mc, Handlers handlers, Object stew, Object clay, Object cookingSerializer) throws Exception {
        Object mushrooms = handlers.input(mc.stack("minecraft:red_mushroom", 1), mc.stack("minecraft:brown_mushroom", 1));
        assertTrue(mc.matches(stew, mushrooms), "a brown and a red mushroom make mushroom stew, in any slots");
        assertFalse(mc.matches(stew, handlers.input(mc.stack("minecraft:red_mushroom", 1), mc.stack("minecraft:red_mushroom", 1))));
        Object meal = mc.assemble(stew, mushrooms);
        assertEquals(mc.item("minecraft:mushroom_stew"), mc.call(meal, "getItem"));
        assertTrue(mc.sameItemSameComponents(meal, mc.stack("minecraft:mushroom_stew", 1)), "the result stacks with a new stack");
        assertTrue(mc.sameItemSameComponents(mc.stack("minecraft:mushroom_stew", 1), meal), "and the other way round");
        Object synced = mc.roundTrip(cookingSerializer, stew);
        assertEquals(200, mc.call(synced, "getCookTime"), "the recipe survives the trip to a client");
        assertEquals(2, ((List<?>) mc.call(synced, "getIngredients")).size());

        assertTrue(mc.matches(clay, handlers.input(mc.stack("minecraft:clay", 1))), "clay goes on the cutting board");
        assertFalse(mc.matches(clay, handlers.input(mc.stack("minecraft:dirt", 1))));
        Object balls = mc.assemble(clay, handlers.input(mc.stack("minecraft:clay", 1)));
        assertEquals(mc.item("minecraft:clay_ball"), mc.call(balls, "getItem"));
        assertEquals(4, mc.call(balls, "getCount"));
    }

    /** The jars the game classes load from: Minecraft, its libraries, and the loader's own jar. */
    private static List<Path> hostJars(String loader, String version) {
        List<String> names = new ArrayList<>(List.of("com/mojang/minecraft/" + version + "/minecraft-" + version + "-client.jar"));
        names.addAll(SHARED);
        for (String jar : BY_HOST.get(version)) {
            if (!jar.startsWith("netty ")) {
                names.add(jar);
                continue;
            }
            String netty = jar.substring("netty ".length());
            for (String part : List.of("buffer", "common", "codec-base", "transport", "handler")) {
                names.add("io/netty/netty-" + part + "/" + netty + "/netty-" + part + "-" + netty + ".jar");
            }
        }
        List<Path> jars = new ArrayList<>();
        for (String name : names) jars.add(LIBRARIES.resolve(name));
        jars.add(switch (loader + " " + version) {
            case "forge 26.2" -> LIBRARIES.resolve("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-universal.jar");
            case "forge 26.3" -> FORGE263.resolve("forge-26.3-66.1.2-universal.jar");
            default -> LIBRARIES.resolve("net/neoforged/neoforge/26.3.0.43-beta/neoforge-26.3.0.43-beta-universal.jar");
        });
        for (Path jar : jars) assumeTrue(Files.isRegularFile(jar), "no local " + jar.getFileName() + "; the game jars are local test material");
        return jars;
    }

    /** One mod translated for the host by the CLI, as {@code transform --target-loader} does. */
    private Path translate(Path mod, String loader, String version) throws Exception {
        ModVersionDetector.setDefaultPreferredLoader(loader);
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        McReflect.setForceForge(loader.equals("forge"));
        Path output = Files.createDirectories(dir.resolve(loader + "-" + version)).resolve(mod.getFileName());
        CliHarness.transform(mod, output, version);
        assertTrue(Files.isRegularFile(output), "the CLI must write " + output);
        return output;
    }

    /** A recipe file from the original mod jar, with the mods' items read as their vanilla stand-ins. */
    private static String recipe(String mod, String path) throws IOException {
        try (JarFile jar = new JarFile(MODS.resolve(mod).toFile())) {
            JarEntry entry = jar.getJarEntry(path);
            assertNotNull(entry, mod + " has " + path);
            String json;
            try (InputStream in = jar.getInputStream(entry)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (Map.Entry<String, String> standIn : STAND_INS.entrySet()) {
                json = json.replace("\"" + standIn.getKey() + "\"", "\"" + standIn.getValue() + "\"");
            }
            return json;
        }
    }

    /**
     * The cutting recipe with its tool as a plain tag. Its own tool ingredient is a type Farmer's Delight registers
     * with Forge when it starts, which this test game does not run.
     */
    private static String withShovelTool(String json) {
        int tool = json.indexOf("\"tool\"");
        assertTrue(tool > 0 && json.lastIndexOf(']') > tool, "the tool is the last entry of " + json);
        return json.substring(0, tool) + "\"tool\": [{\"tag\": \"minecraft:shovels\"}]\n}";
    }

    /**
     * The Forge jar with {@code ForgeRegistries} replaced by one whose {@code ITEMS} reads the game's item registry.
     * The copy is unsigned, since a signed package cannot take an unsigned class.
     */
    private Path withRegistriesStandIn(Path forge) throws IOException {
        String owner = "net/minecraftforge/registries/ForgeRegistries";
        String registry = "Lnet/minecraftforge/registries/IForgeRegistry;";
        String view = Type.getInternalName(ItemRegistryView.class);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, owner, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ITEMS", registry, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn(Type.getObjectType(owner));
        init.visitMethodInsn(INVOKESTATIC, view, "items", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        init.visitTypeInsn(CHECKCAST, registry.substring(1, registry.length() - 1));
        init.visitFieldInsn(PUTSTATIC, owner, "ITEMS", registry);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();

        Path jar = dir.resolve("forge-unsigned.jar");
        try (JarFile original = new JarFile(forge.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             InputStream viewBytes = ItemRegistryView.class.getResourceAsStream("/" + view + ".class")) {
            for (JarEntry entry : (Iterable<JarEntry>) original.stream()::iterator) {
                String name = entry.getName();
                if (entry.isDirectory() || name.equals(owner + ".class") || name.startsWith("META-INF/")) continue;
                out.putNextEntry(new JarEntry(name));
                try (InputStream in = original.getInputStream(entry)) {
                    in.transferTo(out);
                }
            }
            out.putNextEntry(new JarEntry(owner + ".class"));
            out.write(cw.toByteArray());
            out.putNextEntry(new JarEntry(view + ".class"));
            out.write(viewBytes.readAllBytes());
        }
        return jar;
    }

    /** The registry view the stand-in hands out; loaded by the game's loader, so it names the game only by reflection. */
    public static final class ItemRegistryView implements InvocationHandler {
        private final Object registry;
        private final Method[] methods;

        ItemRegistryView(Object registry, Method[] methods) {
            this.registry = registry;
            this.methods = methods;
        }

        public static Object items(Class<?> anchor) throws ReflectiveOperationException {
            ClassLoader loader = anchor.getClassLoader();
            Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader).getField("ITEM").get(null);
            Method[] methods = Class.forName("net.minecraft.core.Registry", false, loader).getMethods();
            Class<?> forge = Class.forName("net.minecraftforge.registries.IForgeRegistry", false, loader);
            return Proxy.newProxyInstance(loader, new Class<?>[]{forge}, new ItemRegistryView(registry, methods));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws ReflectiveOperationException {
            // getValue and containsKey take one id, which the game's registry answers under the same names.
            for (Method own : methods) {
                if (own.getName().equals(method.getName()) && args != null && args.length == 1
                        && own.getParameterCount() == 1 && own.getParameterTypes()[0].isInstance(args[0])) {
                    return own.invoke(registry, args);
                }
            }
            throw new UnsupportedOperationException("the test's item registry has no " + method.getName());
        }
    }

    /** The cooking pot's and cutting board's inventory: an item handler behind a recipe wrapper, as the block entities hold it. */
    private static final class Handlers {
        private final Game mc;
        private final Class<?> handler;
        private final Class<?> wrapper;
        private final Method wrap;

        Handlers(Game mc, Path translated) throws Exception {
            this.mc = mc;
            // The classes the translated mod names, whichever loader's or Retromod's rebuilt ones they became.
            ClassNode pot = read(translated, "vectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity");
            this.handler = mc.type(pot.fields.stream().filter(field -> field.name.equals("inventory")).findFirst()
                    .map(field -> Type.getType(field.desc).getClassName()).orElseThrow(() -> new AssertionError("the pot keeps an inventory")));
            ClassNode recipe = read(translated, "vectorwing/farmersdelight/common/crafting/CookingPotRecipe");
            this.wrapper = mc.type(recipe.methods.stream().filter(method -> method.name.equals("matches"))
                    .map(method -> Type.getArgumentTypes(method.desc)[0].getClassName())
                    .filter(type -> !type.equals("net.minecraft.world.Container") && !type.equals(CRAFTING + "RecipeInput"))
                    .findFirst().orElseThrow(() -> new AssertionError("the recipe matches its own wrapper")));
            this.wrap = inputWrapper(mc.loader, embedded(translated, LegacyRecipeApiBridge.INPUT));
        }

        private static ClassNode read(Path jar, String internalName) throws IOException {
            try (JarFile file = new JarFile(jar.toFile())) {
                JarEntry entry = file.getJarEntry(internalName + ".class");
                assertNotNull(entry, jar.getFileName() + " has " + internalName);
                ClassNode node = new ClassNode();
                new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                return node;
            }
        }

        /** The wrapper with these stacks in its first slots, passed the way the mod's own lookups pass it. */
        Object input(Object... stacks) throws Exception {
            Object items = handler.getConstructor(int.class).newInstance(9);
            Method set = handler.getMethod("setStackInSlot", int.class, mc.type("net.minecraft.world.item.ItemStack"));
            for (int slot = 0; slot < stacks.length; slot++) set.invoke(items, slot, stacks[slot]);
            Object recipeWrapper = wrapper.getConstructors()[0].newInstance(items);
            return wrap.invoke(null, recipeWrapper);
        }

        /** The name the translated jar embedded {@code internalName} under. */
        private static String embedded(Path jar, String internalName) throws IOException {
            try (JarFile file = new JarFile(jar.toFile())) {
                return file.stream().map(JarEntry::getName).filter(name -> name.endsWith("/" + internalName + ".class"))
                        .findFirst().map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                        .orElseThrow(() -> new AssertionError(jar.getFileName() + " carries no " + internalName));
            }
        }

        /**
         * {@code new LegacyContainerInput(container)} in bytecode, as the mod's redirected lookups build it: the
         * rebuilt NeoForge wrapper reaches it typed as the old container, which reflection would refuse.
         */
        private static Method inputWrapper(ClassLoader game, String input) throws Exception {
            String type = input.replace('.', '/');
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "retromod/test/WrapInput", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "wrap", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, type);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "(Lnet/minecraft/world/Container;)V", false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            ClassLoader child = new ClassLoader(game) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (!name.equals("retromod.test.WrapInput")) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            return child.loadClass("retromod.test.WrapInput").getMethod("wrap", Object.class);
        }
    }

    /** The bootstrapped game in its own loader, reached through reflection. */
    private static final class Game {
        private final ClassLoader loader;
        private final Object ops;
        private final Object pendingTags;

        Game(ClassLoader loader) throws Exception {
            this.loader = loader;
            // Bootstrap routes the standard streams into the game's logger, which this test JVM keeps using.
            PrintStream out = System.out;
            PrintStream err = System.err;
            try {
                type("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
                type("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
            // A world's first load reads recipes through the registries with its new tags, before it binds them.
            Object items = type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
            Object itemKey = type("net.minecraft.core.registries.Registries").getField("ITEM").get(null);
            Map<Object, List<Object>> tags = new HashMap<>();
            for (Map.Entry<String, List<String>> tag : TAGS.entrySet()) {
                List<Object> holders = new ArrayList<>();
                for (String id : tag.getValue()) holders.add(holder(id));
                tags.put(type("net.minecraft.tags.TagKey").getMethod("create", type("net.minecraft.resources.ResourceKey"), idType())
                        .invoke(null, itemKey, id(tag.getKey())), holders);
            }
            Object result = type("net.minecraft.tags.TagLoader$LoadResult").getConstructor(type("net.minecraft.resources.ResourceKey"), Map.class)
                    .newInstance(itemKey, tags);
            pendingTags = type("net.minecraft.core.Registry").getMethod("prepareTagReload", result.getClass()).invoke(items, result);
            Object lookup = type("net.minecraft.core.Registry$PendingTags").getMethod("lookup").invoke(pendingTags);
            Object provider = type("net.minecraft.core.HolderLookup$Provider").getMethod("create", Stream.class).invoke(null, Stream.of(lookup));
            Object json = type("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
            ops = type("net.minecraft.core.HolderLookup$Provider").getMethod("createSerializationContext", type("com.mojang.serialization.DynamicOps"))
                    .invoke(provider, json);
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object newInstance(String name) throws Exception {
            var constructor = type(name).getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }

        /** The recipe a translated serializer's codec reads from the JSON, through the host's registry ops. */
        Object decode(Object serializer, String text) throws Exception {
            Object mapCodec = serializer.getClass().getMethod("codec").invoke(serializer);
            Object codec = type("com.mojang.serialization.MapCodec").getMethod("codec").invoke(mapCodec);
            Object element = type("com.google.gson.JsonParser").getMethod("parseString", String.class).invoke(null, text);
            Object result = type("com.mojang.serialization.Decoder").getMethod("parse", type("com.mojang.serialization.DynamicOps"), Object.class)
                    .invoke(codec, ops, element);
            Optional<?> value = (Optional<?>) type("com.mojang.serialization.DataResult").getMethod("result").invoke(result);
            assertTrue(value.isPresent(), () -> serializer.getClass().getSimpleName() + " reported " + result);
            return value.get();
        }

        /** The recipe written by the serializer's stream codec and read back, as a client receives it. */
        Object roundTrip(Object serializer, Object recipe) throws Exception {
            Object streamCodec = serializer.getClass().getMethod("streamCodec").invoke(serializer);
            Object access = type("net.minecraft.core.RegistryAccess").getMethod("fromRegistryOfRegistries", type("net.minecraft.core.Registry"))
                    .invoke(null, type("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY").get(null));
            Object bytes = type("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
            Object buf = type("net.minecraft.network.RegistryFriendlyByteBuf")
                    .getConstructor(type("io.netty.buffer.ByteBuf"), type("net.minecraft.core.RegistryAccess")).newInstance(bytes, access);
            type("net.minecraft.network.codec.StreamEncoder").getMethod("encode", Object.class, Object.class).invoke(streamCodec, buf, recipe);
            return type("net.minecraft.network.codec.StreamDecoder").getMethod("decode", Object.class).invoke(streamCodec, buf);
        }

        /** Binds the world's tags and these items' components, as a loaded world does after its recipes. */
        void bindWorld(String... ids) throws Exception {
            type("net.minecraft.core.Registry$PendingTags").getMethod("apply").invoke(pendingTags);
            Class<?> components = type("net.minecraft.core.component.DataComponentMap");
            Object maxSize = type("net.minecraft.core.component.DataComponents").getField("MAX_STACK_SIZE").get(null);
            for (String id : ids) {
                Object holder = holder(id);
                Object builder = components.getMethod("builder").invoke(null);
                builder.getClass().getMethod("set", type("net.minecraft.core.component.DataComponentType"), Object.class).invoke(builder, maxSize, 64);
                holder.getClass().getMethod("bindComponents", components).invoke(holder, builder.getClass().getMethod("build").invoke(builder));
            }
        }

        Object item(String id) throws Exception {
            Object items = type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
            return type("net.minecraft.core.Registry").getMethod("getValue", idType()).invoke(items, id(id));
        }

        Object holder(String id) throws Exception {
            return type("net.minecraft.world.item.Item").getMethod("builtInRegistryHolder").invoke(item(id));
        }

        Object stack(String id, int count) throws Exception {
            return type("net.minecraft.world.item.ItemStack").getConstructor(type("net.minecraft.world.level.ItemLike"), int.class)
                    .newInstance(item(id), count);
        }

        Object empty() throws Exception {
            return type("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null);
        }

        Object craftingInput(List<Object> stacks) throws Exception {
            return type(CRAFTING + "CraftingInput").getMethod("of", int.class, int.class, List.class).invoke(null, 3, 3, stacks);
        }

        /** {@code recipe.matches(input, level)} through the host's interface, as its recipe manager asks. */
        boolean matches(Object recipe, Object input) throws Exception {
            return (boolean) type(CRAFTING + "Recipe").getMethod("matches", type(CRAFTING + "RecipeInput"), type("net.minecraft.world.level.Level"))
                    .invoke(recipe, input, null);
        }

        /** {@code recipe.assemble(input)}, with no registries from 26.1. */
        Object assemble(Object recipe, Object input) throws Exception {
            for (Method method : type(CRAFTING + "Recipe").getMethods()) {
                if (!method.getName().equals("assemble")) continue;
                return method.getParameterCount() == 1 ? method.invoke(recipe, input) : method.invoke(recipe, input, null);
            }
            throw new AssertionError("the host's Recipe has no assemble");
        }

        Object recipeCall(Object recipe, String name) throws Exception {
            return type(CRAFTING + "Recipe").getMethod(name).invoke(recipe);
        }

        boolean sameItemSameComponents(Object first, Object second) throws Exception {
            Class<?> stack = type("net.minecraft.world.item.ItemStack");
            return (boolean) stack.getMethod("isSameItemSameComponents", stack, stack).invoke(null, first, second);
        }

        Object call(Object target, String name) throws Exception {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        }

        private Class<?> idType() throws ClassNotFoundException {
            return type("net.minecraft.resources.Identifier");
        }

        private Object id(String text) throws Exception {
            return idType().getMethod("parse", String.class).invoke(null, text);
        }
    }
}
