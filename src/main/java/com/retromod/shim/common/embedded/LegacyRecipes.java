/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** Runs a mod's 1.20.1 recipe serializers, recipes, and recipe lookups on the recipe API of 1.20.5 and newer. */
public final class LegacyRecipes {

    // Assembled at runtime so the release jar's Gson relocation leaves the game's Gson names alone.
    private static final String GSON = String.join(".", "com", "google", "gson") + ".";
    private static final String SERIALIZATION = "com.mojang.serialization.";
    private static final String CRAFTING = "net.minecraft.world.item.crafting.";
    // 1.21.11 renamed ResourceLocation to Identifier, so the id class is looked up by either name.
    private static final String ID = "net.minecraft.resources.Identifier";
    private static final String OLD_ID = "net.minecraft.resources.ResourceLocation";
    private static final String REGISTRY_BUFFER = "net.minecraft.network.RegistryFriendlyByteBuf";

    private static final Map<Object, Object> CODECS = Collections.synchronizedMap(new WeakHashMap<>());
    // The id of the holder each recipe last came out of, which an old getId() call reports.
    private static final Map<Object, Object> HOLDER_IDS = Collections.synchronizedMap(new WeakHashMap<>());
    // The ops a recipe file is read with, so the ingredients inside it read tags through the same registries.
    private static final ThreadLocal<Object> DECODE_OPS = new ThreadLocal<>();
    // A client with no integrated server has no recipes; each of its levels gets one empty manager.
    private static final Map<Object, Object> EMPTY_MANAGERS = new WeakHashMap<>();

    private LegacyRecipes() {}

    // ---- serializers --------------------------------------------------------------------------

    /** {@code codec()}: a recipe's JSON goes to the serializer's own {@code fromJson(id, json)}. */
    public static Object codec(Object serializer) {
        return CODECS.computeIfAbsent(serializer, LegacyRecipes::buildCodec);
    }

    private static Object buildCodec(Object serializer) {
        try {
            ClassLoader loader = loader();
            Class<?> codec = Class.forName(SERIALIZATION + "Codec", true, loader);
            Class<?> dataResult = Class.forName(SERIALIZATION + "DataResult", true, loader);
            Class<?> dynamic = Class.forName(SERIALIZATION + "Dynamic", true, loader);
            Class<?> ops = Class.forName(SERIALIZATION + "DynamicOps", true, loader);
            Object jsonOps = Class.forName(SERIALIZATION + "JsonOps", true, loader).getField("INSTANCE").get(null);
            Class<?> jsonObject = Class.forName(GSON + "JsonObject", true, loader);
            Method success = dataResult.getMethod("success", Object.class);
            Method error = dataResult.getMethod("error", Supplier.class);
            Method convert = dynamic.getMethod("convert", ops);
            Method value = dynamic.getMethod("getValue");
            Method fromJson = erased(serializer, "fromJson", jsonObject);
            Method opsOf = dynamic.getMethod("getOps");
            Function<Object, Object> decode = input -> {
                Object outer = DECODE_OPS.get();
                try {
                    Object json = value.invoke(convert.invoke(input, jsonOps));
                    if (!jsonObject.isInstance(json)) {
                        return error.invoke(null, (Supplier<String>) () -> "expected an object, not " + json);
                    }
                    DECODE_OPS.set(opsOf.invoke(input));
                    return success.invoke(null, fromJson.invoke(serializer, recipeId(serializer, json), json));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    return failure(error, serializer, e);
                } finally {
                    DECODE_OPS.set(outer);
                }
            };
            // A 1.20.1 serializer only reads JSON; its data generator wrote the files.
            Function<Object, Object> encode = recipe -> failure(error, serializer,
                    new UnsupportedOperationException("a 1.20.1 recipe serializer cannot write JSON"));
            Object mapped = codec.getMethod("flatXmap", Function.class, Function.class)
                    .invoke(codec.getField("PASSTHROUGH").get(null), decode, encode);
            return Class.forName(SERIALIZATION + "MapCodec", true, loader).getMethod("assumeMapUnsafe", codec)
                    .invoke(null, mapped);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the codec of " + serializer.getClass().getName(), e);
        }
    }

    /** The stream codec's read: the id written by {@link #encodeNetwork}, then the serializer's own fromNetwork. */
    public static Object decodeNetwork(Object serializer, Object buf) {
        try {
            Object id = LegacyPublicMethods.method(buf.getClass(), hasMethod(buf, "readIdentifier") ? "readIdentifier"
                    : "readResourceLocation").invoke(buf);
            return erased(serializer, "fromNetwork", Class.forName("net.minecraft.network.FriendlyByteBuf", true, loader()))
                    .invoke(serializer, id, buf);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a recipe of " + serializer.getClass().getName(), e);
        }
    }

    /** The stream codec's write: the recipe's id, which 1.20.1 sent before every recipe, then its toNetwork. */
    public static void encodeNetwork(Object serializer, Object buf, Object recipe) {
        try {
            Class<?> id = idClass();
            LegacyPublicMethods.method(buf.getClass(), hasMethod(buf, "writeIdentifier") ? "writeIdentifier"
                    : "writeResourceLocation", id).invoke(buf, id(recipe));
            for (Method method : LegacyPublicMethods.methods(serializer.getClass())) {
                if (method.getName().equals("toNetwork") && method.getParameterCount() == 2
                        && method.getParameterTypes()[1].isInstance(recipe)
                        && method.getParameterTypes()[0].isInstance(buf)) {
                    method.trySetAccessible();
                    method.invoke(serializer, buf, recipe);
                    return;
                }
            }
            throw new NoSuchMethodException(serializer.getClass().getName() + ".toNetwork");
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not write a recipe of " + serializer.getClass().getName(), e);
        }
    }

    /**
     * An id for a recipe read from JSON. The file's own id never reaches a serializer since 1.20.2,
     * so it is the serializer's namespace and a hash of the recipe, the same on every load.
     */
    private static Object recipeId(Object serializer, Object json) throws ReflectiveOperationException {
        String namespace = "retromod";
        Object key = serializerKey(serializer);
        if (key != null) namespace = (String) LegacyPublicMethods.method(key.getClass(), "getNamespace").invoke(key);
        return newId(namespace, "retromod_legacy/" + Integer.toHexString(json.toString().hashCode()));
    }

    private static Object serializerKey(Object serializer) throws ReflectiveOperationException {
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader())
                .getField("RECIPE_SERIALIZER").get(null);
        return Class.forName("net.minecraft.core.Registry", true, loader()).getMethod("getKey", Object.class)
                .invoke(registry, serializer);
    }

    private static Object newId(String namespace, String path) throws ReflectiveOperationException {
        Class<?> id = idClass();
        try {
            return id.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
        } catch (NoSuchMethodException beforeOneTwentyOne) {
            return id.getConstructor(String.class, String.class).newInstance(namespace, path);
        }
    }

    // ---- recipe lookups -----------------------------------------------------------------------

    /** {@code Optional<RecipeHolder>} to the {@code Optional<Recipe>} 1.20.1 returned. */
    public static Object value(Object holder) {
        return ((Optional<?>) holder).map(LegacyRecipes::unwrap);
    }

    /** A collection of holders to a list of recipes. */
    public static Object values(Object holders) {
        List<Object> recipes = new ArrayList<>();
        for (Object holder : (Collection<?>) holders) recipes.add(unwrap(holder));
        return recipes;
    }

    /** {@code replaceRecipes(recipes)}: each recipe in a holder under its own id. */
    public static Object holders(Object recipes) {
        try {
            Class<?> holderType = Class.forName(CRAFTING + "RecipeHolder", true, loader());
            Constructor<?> holder = holderType.getConstructor(idClass(),
                    Class.forName(CRAFTING + "Recipe", true, loader()));
            List<Object> holders = new ArrayList<>();
            for (Object recipe : (Iterable<?>) recipes) {
                holders.add(holderType.isInstance(recipe) ? recipe : holder.newInstance(id(recipe), recipe));
            }
            return holders;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not wrap recipes in holders", e);
        }
    }

    /** {@code recipe.getId()}: its holder's id when it came out of one, else the recipe's own. */
    public static Object id(Object recipe) {
        Object known = HOLDER_IDS.get(recipe);
        if (known != null) return known;
        try {
            Method own = LegacyPublicMethods.method(recipe.getClass(), "getId");
            own.trySetAccessible();
            return own.invoke(recipe);
        } catch (NoSuchMethodException vanillaRecipe) {
            return null;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object unwrap(Object holder) {
        try {
            Object recipe = LegacyPublicMethods.method(holder.getClass(), "value").invoke(holder);
            Object id = LegacyPublicMethods.method(holder.getClass(), "id").invoke(holder);
            // From 1.21.2 a holder names its recipe by a resource key, whose id an old getId() wants.
            if (!idClass().isInstance(id)) id = LegacyPublicMethods.method(id.getClass(), hasMethod(id, "identifier")
                    ? "identifier" : "location").invoke(id);
            HOLDER_IDS.put(recipe, id);
            return recipe;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a recipe holder", e);
        }
    }

    // ---- lookups on the 1.21.2 recipe manager -------------------------------------------------

    /** {@code level.getRecipeManager()}: a server level's manager; on a client the integrated server's, else one with no recipes. */
    public static Object recipeManager(Object level) {
        try {
            ClassLoader loader = loader();
            Class<?> manager = Class.forName(CRAFTING + "RecipeManager", true, loader);
            Object access = LegacyPublicMethods.method(level.getClass(), "recipeAccess").invoke(level);
            if (manager.isInstance(access)) return access;
            // 1.21.2 keeps recipes on the server; a client only knows what the server shows it.
            Object client = staticCall(loader, "net.minecraft.client.Minecraft", "getInstance");
            Object server = client == null ? null : LegacyPublicMethods.method(client.getClass(), "getSingleplayerServer").invoke(client);
            if (server != null) return LegacyPublicMethods.method(server.getClass(), "getRecipeManager").invoke(server);
            synchronized (EMPTY_MANAGERS) {
                return EMPTY_MANAGERS.computeIfAbsent(level, ignored -> emptyManager(manager, level));
            }
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not find the recipe manager", e);
        }
    }

    private static Object emptyManager(Class<?> manager, Object level) {
        try {
            Object registries = LegacyPublicMethods.method(level.getClass(), "registryAccess").invoke(level);
            return manager.getConstructor(Class.forName("net.minecraft.core.HolderLookup$Provider", true, loader()))
                    .newInstance(registries);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not make an empty recipe manager", e);
        }
    }

    /** {@code check.getRecipeFor(container, level)}: only a server level has recipes to check. */
    public static Object cachedRecipeFor(Object check, Object input, Object level) {
        try {
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel", true, loader());
            if (!serverLevel.isInstance(level)) return Optional.empty();
            Method lookup = Class.forName(CRAFTING + "RecipeManager$CachedCheck", true, loader()).getMethod("getRecipeFor",
                    Class.forName(CRAFTING + "RecipeInput", true, loader()), serverLevel);
            return value(lookup.invoke(check, input, level));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not check for a recipe", e);
        }
    }

    /** {@code getRecipeFor(type, container, level, lastId)}: the recipe that matches, paired with its id. */
    public static Object recipeWithId(Object manager, Object type, Object input, Object level) {
        try {
            ClassLoader loader = loader();
            Method lookup = Class.forName(CRAFTING + "RecipeManager", true, loader).getMethod("getRecipeFor",
                    Class.forName(CRAFTING + "RecipeType", true, loader), Class.forName(CRAFTING + "RecipeInput", true, loader),
                    Class.forName("net.minecraft.world.level.Level", true, loader));
            Optional<?> found = (Optional<?>) value(lookup.invoke(manager, type, input, level));
            if (found.isEmpty()) return Optional.empty();
            Object recipe = found.get();
            return Optional.of(Class.forName("com.mojang.datafixers.util.Pair", true, loader).getMethod("of", Object.class, Object.class)
                    .invoke(null, id(recipe), recipe));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not look a recipe up", e);
        }
    }

    // ---- Minecraft's own serializers, called the 1.20.1 way --------------------------------------

    /** {@code serializer.fromJson(id, json)}: a mod serializer reads it itself; Minecraft's reads it through its codec. */
    public static Object serializerFromJson(Object serializer, Object id, Object json) {
        try {
            if (hasMethod(serializer, "fromJson")) return erased(serializer, "fromJson", gson("JsonObject")).invoke(serializer, id, json);
            Object codec = LegacyPublicMethods.method(serializer.getClass(), "codec").invoke(serializer);
            Object full = Class.forName(SERIALIZATION + "MapCodec", true, loader()).getMethod("codec").invoke(codec);
            return parse(full, currentRecipe(json), "recipe");
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a recipe with " + serializer, e);
        }
    }

    /** {@code serializer.fromNetwork(id, buf)}. */
    public static Object serializerFromNetwork(Object serializer, Object id, Object buf) {
        try {
            if (hasMethod(serializer, "fromNetwork")) {
                return erased(serializer, "fromNetwork", Class.forName("net.minecraft.network.FriendlyByteBuf", true, loader()))
                        .invoke(serializer, id, buf);
            }
            return decode(LegacyPublicMethods.method(serializer.getClass(), "streamCodec").invoke(serializer), buf);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a recipe with " + serializer, e);
        }
    }

    /** {@code serializer.toNetwork(buf, recipe)}. */
    public static void serializerToNetwork(Object serializer, Object buf, Object recipe) {
        try {
            if (hasMethod(serializer, "toNetwork")) {
                for (Method method : LegacyPublicMethods.methods(serializer.getClass())) {
                    if (method.getName().equals("toNetwork") && method.getParameterCount() == 2) {
                        method.trySetAccessible();
                        method.invoke(serializer, buf, recipe);
                        return;
                    }
                }
            }
            encode(LegacyPublicMethods.method(serializer.getClass(), "streamCodec").invoke(serializer), buf, recipe);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not write a recipe with " + serializer, e);
        }
    }

    /** A copy of an old recipe file in the forms a current codec reads: ingredient ids, and a result named by {@code id}. */
    private static Object currentRecipe(Object json) throws ReflectiveOperationException {
        Class<?> object = gson("JsonObject");
        if (!object.isInstance(json)) return json;
        Object copy = gson("JsonElement").getMethod("deepCopy").invoke(json);
        Method get = object.getMethod("get", String.class);
        Method add = object.getMethod("add", String.class, gson("JsonElement"));
        Object key = get.invoke(copy, "key");
        if (object.isInstance(key)) {
            for (String symbol : List.copyOf((java.util.Set<String>) object.getMethod("keySet").invoke(key))) {
                Object current = currentIngredient(get.invoke(key, symbol));
                if (current != null) add.invoke(key, symbol, current);
            }
        }
        Object ingredients = get.invoke(copy, "ingredients");
        if (gson("JsonArray").isInstance(ingredients)) {
            Object list = gson("JsonArray").getConstructor().newInstance();
            for (Object entry : (Iterable<?>) ingredients) {
                Object current = currentIngredient(entry);
                gson("JsonArray").getMethod("add", gson("JsonElement")).invoke(list, current != null ? current : entry);
            }
            add.invoke(copy, "ingredients", list);
        }
        // 1.20.5 names a result's item "id"; NBT has no form a codec reads here, so it is left out.
        Object result = get.invoke(copy, "result");
        if (object.isInstance(result) && get.invoke(result, "item") != null && get.invoke(result, "id") == null) {
            add.invoke(result, "id", object.getMethod("remove", String.class).invoke(result, "item"));
            object.getMethod("remove", String.class).invoke(result, "nbt");
        }
        return copy;
    }

    // ---- recipe inputs ------------------------------------------------------------------------

    /** A container with an input's items, for an old recipe that reads containers. */
    public static Object container(Object input) {
        try {
            ClassLoader loader = loader();
            Class<?> crafting = Class.forName(CRAFTING + "CraftingInput", true, loader);
            if (crafting.isInstance(input)) {
                // A crafting recipe reads its grid through CraftingContainer. It only reads, so no menu.
                int width = (int) crafting.getMethod("width").invoke(input);
                int height = (int) crafting.getMethod("height").invoke(input);
                Class<?> list = Class.forName("net.minecraft.core.NonNullList", true, loader);
                List<?> stacks = (List<?>) crafting.getMethod("items").invoke(input);
                Class<?> grid = Class.forName("net.minecraft.world.inventory.TransientCraftingContainer", true, loader);
                Class<?> menu = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu", true, loader);
                // The constructor that takes the stacks is private from 1.21.11; setItem would tell the absent menu.
                Constructor<?> withItems = grid.getDeclaredConstructor(menu, int.class, int.class, list);
                withItems.setAccessible(true);
                Object empty = Class.forName("net.minecraft.world.item.ItemStack", true, loader).getField("EMPTY").get(null);
                Object items = list.getMethod("of", Object.class, Object[].class).invoke(null, empty, stacks.toArray());
                return withItems.newInstance(null, width, height, items);
            }
            Class<?> recipeInput = Class.forName(CRAFTING + "RecipeInput", true, loader);
            int size = (int) recipeInput.getMethod("size").invoke(input);
            Class<?> simple = Class.forName("net.minecraft.world.SimpleContainer", true, loader);
            Object container = simple.getConstructor(int.class).newInstance(size);
            Method getItem = recipeInput.getMethod("getItem", int.class);
            Method setItem = simple.getMethod("setItem", int.class, Class.forName("net.minecraft.world.item.ItemStack", true, loader));
            for (int slot = 0; slot < size; slot++) setItem.invoke(container, slot, getItem.invoke(input, slot));
            return container;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a recipe input", e);
        }
    }

    /** The {@code RegistryAccess} an old recipe expects, from the provider it is given now. */
    public static Object registryAccess(Object provider) {
        try {
            Class<?> access = Class.forName("net.minecraft.core.RegistryAccess", true, loader());
            return access.isInstance(provider) ? provider : runningRegistries();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not find the registries", e);
        }
    }

    // ---- ingredients and stacks ---------------------------------------------------------------

    /** {@code Ingredient.fromJson(json)}: through the ingredient codec, after the old item and tag objects take its forms. */
    public static Object ingredientFromJson(Object json) {
        Object codec = field("net.minecraft.world.item.crafting.Ingredient", "CODEC");
        try {
            Object current = currentIngredient(json);
            if (current != null) return parse(codec, current, "ingredient");
            // A list that mixes tags or a loader's own ingredients is read one entry at a time and joined again.
            List<Object> parts = new ArrayList<>();
            for (Object entry : (Iterable<?>) json) parts.add(ingredientFromJson(entry));
            return compound(parts);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the ingredient " + json, e);
        }
    }

    /**
     * The 1.21.2 form of an old ingredient: {@code {"item": id}} becomes the id, {@code {"tag": t}} becomes
     * {@code "#t"}, and a list of items a list of ids. Anything else is passed on, except a list that mixes
     * tags or typed ingredients, which answers null.
     */
    private static Object currentIngredient(Object json) throws ReflectiveOperationException {
        Class<?> object = gson("JsonObject");
        Class<?> array = gson("JsonArray");
        if (object.isInstance(json)) {
            String id = legacyId(json);
            return id == null ? json : gson("JsonPrimitive").getConstructor(String.class).newInstance(id);
        }
        if (!array.isInstance(json)) return json;
        Object ids = array.getConstructor().newInstance();
        for (Object entry : (Iterable<?>) json) {
            String id = object.isInstance(entry) ? legacyId(entry) : null;
            if (id == null || id.startsWith("#")) return null;
            array.getMethod("add", String.class).invoke(ids, id);
        }
        return ids;
    }

    /** An old object's item id, or {@code #tag}, when it holds only that; else null. */
    private static String legacyId(Object json) throws ReflectiveOperationException {
        int size = (int) gson("JsonObject").getMethod("size").invoke(json);
        Method get = gson("JsonObject").getMethod("get", String.class);
        Object item = get.invoke(json, "item");
        Object tag = get.invoke(json, "tag");
        if (size != 1 || (item == null) == (tag == null)) return null;
        String text = (String) gson("JsonElement").getMethod("getAsString").invoke(item != null ? item : tag);
        return item != null ? text : "#" + text;
    }

    /** One ingredient that accepts what any of these does, through the loader's compound ingredient. */
    private static Object compound(List<Object> parts) throws ReflectiveOperationException {
        if (parts.size() == 1) return parts.get(0);
        Class<?> ingredient = Class.forName(CRAFTING + "Ingredient", true, loader());
        Object array = java.lang.reflect.Array.newInstance(ingredient, parts.size());
        for (int i = 0; i < parts.size(); i++) java.lang.reflect.Array.set(array, i, parts.get(i));
        for (String type : List.of("net.minecraftforge.common.crafting.ingredients.CompoundIngredient",
                "net.neoforged.neoforge.common.crafting.CompoundIngredient")) {
            try {
                return Class.forName(type, true, loader()).getMethod("of", array.getClass()).invoke(null, array);
            } catch (ClassNotFoundException otherLoader) {
                // The next loader's class.
            }
        }
        throw new IllegalArgumentException("This game has no compound ingredient to join " + parts);
    }

    /** {@code ingredient.toJson()}. */
    public static Object ingredientToJson(Object ingredient) {
        return encode(field("net.minecraft.world.item.crafting.Ingredient", "CODEC"), ingredient, "ingredient");
    }

    /** {@code Ingredient.fromNetwork(buf)}. */
    public static Object ingredientFromNetwork(Object buf) {
        return decode(field("net.minecraft.world.item.crafting.Ingredient", "CONTENTS_STREAM_CODEC"), buf);
    }

    /** {@code ingredient.toNetwork(buf)}. */
    public static void ingredientToNetwork(Object ingredient, Object buf) {
        encode(field("net.minecraft.world.item.crafting.Ingredient", "CONTENTS_STREAM_CODEC"), buf, ingredient);
    }

    /** {@code buf.readItem()}, which read an empty stack too. */
    public static Object readItem(Object buf) {
        return decode(field("net.minecraft.world.item.ItemStack", "OPTIONAL_STREAM_CODEC"), buf);
    }

    /** {@code buf.writeItem(stack)}. */
    public static Object writeItem(Object buf, Object stack) {
        encode(field("net.minecraft.world.item.ItemStack", "OPTIONAL_STREAM_CODEC"), buf, stack);
        return buf;
    }

    /** {@code ShapedRecipe.itemStackFromJson(json)}: {@code item} and {@code count}, as 1.20.1 read them. */
    public static Object itemStackFromJson(Object json) {
        try {
            ClassLoader loader = loader();
            Object item = itemFromJson(json);
            int count = countFromJson(json);
            Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack", true, loader);
            return stack.getConstructor(Class.forName("net.minecraft.world.level.ItemLike", true, loader), int.class)
                    .newInstance(item, count);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the item stack " + json, e);
        }
    }

    /** A result's {@code count}, 1 when it has none. */
    public static int countFromJson(Object json) {
        try {
            Object counted = LegacyPublicMethods.method(json.getClass(), "get", String.class).invoke(json, "count");
            return counted == null ? 1 : (int) LegacyPublicMethods.method(counted.getClass(), "getAsInt").invoke(counted);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the count of " + json, e);
        }
    }

    /** {@code ShapedRecipe.itemFromJson(json)}: the item its {@code item} names, refusing air. */
    public static Object itemFromJson(Object json) {
        try {
            ClassLoader loader = loader();
            Object name = LegacyPublicMethods.method(json.getClass(), "get", String.class).invoke(json, "item");
            if (name == null) throw new IllegalArgumentException("Missing item, expected to find a string");
            String text = (String) LegacyPublicMethods.method(name.getClass(), "getAsString").invoke(name);
            Object id = idClass().getMethod("parse", String.class).invoke(null, text);
            Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader).getField("ITEM").get(null);
            Optional<?> item = (Optional<?>) Class.forName("net.minecraft.core.Registry", true, loader)
                    .getMethod("getOptional", id.getClass()).invoke(items, id);
            Object air = Class.forName("net.minecraft.world.item.Items", true, loader).getField("AIR").get(null);
            if (item.isEmpty() || item.get() == air) throw new IllegalArgumentException("Unknown item '" + text + "'");
            return item.get();
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the item " + json, e);
        }
    }

    // ---- codec plumbing -----------------------------------------------------------------------

    private static Object parse(Object codec, Object json, String what) {
        try {
            ClassLoader loader = loader();
            Object jsonOps = jsonOps();
            // Through the public interfaces: a codec's and a result's own classes are usually not public.
            Object result = Class.forName(SERIALIZATION + "Decoder", true, loader)
                    .getMethod("parse", Class.forName(SERIALIZATION + "DynamicOps", true, loader), Object.class)
                    .invoke(codec, jsonOps, json);
            return orThrow(result, "Could not read the " + what + " " + json);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the " + what + " " + json, e);
        }
    }

    private static Object encode(Object codec, Object value, String what) {
        try {
            ClassLoader loader = loader();
            Object jsonOps = Class.forName(SERIALIZATION + "JsonOps", true, loader).getField("INSTANCE").get(null);
            Object result = Class.forName(SERIALIZATION + "Encoder", true, loader)
                    .getMethod("encodeStart", Class.forName(SERIALIZATION + "DynamicOps", true, loader), Object.class)
                    .invoke(codec, jsonOps, value);
            return orThrow(result, "Could not write the " + what + " " + value);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not write the " + what + " " + value, e);
        }
    }

    /** JSON ops that know the registries of the recipe file being read, so tags resolve; plain JSON ops otherwise. */
    private static Object jsonOps() throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Object plain = Class.forName(SERIALIZATION + "JsonOps", true, loader).getField("INSTANCE").get(null);
        Object ops = DECODE_OPS.get();
        Class<?> registryOps = Class.forName("net.minecraft.resources.RegistryOps", true, loader);
        if (!registryOps.isInstance(ops)) return plain;
        return registryOps.getMethod("withParent", Class.forName(SERIALIZATION + "DynamicOps", true, loader)).invoke(ops, plain);
    }

    private static Object orThrow(Object result, String message) throws ReflectiveOperationException {
        Class<?> dataResult = Class.forName(SERIALIZATION + "DataResult", true, loader());
        Optional<?> value = (Optional<?>) dataResult.getMethod("result").invoke(result);
        if (value.isPresent()) return value.get();
        throw new IllegalArgumentException(message + ": " + dataResult.getMethod("error").invoke(result));
    }

    private static Object decode(Object streamCodec, Object buf) {
        try {
            return Class.forName("net.minecraft.network.codec.StreamDecoder", true, loader())
                    .getMethod("decode", Object.class).invoke(streamCodec, registryBuffer(buf));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read from the buffer", e);
        }
    }

    private static void encode(Object streamCodec, Object buf, Object value) {
        try {
            Class.forName("net.minecraft.network.codec.StreamEncoder", true, loader())
                    .getMethod("encode", Object.class, Object.class).invoke(streamCodec, registryBuffer(buf), value);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not write to the buffer", e);
        }
    }

    /** Item stacks travel with registries since 1.20.5. A plain buffer gets the running game's. */
    private static Object registryBuffer(Object buf) throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> registryBuffer = Class.forName(REGISTRY_BUFFER, true, loader);
        if (registryBuffer.isInstance(buf)) return buf;
        return registryBuffer.getConstructor(Class.forName("io.netty.buffer.ByteBuf", true, loader),
                Class.forName("net.minecraft.core.RegistryAccess", true, loader)).newInstance(buf, runningRegistries());
    }

    /** The running server's registries, else the client's, else the built-in ones. */
    private static Object runningRegistries() throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Object server = staticCall(loader, "net.neoforged.neoforge.server.ServerLifecycleHooks", "getCurrentServer");
        if (server == null) server = staticCall(loader, "net.minecraftforge.server.ServerLifecycleHooks", "getCurrentServer");
        if (server != null) return LegacyPublicMethods.method(server.getClass(), "registryAccess").invoke(server);
        Object client = staticCall(loader, "net.minecraft.client.Minecraft", "getInstance");
        Object level = client == null ? null : client.getClass().getField("level").get(client);
        if (level != null) return LegacyPublicMethods.method(level.getClass(), "registryAccess").invoke(level);
        Object registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                .getField("REGISTRY").get(null);
        Class<?> registry = Class.forName("net.minecraft.core.Registry", true, loader);
        return Class.forName("net.minecraft.core.RegistryAccess", true, loader)
                .getMethod("fromRegistryOfRegistries", registry).invoke(null, registries);
    }

    private static Object staticCall(ClassLoader loader, String type, String method) throws ReflectiveOperationException {
        try {
            return Class.forName(type, true, loader).getMethod(method).invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError notOnThisSide) {
            // A loader's hook that cannot link is not the running one, as LegacyRecipeInternals reads it too.
            return null;
        }
    }

    private static Object field(String type, String name) {
        try {
            return Class.forName(type, true, loader()).getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not find " + type + "." + name, e);
        }
    }

    /** The serializer's erased {@code name(ResourceLocation, second)}, which returns any recipe. */
    private static Method erased(Object serializer, String name, Class<?> second) throws ReflectiveOperationException {
        Class<?> id = idClass();
        for (Method method : LegacyPublicMethods.methods(serializer.getClass())) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 2 && parameters[0] == id
                    && parameters[1] == second && !method.getReturnType().isPrimitive()) {
                method.trySetAccessible();
                return method;
            }
        }
        throw new NoSuchMethodException(serializer.getClass().getName() + "." + name);
    }

    /** The host's id class: Identifier from 1.21.11, ResourceLocation before. */
    private static Class<?> idClass() throws ClassNotFoundException {
        try {
            return Class.forName(ID, true, loader());
        } catch (ClassNotFoundException beforeOneTwentyOneEleven) {
            return Class.forName(OLD_ID, true, loader());
        }
    }

    private static Class<?> gson(String type) throws ClassNotFoundException {
        return Class.forName(GSON + type, true, loader());
    }

    /** Whether the object has a public method by that name. */
    private static boolean hasMethod(Object target, String name) {
        for (Method method : LegacyPublicMethods.methods(target.getClass())) {
            if (method.getName().equals(name)) return true;
        }
        return false;
    }

    /** This class's loader: it is embedded in the mod, so it sees Minecraft and the game's Gson alike. */
    private static ClassLoader loader() {
        return LegacyRecipes.class.getClassLoader();
    }

    private static Object failure(Method error, Object serializer, Throwable e) {
        Throwable cause = e instanceof InvocationTargetException wrapped ? wrapped.getCause() : e;
        // A failed class initializer says nothing itself, so its root cause is named too.
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String detail = root == cause ? String.valueOf(cause) : cause + ", caused by " + root;
        try {
            return error.invoke(null, (Supplier<String>) () -> serializer.getClass().getName() + ": " + detail);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
