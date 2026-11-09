/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** On NeoForge 26.3 a Forge mod's three-argument brewing add names its pack after the mod's jar, never Minecraft. */
class BrewingNeoForge263Test {

    private static final String MOD = "mod/Brews";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    // Translated in place here, so the store keeps its own name; in a jar each mod has a renamed copy.
    private static final String STORE = "L" + LegacyBrewingBridge.STORE + ";";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void neoForge263() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
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
    @DisplayName("The three-argument add listens on the jar's mod bus and the game bus, and asks nothing of Minecraft")
    void threeArgumentAddUsesTheModsJar() throws Exception {
        ClassLoader game = translatedGame();
        Class<?> mods = game.loadClass("net.neoforged.fml.ModList");
        Object minecraft = addMod(mods, "minecraft", STACK, INGREDIENT);
        Object owner = addMod(mods, "brewmod", STORE);

        assertEquals(true, addTriple(game));

        assertEquals(List.of("AddPackFindersEvent"), events(owner.getClass().getField("bus").get(owner)));
        assertEquals(List.of("ServerStartedEvent"),
                events(game.loadClass("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null)));
        assertTrue((int) owner.getClass().getField("asked").get(owner) > 0);
        assertEquals(0, minecraft.getClass().getField("asked").get(minecraft), "nothing is written under Minecraft's namespace");
    }

    @Test
    @DisplayName("With no jar holding the store and no mod active, no pack is offered rather than one under minecraft")
    void noOwnerOffersNoPack() throws Exception {
        ClassLoader game = translatedGame();
        Object minecraft = addMod(game.loadClass("net.neoforged.fml.ModList"), "minecraft", STACK, INGREDIENT);

        addTriple(game);

        assertEquals(List.of(), events(minecraft.getClass().getField("bus").get(minecraft)));
        assertEquals(List.of(), events(game.loadClass("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null)));
        assertEquals(0, minecraft.getClass().getField("asked").get(minecraft));
    }

    private ClassLoader translatedGame() throws Exception {
        LegacyBrewingBridge.registerRedirects(transformer);
        return neoForge263Host().loader(dir, transformer, Map.of(MOD, transformer.transformClass(brewsClass(), MOD)));
    }

    private static Object addTriple(ClassLoader game) throws Exception {
        Class<?> ingredient = game.loadClass("net.minecraft.world.item.crafting.Ingredient");
        Class<?> stack = game.loadClass("net.minecraft.world.item.ItemStack");
        Method add = game.loadClass("mod.Brews").getMethod("addTriple", ingredient, ingredient, stack);
        return add.invoke(null, ingredient.getConstructor().newInstance(), ingredient.getConstructor().newInstance(),
                stack.getConstructor().newInstance());
    }

    private static Object addMod(Class<?> mods, String namespace, String... classes) throws Exception {
        return mods.getMethod("add", String.class, String[].class).invoke(null, namespace, classes);
    }

    private static List<?> events(Object bus) throws Exception {
        return (List<?>) bus.getClass().getField("events").get(bus);
    }

    /** NeoForge 26.3 in miniature: an instance mod list, a bus per mod, and the game bus. */
    private static ForgeHostStandIns neoForge263Host() {
        return new ForgeHostStandIns()
                .add("net/minecraft/world/item/ItemStack.java",
                        "package net.minecraft.world.item; public class ItemStack {}")
                .add("net/minecraft/world/item/crafting/Ingredient.java",
                        "package net.minecraft.world.item.crafting; public class Ingredient {}")
                .add("net/neoforged/bus/api/EventPriority.java", "package net.neoforged.bus.api; public enum EventPriority { NORMAL }")
                .add("net/neoforged/bus/api/IEventBus.java", """
                        package net.neoforged.bus.api;
                        public interface IEventBus {
                            <T> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> type,
                                    java.util.function.Consumer<T> listener);
                        }""")
                .add("net/neoforged/bus/api/ListBus.java", """
                        package net.neoforged.bus.api;
                        import java.util.ArrayList;
                        import java.util.List;
                        public final class ListBus implements IEventBus {
                            public final List<String> events = new ArrayList<>();
                            public <T> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> type,
                                    java.util.function.Consumer<T> listener) {
                                events.add(type.getSimpleName());
                            }
                        }""")
                .add("net/neoforged/neoforge/common/NeoForge.java", """
                        package net.neoforged.neoforge.common;
                        public class NeoForge { public static final net.neoforged.bus.api.IEventBus EVENT_BUS = new net.neoforged.bus.api.ListBus(); }""")
                .add("net/neoforged/neoforge/event/AddPackFindersEvent.java",
                        "package net.neoforged.neoforge.event; public class AddPackFindersEvent {}")
                .add("net/neoforged/neoforge/event/server/ServerStartedEvent.java",
                        "package net.neoforged.neoforge.event.server; public class ServerStartedEvent {}")
                .add("net/neoforged/neoforgespi/language/IModInfo.java", """
                        package net.neoforged.neoforgespi.language;
                        public interface IModInfo { IModFileInfo getOwningFile(); }""")
                .add("net/neoforged/neoforgespi/language/IModFileInfo.java", """
                        package net.neoforged.neoforgespi.language;
                        public interface IModFileInfo { net.neoforged.neoforgespi.locating.IModFile getFile(); }""")
                .add("net/neoforged/neoforgespi/locating/IModFile.java", """
                        package net.neoforged.neoforgespi.locating;
                        public interface IModFile { net.neoforged.neoforgespi.language.ModFileScanData getScanResult(); }""")
                .add("net/neoforged/neoforgespi/language/ModFileScanData.java", """
                        package net.neoforged.neoforgespi.language;
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
                .add("net/neoforged/fml/ModContainer.java", """
                        package net.neoforged.fml;
                        public class ModContainer {
                            public int asked;
                            public final net.neoforged.bus.api.ListBus bus = new net.neoforged.bus.api.ListBus();
                            private final String namespace;
                            private final net.neoforged.neoforgespi.language.IModInfo info;
                            public ModContainer(String namespace, net.neoforged.neoforgespi.language.IModInfo info) {
                                this.namespace = namespace; this.info = info;
                            }
                            public final String getNamespace() { asked++; return namespace; }
                            String id() { return namespace; }
                            public net.neoforged.neoforgespi.language.IModInfo getModInfo() { return info; }
                            public net.neoforged.bus.api.IEventBus getEventBus() { return bus; }
                        }""")
                .add("net/neoforged/fml/ModList.java", """
                        package net.neoforged.fml;
                        import java.util.ArrayList;
                        import java.util.List;
                        import java.util.Optional;
                        import net.neoforged.neoforgespi.language.ModFileScanData;
                        public final class ModList {
                            private static final ModList LIST = new ModList();
                            private final List<ModContainer> sorted = new ArrayList<>();
                            public static ModList get() { return LIST; }
                            public List<ModContainer> getSortedMods() { return sorted; }
                            public Optional<ModContainer> getModContainerById(String id) {
                                return sorted.stream().filter(mod -> mod.id().equals(id)).findFirst();
                            }
                            public static ModContainer add(String namespace, String... classes) {
                                ModFileScanData scan = new ModFileScanData(classes);
                                ModContainer mod = new ModContainer(namespace, () -> () -> () -> scan);
                                LIST.sorted.add(mod);
                                return mod;
                            }
                        }""")
                .add("net/neoforged/fml/ModLoadingContext.java", """
                        package net.neoforged.fml;
                        public class ModLoadingContext {
                            private static final ModLoadingContext CONTEXT = new ModLoadingContext();
                            public static ModLoadingContext get() { return CONTEXT; }
                            public String getActiveNamespace() { return "minecraft"; }
                        }""")
                .add("net/neoforged/fml/loading/FMLPaths.java", """
                        package net.neoforged.fml.loading;
                        public enum FMLPaths {
                            GAMEDIR;
                            public java.nio.file.Path get() { return java.nio.file.Path.of("unused-game-dir"); }
                        }""");
    }

    /** {@code addTriple} is Forge's three-argument {@code BrewingRecipeRegistry.addRecipe}. */
    private static byte[] brewsClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        String desc = "(" + INGREDIENT + INGREDIENT + STACK + ")Z";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addTriple", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, LegacyBrewingRegistryBridge.FORGE_REGISTRY, "addRecipe", desc, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
