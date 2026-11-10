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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 removed the static BrewingRecipeRegistry; old mods fill and read it through the host's brewing event and brewing. */
class BrewingRegistryForge262Test {

    private static final String MOD = "mod/Brews";
    private static final String REGISTRY = LegacyBrewingRegistryBridge.FORGE_REGISTRY;
    private static final String RECIPE = LegacyBrewingRegistryBridge.FORGE_RECIPE;
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

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

    @Test
    @DisplayName("Recipes added at setup reach the server's brewing in order, and getOutput takes the input first")
    void registryReachesTheServersBrewing() throws Exception {
        ClassLoader game = translatedGame();
        Class<?> brews = game.loadClass("mod.Brews");
        Object apple = stack(game, "apple");
        Object diamond = stack(game, "diamond");

        // Before a server or connection builds brewing, the lookups answer like an empty registry.
        assertEquals(List.of(), invoke(brews, "recipes"));
        assertEquals("empty", invoke(brews, "output", apple, diamond).toString());
        assertEquals(false, invoke(brews, "has", apple, diamond));

        // Two recipes take an apple and a diamond, so the first one added must win.
        assertEquals(true, invoke(brews, "add", recipe(game, "apple", "diamond", "golden_apple")));
        assertEquals(true, invoke(brews, "addTriple", ingredient(game, "apple"), ingredient(game, "emerald"),
                stack(game, "enchanted_apple")));
        invoke(brews, "add", recipe(game, "apple", "diamond", "coal"));
        startServer(game);

        List<?> recipes = (List<?>) invoke(brews, "recipes");
        assertEquals(List.of("VanillaBrewingRecipe", "BrewingRecipe", "BrewingRecipe", "BrewingRecipe"),
                recipes.stream().map(r -> r.getClass().getSimpleName()).toList(), "Minecraft's recipe first, then the mod's in order");
        assertEquals(List.of("golden_apple", "enchanted_apple", "coal"),
                recipes.subList(1, 4).stream().map(r -> String.valueOf(call(r, "getOutput"))).toList());

        assertEquals("golden_apple", invoke(brews, "output", apple, diamond).toString());
        assertEquals("empty", invoke(brews, "output", diamond, apple).toString(),
                "Forge 1.20.1 took the bottle first and the ingredient second");
        assertEquals("awkward_potion", invoke(brews, "output", stack(game, "water_bottle"), stack(game, "nether_wart")).toString());
        assertEquals(true, invoke(brews, "has", apple, diamond));
        assertEquals(false, invoke(brews, "has", diamond, apple));
        assertEquals(true, invoke(brews, "validInput", apple));
        assertEquals(false, invoke(brews, "validInput", diamond));
        assertEquals(true, invoke(brews, "validIngredient", diamond));
        assertEquals(false, invoke(brews, "validIngredient", game.loadClass("net.minecraft.world.item.ItemStack")
                .getField("EMPTY").get(null)));
    }

    @Test
    @DisplayName("A client connection's brewing answers without a server, and every rebuild gets the recipes again")
    void everyBrewingRebuildGetsTheRecipes() throws Exception {
        ClassLoader game = translatedGame();
        Class<?> brews = game.loadClass("mod.Brews");
        invoke(brews, "add", recipe(game, "apple", "diamond", "golden_apple"));

        Class<?> minecraft = game.loadClass("net.minecraft.client.Minecraft");
        Object client = minecraft.getConstructor().newInstance();
        minecraft.getField("connection").set(client,
                game.loadClass("net.minecraft.client.multiplayer.ClientPacketListener").getConstructor().newInstance());
        minecraft.getField("instance").set(null, client);
        assertEquals("golden_apple", invoke(brews, "output", stack(game, "apple"), stack(game, "diamond")).toString());

        // An integrated server builds its own brewing, which the lookups prefer.
        startServer(game);
        assertEquals(2, game.loadClass("net.minecraft.world.item.alchemy.PotionBrewing").getField("built").get(null));
        assertEquals(2, ((List<?>) invoke(brews, "recipes")).size());
        assertEquals("golden_apple", invoke(brews, "output", stack(game, "apple"), stack(game, "diamond")).toString());
    }

    @Test
    @DisplayName("Only a Forge host with the brewing event and recipe classes, and no static registry, takes the bridge")
    void bridgeFollowsTheHost() {
        Function<String, ClassNode> forge262 = host(ForgeHostShape.ANCHOR, LegacyBrewingRegistryBridge.FORGE_EVENT, RECIPE);
        Function<String, ClassNode> forge263 = host(ForgeHostShape.ANCHOR);
        Function<String, ClassNode> forge1201 = host(ForgeHostShape.ANCHOR, REGISTRY, RECIPE);
        Function<String, ClassNode> offline = host();

        assertTrue(registers(forge262), "Forge 26.2");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyBrewingRegistryBridge.HELPER));
        assertFalse(registers(forge263), "Forge 26.3 has no brewing event; LegacyBrewingBridge owns it");
        assertFalse(registers(forge1201), "Forge 1.20.1 still has the registry");
        assertTrue(registers(offline), "an offline Forge 26.2 target");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertFalse(registers(offline), "an offline Forge 26.3 target takes the recipe file bridge instead");
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(registers(offline), "an offline Forge 1.21.11 target");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        assertFalse(registers(forge262), "NeoForge bridges the adds in its own migration");
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        assertFalse(registers(forge262), "Fabric and Quilt are not Forge targets");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths send the registry calls to a copy embedded in the jar")
    void everyPathEmbedsTheHelper(String host) throws Exception {
        Path input = dir.resolve("brews.jar");
        Files.write(input, jar(Map.of("META-INF/mods.toml", toml("brewmod"), MOD + ".class", brewsClass())));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("brews.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        // 26.3 keeps brewing as recipe files, so only the adds are recorded there; its lookups stay unbridged.
        Map<String, String> expected = host.equals("26.2")
                ? Map.of("add", "embedded LegacyBrewingRecipes.addRecipe",
                        "output", "embedded LegacyBrewingRecipes.getOutput, cast to ItemStack")
                : Map.of("add", "embedded LegacyBrewingStore.addRecipe",
                        "output", REGISTRY + ".getOutput");
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(new TreeMap<>(expected), output.getValue(), output.getKey());
        }
    }

    // ---- registration ----

    private boolean registers(Function<String, ClassNode> host) {
        transformer.clearRedirectsForTesting();
        return LegacyBrewingRegistryBridge.registerForge(transformer, host);
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

    // ---- the translated mod against Forge 26.2 stand-ins ----

    /** The mod translated as RetromodForge translates it on Forge 26.2, loaded beside the host stand-ins. */
    private ClassLoader translatedGame() throws Exception {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        Path input = dir.resolve("brews.jar");
        Files.write(input, jar(Map.of("META-INF/mods.toml", toml("brewmod"), MOD + ".class", brewsClass())));
        Path out = new ForgeModTransformer("26.2").transformMod(input, Files.createDirectories(dir.resolve("out")));
        assertNotNull(out, "the mod translated");
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile file = new JarFile(out.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    classes.put(name.substring(0, name.length() - ".class".length()), file.getInputStream(entry).readAllBytes());
                }
            }
        }
        return forge262Host().loader(dir, transformer, classes);
    }

    private static void startServer(ClassLoader game) throws Exception {
        Object server = game.loadClass("net.minecraft.server.MinecraftServer").getConstructor().newInstance();
        game.loadClass("net.minecraftforge.server.ServerLifecycleHooks").getField("current").set(null, server);
    }

    private static Object stack(ClassLoader game, String id) throws Exception {
        return game.loadClass("net.minecraft.world.item.ItemStack").getConstructor(String.class).newInstance(id);
    }

    private static Object ingredient(ClassLoader game, String id) throws Exception {
        return game.loadClass("net.minecraft.world.item.crafting.Ingredient").getConstructor(String.class).newInstance(id);
    }

    private static Object recipe(ClassLoader game, String input, String ingredient, String output) throws Exception {
        Class<?> type = game.loadClass("net.minecraft.world.item.crafting.Ingredient");
        return game.loadClass("net.minecraftforge.common.brewing.BrewingRecipe")
                .getConstructor(type, type, game.loadClass("net.minecraft.world.item.ItemStack"))
                .newInstance(ingredient(game, input), ingredient(game, ingredient), stack(game, output));
    }

    /** Forge 26.2's brewing in miniature: PotionBrewing posts the event on every build and mixes ingredient first. */
    static ForgeHostStandIns forge262Host() {
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
                            public int getCount() { return count; }
                            public ItemStack copy() { return isEmpty() ? EMPTY : new ItemStack(id, count); }
                            @Override public String toString() { return isEmpty() ? "empty" : id; }
                        }""")
                .add("net/minecraft/world/item/crafting/Ingredient.java", """
                        package net.minecraft.world.item.crafting;
                        import net.minecraft.world.item.ItemStack;
                        public class Ingredient {
                            private final String id;
                            public Ingredient(String id) { this.id = id; }
                            public boolean test(ItemStack stack) { return stack != null && !stack.isEmpty() && stack.id().equals(id); }
                        }""")
                .add("net/minecraftforge/common/brewing/IBrewingRecipe.java", """
                        package net.minecraftforge.common.brewing;
                        import net.minecraft.world.item.ItemStack;
                        public interface IBrewingRecipe {
                            boolean isInput(ItemStack input);
                            boolean isIngredient(ItemStack ingredient);
                            ItemStack getOutput(ItemStack input, ItemStack ingredient);
                        }""")
                .add("net/minecraftforge/common/brewing/BrewingRecipe.java", """
                        package net.minecraftforge.common.brewing;
                        import net.minecraft.world.item.ItemStack;
                        import net.minecraft.world.item.crafting.Ingredient;
                        public class BrewingRecipe implements IBrewingRecipe {
                            private final Ingredient input;
                            private final Ingredient ingredient;
                            private final ItemStack output;
                            public BrewingRecipe(Ingredient input, Ingredient ingredient, ItemStack output) {
                                this.input = input; this.ingredient = ingredient; this.output = output;
                            }
                            public boolean isInput(ItemStack stack) { return input.test(stack); }
                            public boolean isIngredient(ItemStack stack) { return ingredient.test(stack); }
                            public ItemStack getOutput() { return output; }
                            public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
                                return isInput(input) && isIngredient(ingredient) ? output.copy() : ItemStack.EMPTY;
                            }
                        }""")
                .add("net/minecraftforge/common/brewing/VanillaBrewingRecipe.java", """
                        package net.minecraftforge.common.brewing;
                        import net.minecraft.world.item.ItemStack;
                        public class VanillaBrewingRecipe implements IBrewingRecipe {
                            public boolean isInput(ItemStack stack) { return stack.id().equals("water_bottle"); }
                            public boolean isIngredient(ItemStack stack) { return stack.id().equals("nether_wart"); }
                            public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
                                return isInput(input) && isIngredient(ingredient) ? new ItemStack("awkward_potion") : ItemStack.EMPTY;
                            }
                        }""")
                .add("net/minecraftforge/eventbus/api/bus/EventBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        public interface EventBus<T> {
                            Object addListener(java.util.function.Consumer<T> listener);
                            boolean post(T event);
                        }""")
                .add("net/minecraftforge/eventbus/api/bus/ListBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        import java.util.ArrayList;
                        import java.util.List;
                        import java.util.function.Consumer;
                        public final class ListBus<T> implements EventBus<T> {
                            public final List<Consumer<T>> listeners = new ArrayList<>();
                            public Object addListener(Consumer<T> listener) { listeners.add(listener); return listener; }
                            public boolean post(T event) { for (Consumer<T> listener : listeners) listener.accept(event); return false; }
                        }""")
                .add("net/minecraftforge/event/brewing/BrewingRecipeRegisterEvent.java", """
                        package net.minecraftforge.event.brewing;
                        import net.minecraft.world.item.ItemStack;
                        import net.minecraft.world.item.alchemy.PotionBrewing;
                        import net.minecraft.world.item.crafting.Ingredient;
                        import net.minecraftforge.common.brewing.BrewingRecipe;
                        import net.minecraftforge.common.brewing.IBrewingRecipe;
                        import net.minecraftforge.eventbus.api.bus.EventBus;
                        import net.minecraftforge.eventbus.api.bus.ListBus;
                        public final class BrewingRecipeRegisterEvent {
                            public static final EventBus<BrewingRecipeRegisterEvent> BUS = new ListBus<>();
                            private final PotionBrewing.Builder builder;
                            public BrewingRecipeRegisterEvent(PotionBrewing.Builder builder) { this.builder = builder; }
                            public PotionBrewing.Builder getBuilder() { return builder; }
                            public void addRecipe(IBrewingRecipe recipe) { builder.add(recipe); }
                            public void addRecipe(Ingredient input, Ingredient ingredient, ItemStack output) {
                                addRecipe(new BrewingRecipe(input, ingredient, output));
                            }
                        }""")
                .add("net/minecraft/world/item/alchemy/PotionBrewing.java", """
                        package net.minecraft.world.item.alchemy;
                        import java.util.ArrayList;
                        import java.util.List;
                        import net.minecraft.world.item.ItemStack;
                        import net.minecraftforge.common.brewing.IBrewingRecipe;
                        import net.minecraftforge.common.brewing.VanillaBrewingRecipe;
                        import net.minecraftforge.event.brewing.BrewingRecipeRegisterEvent;
                        public class PotionBrewing {
                            public static int built;
                            private final List<IBrewingRecipe> recipes;
                            private PotionBrewing(List<IBrewingRecipe> recipes) { this.recipes = recipes; }
                            public static PotionBrewing bootstrap() {
                                built++;
                                Builder builder = new Builder();
                                BrewingRecipeRegisterEvent.BUS.post(new BrewingRecipeRegisterEvent(builder));
                                List<IBrewingRecipe> all = new ArrayList<>();
                                all.add(new VanillaBrewingRecipe());
                                all.addAll(builder.recipes);
                                return new PotionBrewing(List.copyOf(all));
                            }
                            public List<IBrewingRecipe> getRecipes() { return recipes; }
                            public boolean isValidInput(ItemStack stack) {
                                for (IBrewingRecipe recipe : recipes) if (recipe.isInput(stack)) return true;
                                return false;
                            }
                            public boolean isIngredient(ItemStack stack) {
                                if (stack.isEmpty()) return false;
                                for (IBrewingRecipe recipe : recipes) if (recipe.isIngredient(stack)) return true;
                                return false;
                            }
                            public ItemStack mix(ItemStack ingredient, ItemStack input) {
                                if (input.isEmpty() || input.getCount() != 1 || ingredient.isEmpty()) return ItemStack.EMPTY;
                                for (IBrewingRecipe recipe : recipes) {
                                    ItemStack output = recipe.getOutput(input, ingredient);
                                    if (!output.isEmpty()) return output;
                                }
                                return ItemStack.EMPTY;
                            }
                            public static final class Builder {
                                final List<IBrewingRecipe> recipes = new ArrayList<>();
                                public Builder add(IBrewingRecipe recipe) { recipes.add(recipe); return this; }
                            }
                        }""")
                .add("net/minecraft/server/MinecraftServer.java", """
                        package net.minecraft.server;
                        public class MinecraftServer {
                            private final net.minecraft.world.item.alchemy.PotionBrewing brewing =
                                    net.minecraft.world.item.alchemy.PotionBrewing.bootstrap();
                            public net.minecraft.world.item.alchemy.PotionBrewing potionBrewing() { return brewing; }
                        }""")
                .add("net/minecraftforge/server/ServerLifecycleHooks.java", """
                        package net.minecraftforge.server;
                        public class ServerLifecycleHooks {
                            public static net.minecraft.server.MinecraftServer current;
                            public static net.minecraft.server.MinecraftServer getCurrentServer() { return current; }
                        }""")
                .add("net/minecraft/client/multiplayer/ClientPacketListener.java", """
                        package net.minecraft.client.multiplayer;
                        public class ClientPacketListener {
                            private final net.minecraft.world.item.alchemy.PotionBrewing brewing =
                                    net.minecraft.world.item.alchemy.PotionBrewing.bootstrap();
                            public net.minecraft.world.item.alchemy.PotionBrewing potionBrewing() { return brewing; }
                        }""")
                .add("net/minecraft/client/Minecraft.java", """
                        package net.minecraft.client;
                        public class Minecraft {
                            public static Minecraft instance;
                            public net.minecraft.client.multiplayer.ClientPacketListener connection;
                            public static Minecraft getInstance() { return instance; }
                            public net.minecraft.client.multiplayer.ClientPacketListener getConnection() { return connection; }
                        }""");
    }

    // ---- what each path wrote ----

    /** What RetromodForge registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, String host) throws Exception {
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

    /** The registry call in add and output, and whether it reaches a copy embedded in the same jar. */
    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(file.getJarEntry(MOD + ".class")).readAllBytes()).accept(node, 0);
            for (String name : List.of("add", "output")) {
                MethodNode method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
                for (AbstractInsnNode insn : method.instructions) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX)
                            && file.getJarEntry(call.owner + ".class") != null;
                    String owner = embedded ? call.owner.substring(call.owner.lastIndexOf('/') + 1) : call.owner;
                    String cast = call.getNext() instanceof TypeInsnNode type && type.getOpcode() == CHECKCAST
                            ? ", cast to " + type.desc.substring(type.desc.lastIndexOf('/') + 1) : "";
                    found.put(name, (embedded ? "embedded " : "") + owner + "." + call.name + cast);
                }
            }
        }
        return found;
    }

    private static Path onlyJar(Path folder) throws Exception {
        try (var jars = Files.list(folder)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    // ---- the old mod ----

    /** Each method forwards its arguments to the 1.20.1 registry method of the same shape and returns its result. */
    static byte[] brewsClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        forward(cw, "add", "addRecipe", "(L" + RECIPE + ";)Z");
        forward(cw, "addTriple", "addRecipe", "(" + INGREDIENT + INGREDIENT + STACK + ")Z");
        forward(cw, "output", "getOutput", "(" + STACK + STACK + ")" + STACK);
        forward(cw, "has", "hasOutput", "(" + STACK + STACK + ")Z");
        forward(cw, "validInput", "isValidInput", "(" + STACK + ")Z");
        forward(cw, "validIngredient", "isValidIngredient", "(" + STACK + ")Z");
        forward(cw, "recipes", "getRecipes", "()Ljava/util/List;");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void forward(ClassWriter cw, String name, String registryMethod, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        Type[] arguments = Type.getArgumentTypes(desc);
        for (int i = 0; i < arguments.length; i++) mv.visitVarInsn(ALOAD, i);
        mv.visitMethodInsn(INVOKESTATIC, REGISTRY, registryMethod, desc, false);
        mv.visitInsn(Type.getReturnType(desc).getOpcode(IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Object call(Object target, String name, Object... arguments) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                    return method.invoke(target, arguments);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
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
