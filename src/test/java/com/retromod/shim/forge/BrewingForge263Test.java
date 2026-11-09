/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 66 removed the brewing recipe classes and registry with 26.3's recipe files; old recipes are kept and written as files. */
class BrewingForge263Test {

    private static final String MOD = "mod/Brews";
    private static final String PICKY = "mod/PickyRecipe";
    private static final String FORGE_RECIPE_CLASS = "net/minecraftforge/common/brewing/BrewingRecipe";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    private static final String NEW_RECIPE = "(" + INGREDIENT + INGREDIENT + STACK + ")V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    // The mod's own renamed copy of the brewing store, as its jar lists it.
    private String storeDescriptor;

    @BeforeEach
    void forge263() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
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

    @Test
    @DisplayName("A recipe added on Forge 26.3 starts the pack listeners on Forge's own event buses, for the mod that owns it")
    void storeListensOnForgeBuses() throws Exception {
        ClassLoader game = translatedGame();
        Class<?> brews = game.loadClass("mod.Brews");
        Class<?> mods = game.loadClass("net.minecraftforge.fml.ModList");
        Object other = mods.getMethod("add", String.class, String[].class)
                .invoke(null, "othermod", new String[] {"Lother/Thing;"});
        Object owner = addMod(mods, "brewmod", storeDescriptor);

        Object recipe = invoke(brews, "make", ingredient(game, "apple"), ingredient(game, "diamond"), stack(game, "golden_apple"));
        assertEquals(true, invoke(brews, "add", recipe));
        invoke(brews, "add", invoke(brews, "make", ingredient(game, "apple"), ingredient(game, "emerald"), stack(game, "coal")));

        assertEquals(1, listeners(game, "net.minecraftforge.event.AddPackFindersEvent"), "the data pack is offered once");
        assertEquals(1, listeners(game, "net.minecraftforge.event.server.ServerStartedEvent"), "the recipe files are written once");
        assertTrue(asked(owner) > 0, "the pack is named after the mod whose jar holds the store");
        assertEquals(0, asked(other));
    }

    @Test
    @DisplayName("The three-argument add names the pack after the mod's jar, never Minecraft, whose class the output stack is")
    void threeArgumentAddUsesTheModsJar() throws Exception {
        ClassLoader game = translatedGame();
        Class<?> mods = game.loadClass("net.minecraftforge.fml.ModList");
        Object minecraft = addMod(mods, "minecraft", STACK, INGREDIENT);
        Object owner = addMod(mods, "brewmod", storeDescriptor);

        assertEquals(true, invoke(game.loadClass("mod.Brews"), "addTriple", ingredient(game, "apple"),
                ingredient(game, "diamond"), stack(game, "golden_apple")));

        assertEquals(1, listeners(game, "net.minecraftforge.event.AddPackFindersEvent"));
        assertEquals(1, listeners(game, "net.minecraftforge.event.server.ServerStartedEvent"));
        assertTrue(asked(owner) > 0, "the pack is named after the mod whose jar holds the store");
        assertEquals(0, asked(minecraft), "nothing is written under Minecraft's namespace");
    }

    @Test
    @DisplayName("With no jar holding the store and no mod active, no pack is offered rather than one under minecraft")
    void noOwnerOffersNoPack() throws Exception {
        ClassLoader game = translatedGame();
        Object minecraft = addMod(game.loadClass("net.minecraftforge.fml.ModList"), "minecraft", STACK, INGREDIENT);

        // The loader answers minecraft for the active namespace when no mod is active, as in a deferred setup task.
        invoke(game.loadClass("mod.Brews"), "addTriple", ingredient(game, "apple"), ingredient(game, "diamond"),
                stack(game, "golden_apple"));

        assertEquals(0, listeners(game, "net.minecraftforge.event.AddPackFindersEvent"));
        assertEquals(0, listeners(game, "net.minecraftforge.event.server.ServerStartedEvent"));
        assertEquals(0, asked(minecraft));
    }

    @Test
    @DisplayName("The rebuilt BrewingRecipe brews a copy of its output, and a subclass's own checks still decide")
    void rebuiltRecipeKeepsForgesBehavior() throws Exception {
        ClassLoader game = translatedGame();
        Class<?> brews = game.loadClass("mod.Brews");
        Object recipe = invoke(brews, "make", ingredient(game, "apple"), ingredient(game, "diamond"), stack(game, "golden_apple"));
        String type = recipe.getClass().getName();
        assertTrue(type.startsWith(SyntheticEmbedder.PREFIX.replace('/', '.')) && type.endsWith(".LegacySimpleBrewingRecipe"),
                "the mod builds its own embedded copy: " + type);

        Object output = call(recipe, "getOutput", stack(game, "apple"), stack(game, "diamond"));
        assertEquals("golden_apple", output.toString());
        assertNotSame(call(recipe, "getOutput"), output, "each brew hands out a copy");
        assertEquals("empty", call(recipe, "getOutput", stack(game, "diamond"), stack(game, "apple")).toString());
        assertEquals(true, call(recipe, "isInput", stack(game, "apple")));
        assertEquals(true, call(recipe, "isIngredient", stack(game, "diamond")));
        assertEquals("apple", call(call(recipe, "getInput"), "id"));
        assertEquals("diamond", call(call(recipe, "getIngredient"), "id"));

        // A subclass that refuses every input, as Alex's Mobs narrows its own, brews nothing.
        Object picky = invoke(brews, "makePicky", ingredient(game, "apple"), ingredient(game, "diamond"), stack(game, "golden_apple"));
        assertEquals("empty", call(picky, "getOutput", stack(game, "apple"), stack(game, "diamond")).toString());
    }

    @Test
    @DisplayName("Only a Forge host without the recipe interface takes the recipe file bridge")
    void bridgeFollowsTheHost() {
        Function<String, ClassNode> forge263 = host(ForgeHostShape.ANCHOR);
        Function<String, ClassNode> forge262 = host(ForgeHostShape.ANCHOR, LegacyBrewingRegistryBridge.FORGE_RECIPE);
        assertTrue(registers(forge263));
        assertEquals(LegacyBrewingBridge.SIMPLE_RECIPE, transformer.getClassRedirects().get(FORGE_RECIPE_CLASS));
        assertEquals(LegacyBrewingBridge.RECIPE, transformer.getClassRedirects().get(LegacyBrewingRegistryBridge.FORGE_RECIPE));
        assertFalse(registers(forge262), "Forge 26.2 keeps the recipe classes and gets the event bridge");
        assertTrue(registers(host()), "an offline Forge 26.3 target");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(registers(host()), "an offline Forge 26.2 target");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceNeoForge(true);
        assertFalse(registers(forge263), "NeoForge registers the same redirects through its own migration");
    }

    @Test
    @DisplayName("The rebuilt BrewingRecipe passes the stack verifier")
    void rebuiltRecipeVerifies() throws Exception {
        ClassNode recipe = new ClassNode();
        new ClassReader(LegacyBrewingBridge.generateSimpleRecipe()).accept(recipe, 0);
        assertEquals(List.of(LegacyBrewingBridge.RECIPE), recipe.interfaces);
        for (MethodNode method : recipe.methods) new Analyzer<>(new BasicVerifier()).analyze(recipe.name, method);
    }

    // ---- registration ----

    private boolean registers(Function<String, ClassNode> host) {
        transformer.clearRedirectsForTesting();
        return LegacyBrewingBridge.registerForge(transformer, host);
    }

    private static Function<String, ClassNode> host(String... classes) {
        Set<String> present = Set.of(classes);
        return name -> {
            if (!present.contains(name)) return null;
            ClassNode node = new ClassNode();
            node.name = name;
            return node;
        };
    }

    // ---- the translated mod against Forge 26.3 stand-ins ----

    /** The mod translated as RetromodForge translates it on Forge 26.3, loaded beside the host stand-ins. */
    private ClassLoader translatedGame() throws Exception {
        new Forge_26_2_to_26_3().registerRedirects(transformer);
        Path input = dir.resolve("brews.jar");
        Files.write(input, BrewingRegistryForge262Test.jar(Map.of("META-INF/mods.toml", BrewingRegistryForge262Test.toml("brewmod"),
                MOD + ".class", brewsClass(), PICKY + ".class", pickyClass())));
        Path out = new ForgeModTransformer("26.3").transformMod(input, Files.createDirectories(dir.resolve("out")));
        assertNotNull(out, "the mod translated");
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile file = new JarFile(out.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;
                String internal = name.substring(0, name.length() - ".class".length());
                classes.put(internal, file.getInputStream(entry).readAllBytes());
                if (internal.endsWith("/shim/forge/embedded/LegacyBrewingStore")) storeDescriptor = "L" + internal + ";";
            }
        }
        assertNotNull(storeDescriptor, "the jar carries its own copy of the store");
        return forge263Host().loader(dir, transformer, classes);
    }

    private static Object addMod(Class<?> mods, String namespace, String... classes) throws Exception {
        return mods.getMethod("add", String.class, String[].class).invoke(null, namespace, classes);
    }

    private static int asked(Object mod) throws Exception {
        return (int) mod.getClass().getField("asked").get(mod);
    }

    private static Object stack(ClassLoader game, String id) throws Exception {
        return game.loadClass("net.minecraft.world.item.ItemStack").getConstructor(String.class).newInstance(id);
    }

    private static Object ingredient(ClassLoader game, String id) throws Exception {
        return game.loadClass("net.minecraft.world.item.crafting.Ingredient").getConstructor(String.class).newInstance(id);
    }

    private static int listeners(ClassLoader game, String event) throws Exception {
        Object bus = game.loadClass(event).getField("BUS").get(null);
        return ((List<?>) bus.getClass().getField("listeners").get(bus)).size();
    }

    /** Forge 26.3 in miniature: no brewing classes, a static mod list, and events with their own buses. */
    private static ForgeHostStandIns forge263Host() {
        return new ForgeHostStandIns()
                .add("net/minecraft/world/item/ItemStack.java", """
                        package net.minecraft.world.item;
                        public class ItemStack {
                            public static final ItemStack EMPTY = new ItemStack("air", 0);
                            private final String id;
                            private final int count;
                            public ItemStack(String id, int count) { this.id = id; this.count = count; }
                            public ItemStack(String id) { this(id, 1); }
                            public String id() { return id; }
                            public boolean isEmpty() { return count <= 0; }
                            public ItemStack copy() { return isEmpty() ? EMPTY : new ItemStack(id, count); }
                            @Override public String toString() { return isEmpty() ? "empty" : id; }
                        }""")
                .add("net/minecraft/world/item/crafting/Ingredient.java", """
                        package net.minecraft.world.item.crafting;
                        import net.minecraft.world.item.ItemStack;
                        public final class Ingredient {
                            private final String id;
                            public Ingredient(String id) { this.id = id; }
                            public String id() { return id; }
                            public boolean test(ItemStack stack) { return stack != null && !stack.isEmpty() && stack.id().equals(id); }
                        }""")
                .add("net/minecraftforge/eventbus/api/bus/EventBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        public interface EventBus<T> { Object addListener(java.util.function.Consumer<T> listener); }""")
                .add("net/minecraftforge/eventbus/api/bus/ListBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        import java.util.ArrayList;
                        import java.util.List;
                        import java.util.function.Consumer;
                        public final class ListBus<T> implements EventBus<T> {
                            public final List<Consumer<T>> listeners = new ArrayList<>();
                            public Object addListener(Consumer<T> listener) { listeners.add(listener); return listener; }
                        }""")
                .add("net/minecraftforge/event/AddPackFindersEvent.java", """
                        package net.minecraftforge.event;
                        public final class AddPackFindersEvent {
                            public static final net.minecraftforge.eventbus.api.bus.EventBus<AddPackFindersEvent> BUS =
                                    new net.minecraftforge.eventbus.api.bus.ListBus<>();
                        }""")
                .add("net/minecraftforge/event/server/ServerStartedEvent.java", """
                        package net.minecraftforge.event.server;
                        public final class ServerStartedEvent {
                            public static final net.minecraftforge.eventbus.api.bus.EventBus<ServerStartedEvent> BUS =
                                    new net.minecraftforge.eventbus.api.bus.ListBus<>();
                        }""")
                .add("net/minecraftforge/forgespi/language/IModInfo.java", """
                        package net.minecraftforge.forgespi.language;
                        public interface IModInfo { IModFileInfo getOwningFile(); }""")
                .add("net/minecraftforge/forgespi/language/IModFileInfo.java", """
                        package net.minecraftforge.forgespi.language;
                        public interface IModFileInfo { net.minecraftforge.forgespi.locating.IModFile getFile(); }""")
                .add("net/minecraftforge/forgespi/locating/IModFile.java", """
                        package net.minecraftforge.forgespi.locating;
                        public interface IModFile { net.minecraftforge.forgespi.language.ModFileScanData getScanResult(); }""")
                .add("net/minecraftforge/forgespi/language/ModFileScanData.java", """
                        package net.minecraftforge.forgespi.language;
                        import java.util.LinkedHashSet;
                        import java.util.Set;
                        public class ModFileScanData {
                            private final Set<ClassData> classes = new LinkedHashSet<>();
                            public ModFileScanData(String... descriptors) {
                                for (String descriptor : descriptors) classes.add(new ClassData(descriptor));
                            }
                            public Set<ClassData> getClasses() { return classes; }
                            public record ClassData(String clazz) {}
                        }""")
                .add("net/minecraftforge/fml/ModContainer.java", """
                        package net.minecraftforge.fml;
                        public class ModContainer {
                            public int asked;
                            private final String namespace;
                            private final net.minecraftforge.forgespi.language.IModInfo info;
                            public ModContainer(String namespace, net.minecraftforge.forgespi.language.IModInfo info) {
                                this.namespace = namespace; this.info = info;
                            }
                            public final String getNamespace() { asked++; return namespace; }
                            String id() { return namespace; }
                            public net.minecraftforge.forgespi.language.IModInfo getModInfo() { return info; }
                        }""")
                .add("net/minecraftforge/fml/ModList.java", """
                        package net.minecraftforge.fml;
                        import java.util.ArrayList;
                        import java.util.List;
                        import java.util.Optional;
                        import net.minecraftforge.forgespi.language.ModFileScanData;
                        public final class ModList {
                            private static final List<ModContainer> LOADED = new ArrayList<>();
                            public static List<ModContainer> getLoadedMods() { return LOADED; }
                            public static Optional<ModContainer> getModContainerById(String id) {
                                return LOADED.stream().filter(mod -> mod.id().equals(id)).findFirst();
                            }
                            public static ModContainer add(String namespace, String... classes) {
                                ModFileScanData scan = new ModFileScanData(classes);
                                ModContainer mod = new ModContainer(namespace, () -> () -> () -> scan);
                                LOADED.add(mod);
                                return mod;
                            }
                        }""")
                .add("net/minecraftforge/fml/ModLoadingContext.java", """
                        package net.minecraftforge.fml;
                        public class ModLoadingContext {
                            private static final ModLoadingContext CONTEXT = new ModLoadingContext();
                            public static ModLoadingContext get() { return CONTEXT; }
                            public String getActiveNamespace() { return "minecraft"; }
                        }""")
                .add("net/minecraftforge/fml/loading/FMLPaths.java", """
                        package net.minecraftforge.fml.loading;
                        public enum FMLPaths {
                            GAMEDIR;
                            public java.nio.file.Path get() { return java.nio.file.Path.of("unused-game-dir"); }
                        }""");
    }

    // ---- the old mod ----

    /** {@code make} builds Forge's plain recipe, {@code makePicky} the mod's own subclass, and the adds register one. */
    private static byte[] brewsClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        construct(cw, "make", FORGE_RECIPE_CLASS);
        construct(cw, "makePicky", PICKY);
        forwardAdd(cw, "add", "(L" + LegacyBrewingRegistryBridge.FORGE_RECIPE + ";)Z");
        forwardAdd(cw, "addTriple", "(" + INGREDIENT + INGREDIENT + STACK + ")Z");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void forwardAdd(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        int arguments = org.objectweb.asm.Type.getArgumentTypes(desc).length;
        for (int i = 0; i < arguments; i++) mv.visitVarInsn(ALOAD, i);
        mv.visitMethodInsn(INVOKESTATIC, LegacyBrewingRegistryBridge.FORGE_REGISTRY, "addRecipe", desc, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void construct(ClassWriter cw, String name, String type) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(" + INGREDIENT + INGREDIENT + STACK + ")L" + FORGE_RECIPE_CLASS + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, type);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", NEW_RECIPE, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A recipe that extends Forge's and refuses every input. */
    private static byte[] pickyClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, PICKY, null, FORGE_RECIPE_CLASS, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", NEW_RECIPE, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitVarInsn(ALOAD, 3);
        init.visitMethodInsn(INVOKESPECIAL, FORGE_RECIPE_CLASS, "<init>", NEW_RECIPE, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor isInput = cw.visitMethod(ACC_PUBLIC, "isInput", "(" + STACK + ")Z", null, null);
        isInput.visitCode();
        isInput.visitInsn(ICONST_0);
        isInput.visitInsn(IRETURN);
        isInput.visitMaxs(0, 0);
        isInput.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new AssertionError(target.getClass().getName() + " has no " + name);
    }

    private static Object invoke(Class<?> owner, String name, Object... arguments) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(null, arguments);
            }
        }
        throw new AssertionError(owner.getName() + " has no " + name);
    }
}
