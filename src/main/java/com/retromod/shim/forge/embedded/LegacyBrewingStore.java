/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runtime half of {@link com.retromod.shim.forge.LegacyBrewingBridge}: turns the brewing recipes
 * an older Forge mod registered in code into the brewing recipe files 26.3 reads. Embedded into
 * each mod and reflective, because Retromod is not compiled against Minecraft or NeoForge.
 *
 * <p>An old recipe answered three questions in code: is this the input, is this the reagent, and
 * what comes out. Once the server has started, when items can be built, every potion in every
 * bottle and every item is put to the first two questions, and each match becomes a
 * {@code minecraft:brewing} recipe with the output the third one gives. Like trades, recipes load
 * before items can be built and stay for the session, so they apply from the next start.
 */
public final class LegacyBrewingStore {

    private static final List<Object> RECIPES = java.util.Collections.synchronizedList(new ArrayList<>());
    private static String namespace;
    private static boolean listening;
    private static Path root;

    private LegacyBrewingStore() {}

    /** {@code BrewingRecipeRegistry.addRecipe(recipe)}: kept for the brewing pack. */
    public static boolean addRecipe(Object recipe) {
        if (recipe == null) return false;
        RECIPES.add(recipe);
        watch(recipe instanceof Object[] triple ? triple[2] : recipe);
        return true;
    }

    /** {@code BrewingRecipeRegistry.addRecipe(input, ingredient, output)} with ingredients. */
    public static boolean addRecipe(Object input, Object ingredient, Object output) {
        return addRecipe(new Object[] {input, ingredient, output});
    }

    /** Starts the pack listeners on the mod that registered {@code owner}'s recipe. */
    private static synchronized void watch(Object owner) {
        if (listening) return;
        listening = true;
        try {
            Object mods = Class.forName("net.neoforged.fml.ModList").getMethod("get").invoke(null);
            Object mod = owningMod(mods, owner);
            if (mod == null) {
                Object context = Class.forName("net.neoforged.fml.ModLoadingContext").getMethod("get").invoke(null);
                Object active = context.getClass().getMethod("getActiveNamespace").invoke(context);
                Object byId = active == null ? null
                        : mods.getClass().getMethod("getModContainerById", String.class).invoke(mods, active.toString());
                mod = byId instanceof Optional<?> optional ? optional.orElse(null) : null;
            }
            if (mod == null) {
                log("could not tell which mod added a brewing recipe, so its recipes are not added");
                return;
            }
            namespace = String.valueOf(mod.getClass().getMethod("getNamespace").invoke(mod));
            root = gameDirectory().resolve("config").resolve("retromod").resolve("legacy-brewing")
                    .resolve("retromod_legacy_brewing_" + namespace);
            Object gameBus = Class.forName("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
            Consumer<Object> offer = LegacyBrewingStore::onAddPackFinders;
            Consumer<Object> fill = LegacyBrewingStore::onServerStarted;
            addListener(mod.getClass().getMethod("getEventBus").invoke(mod), "net.neoforged.neoforge.event.AddPackFindersEvent", offer);
            addListener(gameBus, "net.neoforged.neoforge.event.server.ServerStartedEvent", fill);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("cannot offer brewing recipes as a data pack on this loader: " + e);
        }
    }

    /**
     * The mod whose jar holds the recipe's class. A mod usually adds recipes from a deferred
     * setup task, where no mod is active, so the jar's scan of its classes answers instead.
     */
    private static Object owningMod(Object mods, Object owner) throws ReflectiveOperationException {
        // Members are read through the public interfaces: the implementations sit in packages a
        // mod's module may not reflect into.
        // A scanned class prints as its descriptor, such as clazz=Lmod/Recipe;
        String descriptor = "L" + owner.getClass().getName().replace('.', '/') + ";";
        Method modInfo = Class.forName("net.neoforged.fml.ModContainer").getMethod("getModInfo");
        Method owningFile = Class.forName("net.neoforged.neoforgespi.language.IModInfo").getMethod("getOwningFile");
        Method file = Class.forName("net.neoforged.neoforgespi.language.IModFileInfo").getMethod("getFile");
        Method scan = Class.forName("net.neoforged.neoforgespi.locating.IModFile").getMethod("getScanResult");
        Method classes = Class.forName("net.neoforged.neoforgespi.language.ModFileScanData").getMethod("getClasses");
        for (Object container : (List<?>) mods.getClass().getMethod("getSortedMods").invoke(mods)) {
            Object scanData = scan.invoke(file.invoke(owningFile.invoke(modInfo.invoke(container))));
            for (Object classData : (Collection<?>) classes.invoke(scanData)) {
                if (String.valueOf(classData).contains("clazz=" + descriptor)) return container;
            }
        }
        return null;
    }

    private static void addListener(Object bus, String eventClass, Consumer<Object> listener) throws ReflectiveOperationException {
        Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority");
        Class.forName("net.neoforged.bus.api.IEventBus")
                .getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                .invoke(bus, priority.getField("NORMAL").get(null), false, Class.forName(eventClass), listener);
    }

    private static void onAddPackFinders(Object event) {
        try {
            Object packType = event.getClass().getMethod("getPackType").invoke(event);
            if (!(packType instanceof Enum<?> type) || !type.name().equals("SERVER_DATA")) return;
            int format = LegacyDataPackSource.packFormat();
            write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + format + ",\"min_format\":" + format
                    + ",\"max_format\":" + format + ",\"description\":\"Retromod legacy brewing\"}}");
            Object source = LegacyDataPackSource.create(root.getFileName().toString(), root);
            if (source == null) return;
            for (Method method : event.getClass().getMethods()) {
                if (method.getName().equals("addRepositorySource") && method.getParameterCount() == 1) {
                    method.invoke(event, source);
                    return;
                }
            }
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not offer the brewing recipes of " + namespace + ": " + e);
        }
    }

    private static void onServerStarted(Object event) {
        try {
            Object server = event.getClass().getMethod("getServer").invoke(event);
            String before = snapshot();
            int written = writeRecipes(server);
            if (before.equals(snapshot())) return;
            log("wrote " + written + " brewing recipes from " + namespace + "; they apply from the next start,"
                    + " because this Minecraft version loads recipes before a mod can build the items they use");
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log("could not convert the brewing recipes of " + namespace + ": " + cause);
        }
    }

    /** Runs every recorded recipe over the potions and items, and writes each match as a recipe file. */
    static int writeRecipes(Object server) throws IOException, ReflectiveOperationException {
        Path data = root.resolve("data").resolve(namespace).resolve("recipe").resolve("legacy_brewing");
        deleteTree(root.resolve("data"));
        ClassLoader loader = server.getClass().getClassLoader();
        Object ops = serializationOps(server, loader);
        List<Object> candidates = candidateStacks(loader);
        List<Object> recipes;
        synchronized (RECIPES) {
            recipes = new ArrayList<>(RECIPES);
        }
        int written = 0;
        for (Object recipe : recipes) {
            List<Object> inputs = new ArrayList<>();
            List<Object> reagents = new ArrayList<>();
            for (Object stack : candidates) {
                if (test(recipe, 0, stack)) inputs.add(stack);
                if (test(recipe, 1, stack)) reagents.add(stack);
            }
            for (Object input : inputs) {
                for (Object reagent : reagents) {
                    Object output = output(recipe, input, reagent);
                    if (output == null || Boolean.TRUE.equals(call(output, "isEmpty"))) continue;
                    String json = "{\"type\":\"minecraft:brewing\",\"input\":" + ingredient(input, loader)
                            + ",\"reagent\":" + ingredient(reagent, loader) + ",\"output\":" + encode(output, ops, loader) + "}";
                    write(data.resolve(written++ + ".json"), json);
                }
            }
        }
        return written;
    }

    /** Every potion in each bottle, then the default stack of every item. */
    private static List<Object> candidateStacks(ClassLoader loader) throws ReflectiveOperationException {
        List<Object> stacks = new ArrayList<>();
        Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);
        Object potions = registries.getField("POTION").get(null);
        Object items = registries.getField("ITEM").get(null);
        Class<?> itemsClass = Class.forName("net.minecraft.world.item.Items", true, loader);
        Class<?> contents = Class.forName("net.minecraft.world.item.alchemy.PotionContents", true, loader);
        Method create = null;
        for (Method method : contents.getMethods()) {
            if (method.getName().equals("createItemStack") && method.getParameterCount() == 2) create = method;
        }
        Method wrap = findMethod(potions.getClass(), "wrapAsHolder", 1);
        for (String bottle : new String[] {"POTION", "SPLASH_POTION", "LINGERING_POTION"}) {
            Object item = itemsClass.getField(bottle).get(null);
            for (Object entry : (Collection<?>) call(potions, "entrySet")) {
                stacks.add(create.invoke(null, item, wrap.invoke(potions, ((Map.Entry<?, ?>) entry).getValue())));
            }
        }
        Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack", true, loader);
        Class<?> itemLike = Class.forName("net.minecraft.world.level.ItemLike", true, loader);
        for (Object entry : (Collection<?>) call(items, "entrySet")) {
            try {
                stacks.add(stackClass.getConstructor(itemLike).newInstance(((Map.Entry<?, ?>) entry).getValue()));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // an item that cannot be built without a world is no recipe's ingredient
            }
        }
        return stacks;
    }

    private static boolean test(Object recipe, int slot, Object stack) {
        try {
            if (recipe instanceof Object[] triple) {
                return Boolean.TRUE.equals(findMethod(triple[slot].getClass(), "test", 1).invoke(triple[slot], stack));
            }
            return Boolean.TRUE.equals(findMethod(recipe.getClass(), slot == 0 ? "isInput" : "isIngredient", 1).invoke(recipe, stack));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Object output(Object recipe, Object input, Object reagent) {
        try {
            if (recipe instanceof Object[] triple) return call(triple[2], "copy");
            return findMethod(recipe.getClass(), "getOutput", 2).invoke(recipe, input, reagent);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** {@code {"item": id}}, with the potion for a bottle, as vanilla's brewing recipes match them. */
    private static String ingredient(Object stack, ClassLoader loader) throws ReflectiveOperationException {
        Object item = call(stack, "getItem");
        Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader).getField("ITEM").get(null);
        String json = "{\"item\":\"" + findMethod(items.getClass(), "getKey", 1).invoke(items, item) + "\"";
        Object contentsType = Class.forName("net.minecraft.core.component.DataComponents", true, loader)
                .getField("POTION_CONTENTS").get(null);
        Object contents = findMethod(stack.getClass(), "get", 1).invoke(stack, contentsType);
        if (contents != null && call(contents, "potion") instanceof Optional<?> potion && potion.isPresent()) {
            Object key = call(potion.get(), "unwrapKey");
            if (key instanceof Optional<?> id && id.isPresent()) {
                json += ",\"potion_contents\":{\"potions\":\"" + keyId(id.get()) + "\"}";
            }
        }
        return json + "}";
    }

    private static String encode(Object stack, Object ops, ClassLoader loader) throws ReflectiveOperationException {
        Object codec = Class.forName("net.minecraft.world.item.ItemStack", true, loader).getField("CODEC").get(null);
        Object result = findMethod(codec.getClass(), "encodeStart", 2).invoke(codec, ops, stack);
        Object value = call(result, "result");
        if (value instanceof Optional<?> json && json.isPresent()) return json.get().toString();
        throw new IllegalStateException("could not write the output " + stack);
    }

    private static Object serializationOps(Object server, ClassLoader loader) throws ReflectiveOperationException {
        Object json = Class.forName("com.mojang.serialization.JsonOps", true, loader).getField("INSTANCE").get(null);
        Object access = call(server, "registryAccess");
        return findMethod(access.getClass(), "createSerializationContext", 1).invoke(access, json);
    }

    private static String keyId(Object resourceKey) {
        String text = String.valueOf(resourceKey);
        int slash = text.lastIndexOf(" / ");
        return slash < 0 ? text : text.substring(slash + 3).replace("]", "").trim();
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), name, 0);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return method.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name, int arity) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) return method;
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static String snapshot() throws IOException {
        Path data = root.resolve("data");
        if (!Files.exists(data)) return "";
        StringBuilder out = new StringBuilder();
        try (Stream<Path> walk = Files.walk(data)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.append(data.relativize(file)).append('\n').append(Files.readString(file)).append('\n');
            }
        }
        return out.toString();
    }

    private static Path gameDirectory() {
        try {
            Object gameDir = Class.forName("net.neoforged.fml.loading.FMLPaths").getField("GAMEDIR").get(null);
            if (gameDir.getClass().getMethod("get").invoke(gameDir) instanceof Path path) return path;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // The working directory is the game directory on every launcher.
        }
        return Path.of("").toAbsolutePath();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private static void log(String message) {
        System.out.println("[Retromod] Legacy brewing: " + message);
    }
}
