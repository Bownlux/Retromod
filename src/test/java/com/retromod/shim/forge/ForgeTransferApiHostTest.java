/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Farmer's Delight 1.20.1, a Forge mod, translated for NeoForge 1.21.1 and 26.3 and run on the real game and NeoForge
 * classes: its cooking pot's own item handler, wrapped the way the block entity wraps it, matches the pot's recipe
 * and saves.
 */
class ForgeTransferApiHostTest {

    private static final String HOME = System.getProperty("user.home");
    private static final Path PRISM = Path.of(HOME, "Library/Application Support/PrismLauncher/libraries");
    private static final Path NF1211 = Path.of(System.getProperty("retromod.test.neoforge1211",
            HOME + "/Development/Minecraft/RetroMod/.retromod-work/nf1211/libraries"));
    private static final Path FD = Path.of(System.getProperty("retromod.test.forge1201mods",
            HOME + "/Development/Minecraft/RetroMod/.retromod-work/survey/dl-forge1201")).resolve("FarmersDelight-1.20.1-1.3.4.jar");
    private static final String POT = "vectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("The cooking pot's handler and recipe wrapper match its recipe and save on NeoForge")
    void cookingPotMatchesItsRecipe(String version) throws Exception {
        List<Path> host = hostJars(version);
        assumeTrue(Files.isRegularFile(FD), "no local " + FD.getFileName() + "; the 1.20.1 mods are local test material");
        List<URL> urls = new ArrayList<>();
        for (Path jar : host) urls.add(jar.toUri().toURL());
        Path fd = translate(version, urls);
        Path tank = translate(tankMod(), version, urls);
        urls.add(0, fd.toUri().toURL());
        urls.add(1, tank.toUri().toURL());

        try (URLClassLoader game = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Game mc = new Game(game, version);

            // The block entity as the game would hold it, with the handler it builds for itself.
            List<String> interfaces = read(fd, POT).interfaces;
            assertTrue(interfaces.stream().noneMatch(name -> name.startsWith("net/minecraftforge/") || name.equals("net/minecraft/world/inventory/RecipeHolder")),
                    "the pot implements only types the host or the jar has: " + interfaces);
            Object pot = mc.allocate(POT);
            Method createHandler = pot.getClass().getDeclaredMethod("createHandler");
            createHandler.setAccessible(true);
            Object handler = createHandler.invoke(pot);
            String handlerType = handler.getClass().getSuperclass().getName();
            assertTrue(version.equals("26.3") ? handlerType.endsWith(".com.retromod.legacyapi.neoforge.items.ItemStackHandler")
                    : handlerType.equals("net.neoforged.neoforge.items.ItemStackHandler"), "the handler extends " + handlerType);
            mc.put(handler, 0, "minecraft:red_mushroom");
            mc.put(handler, 1, "minecraft:brown_mushroom");

            // The wrapper the pot passes its recipe lookup, named the way the translated block entity names it.
            Class<?> wrapperType = game.loadClass(wrapperName(fd));
            assertTrue(game.loadClass("net.minecraft.world.Container").isAssignableFrom(wrapperType), "the old wrapper is a container");
            Object wrapper = wrapperType.getConstructors()[0].newInstance(handler);
            assertEquals(9, wrapperType.getMethod("getContainerSize").invoke(wrapper));

            Object recipe = mc.cookingRecipe(game.loadClass("vectorwing.farmersdelight.common.crafting.CookingPotRecipe"));
            Method matches = recipe.getClass().getMethod("matches", wrapperType, game.loadClass("net.minecraft.world.level.Level"));
            assertTrue((boolean) matches.invoke(recipe, wrapper, null), "a red and a brown mushroom make mushroom stew");
            Object other = createHandler.invoke(pot);
            mc.put(other, 0, "minecraft:red_mushroom");
            mc.put(other, 1, "minecraft:red_mushroom");
            assertFalse((boolean) matches.invoke(recipe, wrapperType.getConstructors()[0].newInstance(other), null));

            // The pot's no-argument save and load reach the handler with the save's registries.
            Class<?> context = game.loadClass(embeddedName(fd, "com/retromod/legacyapi/minecraft/core/LegacyRegistryContext"));
            Class<?> saves = game.loadClass(embeddedName(fd, "com/retromod/legacyapi/forge/items/LegacyForgeItemHandlers"));
            Object previous = context.getMethod("enter", game.loadClass("net.minecraft.core.HolderLookup$Provider"))
                    .invoke(null, mc.registries());
            try {
                Object tag = saves.getMethod("serializeNBT", Object.class).invoke(null, handler);
                Object loaded = createHandler.invoke(pot);
                saves.getMethod("deserializeNBT", Object.class, game.loadClass("net.minecraft.nbt.CompoundTag")).invoke(null, loaded, tag);
                assertEquals(mc.item("minecraft:brown_mushroom"), mc.itemIn(loaded, 1), "the saved handler loads with its items");

                // A 1.20.1 world saved each slot with a byte Count and its NBT: both survive the load.
                Object old = mc.oldHandlerTag();
                Object fromOldWorld = createHandler.invoke(pot);
                saves.getMethod("deserializeNBT", Object.class, game.loadClass("net.minecraft.nbt.CompoundTag")).invoke(null, fromOldWorld, old);
                Object stack = fromOldWorld.getClass().getMethod("getStackInSlot", int.class).invoke(fromOldWorld, 2);
                assertEquals(mc.item("minecraft:red_mushroom"), stack.getClass().getMethod("getItem").invoke(stack));
                assertEquals(5, stack.getClass().getMethod("getCount").invoke(stack), "the byte Count is read");
                assertEquals("Stew Base", mc.customName(stack), "the tag's display name became the custom name component");
            } finally {
                context.getMethod("exit", Object.class).invoke(null, previous);
            }

            // A tank's fluid saved into and loaded from its block entity's own tag, as Forge mods do, and one a 1.20.1 world saved.
            Class<?> tankContext = game.loadClass(embeddedName(tank, "com/retromod/legacyapi/minecraft/core/LegacyRegistryContext"));
            Object outer = tankContext.getMethod("enter", game.loadClass("net.minecraft.core.HolderLookup$Provider")).invoke(null, mc.registries());
            try {
                Class<?> tankType = game.loadClass("mod.Tank");
                Class<?> compound = game.loadClass("net.minecraft.nbt.CompoundTag");
                Class<?> fluidStack = game.loadClass("net.neoforged.neoforge.fluids.FluidStack");
                Method save = tankType.getMethod("save", fluidStack, compound);
                Method load = tankType.getMethod("load", compound);

                Object root = mc.parseTag("{id: \"farmersdelight:cooking_pot\", x: 0, y: 64, z: 0, components: {\"farmersdelight:marker\": 1b}}");
                String ownComponents = mc.text(root, "components");
                assertSame(root, save.invoke(null, mc.fluidStack("WATER", 1000), root), "the stack is written into the tag the mod passed, as Forge did");
                assertEquals("\"farmersdelight:cooking_pot\"", mc.text(root, "id"), "the block entity's id is left alone");
                assertEquals(ownComponents, mc.text(root, "components"), "the block entity's components are left alone");
                assertEquals("\"minecraft:water\"", mc.text(root, "FluidName"));
                assertEquals("1000", mc.text(root, "Amount"));
                Object back = load.invoke(null, root);
                assertEquals(1000, back.getClass().getMethod("getAmount").invoke(back));
                assertEquals(mc.fluid("WATER"), back.getClass().getMethod("getFluid").invoke(back));

                // Saving an empty tank over the same tag leaves no water behind, as in Forge.
                save.invoke(null, fluidStack.getField("EMPTY").get(null), root);
                assertEquals("\"minecraft:empty\"", mc.text(root, "FluidName"));
                assertEquals("0", mc.text(root, "Amount"));
                assertTrue(isEmpty(load.invoke(null, root)), "the emptied tank loads empty");

                Object old = load.invoke(null, mc.parseTag("{id: \"farmersdelight:cooking_pot\", x: 0, y: 64, z: 0,"
                        + " FluidName: \"minecraft:water\", Amount: 500, Tag: {Potion: \"minecraft:healing\"}}"));
                assertEquals(500, old.getClass().getMethod("getAmount").invoke(old), "a 1.20.1 block entity's FluidName and Amount are read");
                assertTrue(isEmpty(load.invoke(null, mc.parseTag("{FluidName: \"minecraft:empty\", Amount: 0}"))),
                        "Forge's empty tank loads empty");
                assertTrue(isEmpty(load.invoke(null, mc.parseTag("{id: \"farmersdelight:cooking_pot\", x: 0, y: 64, z: 0}"))),
                        "a tag without FluidName loads empty, as in Forge");
            } finally {
                tankContext.getMethod("exit", Object.class).invoke(null, outer);
            }
        }
    }

    private static boolean isEmpty(Object fluidStack) throws Exception {
        return (boolean) fluidStack.getClass().getMethod("isEmpty").invoke(fluidStack);
    }

    /** A Forge 1.20.1 tank that saves and loads its fluid through FluidStack's own calls. */
    private Path tankMod() throws Exception {
        String stack = "Lnet/minecraftforge/fluids/FluidStack;";
        String compound = "Lnet/minecraft/nbt/CompoundTag;";
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER, "mod/Tank", null,
                "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor save = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "save", "(" + stack + compound + ")" + compound, null, null);
        save.visitCode();
        save.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        save.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        save.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fluids/FluidStack", "writeToNBT",
                "(" + compound + ")" + compound, false);
        save.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        org.objectweb.asm.MethodVisitor load = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "load", "(" + compound + ")" + stack, null, null);
        load.visitCode();
        load.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        load.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraftforge/fluids/FluidStack", "loadFluidStackFromNBT",
                "(" + compound + ")" + stack, false);
        load.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        cw.visitEnd();
        Path jar = Files.createDirectories(dir.resolve("tank-in")).resolve("tankmod-1.20.1.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"tankmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.tankmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
                + "[[dependencies.tankmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\n";
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.putNextEntry(new JarEntry("mod/Tank.class"));
            out.write(cw.toByteArray());
        }
        return jar;
    }

    /** The host jars: the game, its libraries and NeoForge's own jar. */
    private static List<Path> hostJars(String version) {
        List<Path> jars = new ArrayList<>();
        if (version.equals("26.3")) {
            for (String jar : List.of("com/mojang/minecraft/26.3/minecraft-26.3-client.jar",
                    "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar", "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
                    "com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar", "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar",
                    "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar", "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
                    "com/mojang/logging/1.7.12/logging-1.7.12.jar", "com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar",
                    "com/mojang/authlib/10.0.77/authlib-10.0.77.jar", "org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar",
                    "io/netty/netty-buffer/4.2.16.Final/netty-buffer-4.2.16.Final.jar",
                    "io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar",
                    "io/netty/netty-codec-base/4.2.16.Final/netty-codec-base-4.2.16.Final.jar",
                    "io/netty/netty-transport/4.2.16.Final/netty-transport-4.2.16.Final.jar",
                    "io/netty/netty-handler/4.2.16.Final/netty-handler-4.2.16.Final.jar", "org/joml/joml/1.10.9/joml-1.10.9.jar",
                    "com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar", "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
                    "com/ibm/icu/icu4j/78.3/icu4j-78.3.jar", "commons-io/commons-io/2.20.0/commons-io-2.20.0.jar",
                    "net/neoforged/neoforge/26.3.0.43-beta/neoforge-26.3.0.43-beta-universal.jar",
                    "net/neoforged/fancymodloader/loader/12.0.8/loader-12.0.8.jar", "net/neoforged/bus/8.0.5/bus-8.0.5.jar")) {
                jars.add(PRISM.resolve(jar));
            }
        } else {
            for (String jar : List.of("net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar",
                    "net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-extra.jar",
                    "com/mojang/datafixerupper/8.0.16/datafixerupper-8.0.16.jar", "it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar",
                    "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar", "com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar",
                    "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar", "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
                    "com/mojang/logging/1.2.7/logging-1.2.7.jar", "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
                    "com/mojang/authlib/6.0.54/authlib-6.0.54.jar", "org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar",
                    "io/netty/netty-buffer/4.1.97.Final/netty-buffer-4.1.97.Final.jar",
                    "io/netty/netty-common/4.1.97.Final/netty-common-4.1.97.Final.jar",
                    "io/netty/netty-codec/4.1.97.Final/netty-codec-4.1.97.Final.jar",
                    "io/netty/netty-transport/4.1.97.Final/netty-transport-4.1.97.Final.jar",
                    "io/netty/netty-handler/4.1.97.Final/netty-handler-4.1.97.Final.jar", "org/joml/joml/1.10.5/joml-1.10.5.jar",
                    "commons-io/commons-io/2.15.1/commons-io-2.15.1.jar",
                    "net/neoforged/neoforge/21.1.252/neoforge-21.1.252-universal.jar",
                    "net/neoforged/fancymodloader/loader/4.0.44/loader-4.0.44.jar", "net/neoforged/bus/8.0.5/bus-8.0.5.jar",
                    "org/apache/logging/log4j/log4j-api/2.22.1/log4j-api-2.22.1.jar")) {
                jars.add(NF1211.resolve(jar));
            }
        }
        for (Path jar : jars) assumeTrue(Files.isRegularFile(jar), "no local " + jar.getFileName() + "; the game jars are local test material");
        return jars;
    }

    /** Farmer's Delight translated by the CLI for NeoForge {@code version}, reading the host as a running game does. */
    private Path translate(String version, List<URL> host) throws Exception {
        return translate(FD, version, host);
    }

    /** {@code mod} translated the same way. */
    private Path translate(Path mod, String version, List<URL> host) throws Exception {
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        McReflect.setForceNeoForge(true);
        Path output = Files.createDirectories(dir.resolve(version)).resolve(mod.getFileName());
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        // Retromod's own classes and shim services stay visible through the parent; the host answers the rest.
        try (URLClassLoader view = new URLClassLoader(host.toArray(new URL[0]), ForgeTransferApiHostTest.class.getClassLoader())) {
            thread.setContextClassLoader(view);
            CliHarness.transform(mod, output, version);
        } finally {
            thread.setContextClassLoader(saved);
        }
        assertTrue(Files.isRegularFile(output), "the CLI must write " + output);
        return output;
    }

    /** The wrapper type the translated pot's recipe lookup takes. */
    private static String wrapperName(Path jar) throws Exception {
        ClassNode pot = read(jar, POT);
        for (MethodNode method : pot.methods) {
            if (method.name.equals("getMatchingRecipe")) return Type.getArgumentTypes(method.desc)[0].getClassName();
        }
        throw new AssertionError("the pot has no getMatchingRecipe");
    }

    /** The name the translated jar embedded {@code internalName} under. */
    private static String embeddedName(Path jar, String internalName) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().map(JarEntry::getName).filter(name -> name.endsWith("/" + internalName + ".class")).findFirst()
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .orElseThrow(() -> new AssertionError(jar.getFileName() + " carries no " + internalName));
        }
    }

    private static ClassNode read(Path jar, String internalName) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(internalName + ".class");
            assertNotNull(entry, jar.getFileName() + " has " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }

    /** The bootstrapped game in its own loader, reached through reflection. */
    private static final class Game {
        private final ClassLoader loader;
        private final boolean bindsLate;

        Game(ClassLoader loader, String version) throws Exception {
            this.loader = loader;
            this.bindsLate = version.equals("26.3");
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
            // 26.x binds item components when a world loads; this binds the stack size of the items used here.
            if (bindsLate) {
                for (String id : List.of("minecraft:red_mushroom", "minecraft:brown_mushroom", "minecraft:mushroom_stew", "minecraft:bowl")) {
                    bind(id);
                }
                // Fluids get theirs at the same time, and water has none.
                Class<?> components = type("net.minecraft.core.component.DataComponentMap");
                Object waterHolder = type("net.minecraft.world.level.material.Fluid").getMethod("builtInRegistryHolder").invoke(fluid("WATER"));
                waterHolder.getClass().getMethod("bindComponents", components).invoke(waterHolder, components.getField("EMPTY").get(null));
            }
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        /** An instance made without its constructor, as a test cannot give a block entity a level and position. */
        Object allocate(String internalName) throws Exception {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type(internalName.replace('/', '.')));
        }

        Object item(String id) throws Exception {
            Object items = type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
            Class<?> idType = idType();
            Object key = idType.getMethod("parse", String.class).invoke(null, id);
            for (Method get : type("net.minecraft.core.Registry").getMethods()) {
                if ((get.getName().equals("getValue") || get.getName().equals("get")) && get.getParameterCount() == 1
                        && get.getParameterTypes()[0] == idType) {
                    return get.invoke(items, key);
                }
            }
            throw new AssertionError("the item registry has no lookup by id");
        }

        Object stack(String id) throws Exception {
            return type("net.minecraft.world.item.ItemStack").getConstructor(type("net.minecraft.world.level.ItemLike")).newInstance(item(id));
        }

        void put(Object handler, int slot, String id) throws Exception {
            handler.getClass().getMethod("setStackInSlot", int.class, type("net.minecraft.world.item.ItemStack"))
                    .invoke(handler, slot, stack(id));
        }

        Object itemIn(Object handler, int slot) throws Exception {
            Object stack = handler.getClass().getMethod("getStackInSlot", int.class).invoke(handler, slot);
            return stack.getClass().getMethod("getItem").invoke(stack);
        }

        /** Farmer's Delight's mushroom stew recipe, built with its own constructor from the host's ingredients. */
        Object cookingRecipe(Class<?> recipeType) throws Exception {
            Constructor<?> constructor = recipeType.getDeclaredConstructors()[0];
            Class<?>[] parameters = constructor.getParameterTypes();
            Class<?> itemLike = type("net.minecraft.world.level.ItemLike");
            Object ingredients = type("net.minecraft.core.NonNullList").getMethod("create").invoke(null);
            for (String id : List.of("minecraft:brown_mushroom", "minecraft:red_mushroom")) {
                Object one = Array.newInstance(itemLike, 1);
                Array.set(one, 0, item(id));
                Object ingredient = type("net.minecraft.world.item.crafting.Ingredient").getMethod("of", one.getClass()).invoke(null, one);
                ingredients.getClass().getMethod("add", Object.class).invoke(ingredients, ingredient);
            }
            Object id = idType().getMethod("parse", String.class).invoke(null, "farmersdelight:cooking/mushroom_stew");
            Object tab = parameters[2].getEnumConstants()[0];
            return constructor.newInstance(id, "", tab, ingredients, stack("minecraft:mushroom_stew"), stack("minecraft:bowl"), 1.0F, 200);
        }

        /** A handler tag as Forge 1.20.1 saved it: five red mushrooms in slot 2, named through their tag. */
        Object oldHandlerTag() throws Exception {
            return parseTag("{Size: 9, Items: [{Slot: 2, id: \"minecraft:red_mushroom\", Count: 5b,"
                    + " tag: {display: {Name: '{\"text\":\"Stew Base\"}'}}}]}");
        }

        Object fluid(String name) throws Exception {
            return type("net.minecraft.world.level.material.Fluids").getField(name).get(null);
        }

        /** NeoForge's {@code new FluidStack(fluid, amount)}. */
        Object fluidStack(String fluid, int amount) throws Exception {
            return type("net.neoforged.neoforge.fluids.FluidStack").getConstructor(type("net.minecraft.world.level.material.Fluid"), int.class)
                    .newInstance(fluid(fluid), amount);
        }

        /** The text form of one key's value in a compound. */
        String text(Object tag, String key) throws Exception {
            Object value = tag.getClass().getMethod("get", String.class).invoke(tag, key);
            assertNotNull(value, key + " is in the tag");
            return value.toString();
        }

        /** An NBT compound read from its text form. */
        Object parseTag(String text) throws Exception {
            Class<?> parser = type("net.minecraft.nbt.TagParser");
            for (Method parse : parser.getMethods()) {
                // 1.21.1 names it parseTag; later versions parseCompoundFully.
                if ((parse.getName().equals("parseTag") || parse.getName().equals("parseCompoundFully")) && parse.getParameterCount() == 1
                        && parse.getParameterTypes()[0] == String.class && java.lang.reflect.Modifier.isStatic(parse.getModifiers())) {
                    return parse.invoke(null, text);
                }
            }
            throw new AssertionError("no tag parser on this host");
        }

        /** The stack's custom name as plain text, or null. */
        String customName(Object stack) throws Exception {
            Object type = type("net.minecraft.core.component.DataComponents").getField("CUSTOM_NAME").get(null);
            Object name = stack.getClass().getMethod("get", type("net.minecraft.core.component.DataComponentType")).invoke(stack, type);
            return name == null ? null : (String) type("net.minecraft.network.chat.Component").getMethod("getString").invoke(name);
        }

        /** The registries of the built-in game, which a save of plain items needs. */
        Object registries() throws Exception {
            return type("net.minecraft.core.RegistryAccess").getMethod("fromRegistryOfRegistries", type("net.minecraft.core.Registry"))
                    .invoke(null, type("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY").get(null));
        }

        private Class<?> idType() throws ClassNotFoundException {
            try {
                return type("net.minecraft.resources.Identifier");
            } catch (ClassNotFoundException beforeTheRename) {
                return type("net.minecraft.resources.ResourceLocation");
            }
        }

        private void bind(String id) throws Exception {
            Class<?> components = type("net.minecraft.core.component.DataComponentMap");
            Object maxSize = type("net.minecraft.core.component.DataComponents").getField("MAX_STACK_SIZE").get(null);
            Object holder = type("net.minecraft.world.item.Item").getMethod("builtInRegistryHolder").invoke(item(id));
            Object builder = components.getMethod("builder").invoke(null);
            builder.getClass().getMethod("set", type("net.minecraft.core.component.DataComponentType"), Object.class).invoke(builder, maxSize, 64);
            holder.getClass().getMethod("bindComponents", components).invoke(holder, builder.getClass().getMethod("build").invoke(builder));
        }
    }
}
