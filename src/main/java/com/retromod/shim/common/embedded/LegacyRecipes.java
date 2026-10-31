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

/** Runs a mod's 1.20.1 recipe serializers, recipes, and recipe lookups on the 1.20.5 to 1.21.1 recipe API. */
public final class LegacyRecipes {

    // Assembled at runtime so the release jar's Gson relocation leaves the game's Gson names alone.
    private static final String GSON = String.join(".", "com", "google", "gson") + ".";
    private static final String SERIALIZATION = "com.mojang.serialization.";
    private static final String CRAFTING = "net.minecraft.world.item.crafting.";
    private static final String ID = "net.minecraft.resources.ResourceLocation";
    private static final String REGISTRY_BUFFER = "net.minecraft.network.RegistryFriendlyByteBuf";

    private static final Map<Object, Object> CODECS = Collections.synchronizedMap(new WeakHashMap<>());
    // The id of the holder each recipe last came out of, which an old getId() call reports.
    private static final Map<Object, Object> HOLDER_IDS = Collections.synchronizedMap(new WeakHashMap<>());

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
            Function<Object, Object> decode = input -> {
                try {
                    Object json = value.invoke(convert.invoke(input, jsonOps));
                    if (!jsonObject.isInstance(json)) {
                        return error.invoke(null, (Supplier<String>) () -> "expected an object, not " + json);
                    }
                    return success.invoke(null, fromJson.invoke(serializer, recipeId(serializer, json), json));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return failure(error, serializer, e);
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
            Object id = buf.getClass().getMethod("readResourceLocation").invoke(buf);
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
            Class<?> id = Class.forName(ID, true, loader());
            buf.getClass().getMethod("writeResourceLocation", id).invoke(buf, id(recipe));
            for (Method method : serializer.getClass().getMethods()) {
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
        if (key != null) namespace = (String) key.getClass().getMethod("getNamespace").invoke(key);
        return newId(namespace, "retromod_legacy/" + Integer.toHexString(json.toString().hashCode()));
    }

    private static Object serializerKey(Object serializer) throws ReflectiveOperationException {
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader())
                .getField("RECIPE_SERIALIZER").get(null);
        return Class.forName("net.minecraft.core.Registry", true, loader()).getMethod("getKey", Object.class)
                .invoke(registry, serializer);
    }

    private static Object newId(String namespace, String path) throws ReflectiveOperationException {
        Class<?> id = Class.forName(ID, true, loader());
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
            Constructor<?> holder = holderType.getConstructor(Class.forName(ID, true, loader()),
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
            Method own = recipe.getClass().getMethod("getId");
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
            Object recipe = holder.getClass().getMethod("value").invoke(holder);
            HOLDER_IDS.put(recipe, holder.getClass().getMethod("id").invoke(holder));
            return recipe;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a recipe holder", e);
        }
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
                Object items = list.getMethod("copyOf", Collection.class)
                        .invoke(null, crafting.getMethod("items").invoke(input));
                return Class.forName("net.minecraft.world.inventory.TransientCraftingContainer", true, loader)
                        .getConstructor(Class.forName("net.minecraft.world.inventory.AbstractContainerMenu", true, loader),
                                int.class, int.class, list)
                        .newInstance(null, width, height, items);
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

    /** {@code Ingredient.fromJson(json)}: through the ingredient codec, which reads the same item and tag forms. */
    public static Object ingredientFromJson(Object json) {
        return parse(field("net.minecraft.world.item.crafting.Ingredient", "CODEC"), json, "ingredient");
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
            int count = 1;
            Object counted = json.getClass().getMethod("get", String.class).invoke(json, "count");
            if (counted != null) count = (int) counted.getClass().getMethod("getAsInt").invoke(counted);
            Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack", true, loader);
            return stack.getConstructor(Class.forName("net.minecraft.world.level.ItemLike", true, loader), int.class)
                    .newInstance(item, count);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the item stack " + json, e);
        }
    }

    /** {@code ShapedRecipe.itemFromJson(json)}: the item its {@code item} names, refusing air. */
    public static Object itemFromJson(Object json) {
        try {
            ClassLoader loader = loader();
            Object name = json.getClass().getMethod("get", String.class).invoke(json, "item");
            if (name == null) throw new IllegalArgumentException("Missing item, expected to find a string");
            String text = (String) name.getClass().getMethod("getAsString").invoke(name);
            Object id = Class.forName(ID, true, loader).getMethod("parse", String.class).invoke(null, text);
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
            Object jsonOps = Class.forName(SERIALIZATION + "JsonOps", true, loader).getField("INSTANCE").get(null);
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
        if (server != null) return server.getClass().getMethod("registryAccess").invoke(server);
        Object client = staticCall(loader, "net.minecraft.client.Minecraft", "getInstance");
        Object level = client == null ? null : client.getClass().getField("level").get(client);
        if (level != null) return level.getClass().getMethod("registryAccess").invoke(level);
        Object registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                .getField("REGISTRY").get(null);
        Class<?> registry = Class.forName("net.minecraft.core.Registry", true, loader);
        return Class.forName("net.minecraft.core.RegistryAccess", true, loader)
                .getMethod("fromRegistryOfRegistries", registry).invoke(null, registries);
    }

    private static Object staticCall(ClassLoader loader, String type, String method) throws ReflectiveOperationException {
        try {
            return Class.forName(type, true, loader).getMethod(method).invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException notOnThisSide) {
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
        Class<?> id = Class.forName(ID, true, loader());
        for (Method method : serializer.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 2 && parameters[0] == id
                    && parameters[1] == second && !method.getReturnType().isPrimitive()) {
                method.trySetAccessible();
                return method;
            }
        }
        throw new NoSuchMethodException(serializer.getClass().getName() + "." + name);
    }

    /** This class's loader: it is embedded in the mod, so it sees Minecraft and the game's Gson alike. */
    private static ClassLoader loader() {
        return LegacyRecipes.class.getClassLoader();
    }

    private static Object failure(Method error, Object serializer, Exception e) {
        Throwable cause = e instanceof InvocationTargetException wrapped ? wrapped.getCause() : e;
        try {
            return error.invoke(null, (Supplier<String>) () -> serializer.getClass().getName() + ": " + cause);
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
