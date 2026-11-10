/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import com.retromod.shim.forge.embedded.LegacyDataPackSource;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** The animal and villager food fields that became item tags and a component, read as those and extended through them. */
public final class LegacyAnimalFoodTags {
    // Reached by reflection, because Retromod is not compiled against Minecraft or a loader.
    private static final String SELF = "com.retromod.shim.common.embedded.LegacyAnimalFoodTags";
    private static final String FORGE_EVENT_BUS = "net.minecraftforge.eventbus.api.bus.EventBus";
    // 1.21 renamed the tag folders to the singular; 1.20.5 and 1.20.6 read tags/items (format 41).
    private static final int FIRST_SINGULAR_TAG_FORMAT = 45;

    /** Item ids mods added, by the tag that replaced the field, such as {@code chicken_food}. */
    private static final Map<String, Set<String>> ADDED = new TreeMap<>();
    /** The nutrition mods gave villager foods on hosts where it is a component, by item id. */
    private static final Map<String, Integer> VILLAGER_FOODS = new TreeMap<>();
    // Each tag's ingredient view, kept with the tag set it was built on, so it is rebuilt once the tags bind.
    private static final Map<String, Object[]> INGREDIENTS = new HashMap<>();
    private static final Set<Object> VIEWS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String, LegacyTagItemSet> SETS = new HashMap<>();
    private static boolean listening;
    private static boolean offerable;

    private LegacyAnimalFoodTags() {}

    public static Object chickenFood() {
        return ingredient("chicken_food");
    }

    public static void setChickenFood(Object value) {
        setIngredient("chicken_food", value);
    }

    public static Object pigFood() {
        return ingredient("pig_food");
    }

    public static void setPigFood(Object value) {
        setIngredient("pig_food", value);
    }

    /** {@code Cat.TEMPT_INGREDIENT}, which became the cat_food tag. */
    public static Object catFood() {
        return ingredient("cat_food");
    }

    public static void setCatFood(Object value) {
        setIngredient("cat_food", value);
    }

    /** {@code Parrot.TAME_FOOD}, which became the parrot_food tag; adding to the set adds to the tag. */
    public static Object parrotFood() {
        return itemSet("parrot_food");
    }

    public static void setParrotFood(Object value) {
        setItems("parrot_food", value);
    }

    /** {@code Villager.WANTED_ITEMS}, which became the villager_picks_up tag. */
    public static Object villagerWantedItems() {
        return itemSet("villager_picks_up");
    }

    public static void setVillagerWantedItems(Object value) {
        setItems("villager_picks_up", value);
    }

    /** {@code Villager.FOOD_POINTS} on 26.3 and newer: the foods recorded here and, once items have components, every item with villager food. */
    public static synchronized Object villagerFoodPoints() {
        Map<Object, Integer> foods = new LinkedHashMap<>();
        try {
            Object component = villagerFoodComponent();
            Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader());
            // Components bind when a world loads; before that only the recorded foods are known.
            for (Object item : (Iterable<?>) items()) {
                Object holder = call(item, "builtInRegistryHolder");
                if (!(Boolean) call(holder, "areComponentsBound")) break;
                Object components = call(holder, "components");
                Object food = LegacyPublicMethods.method(components.getClass(), "get", componentType).invoke(components, component);
                if (food != null) foods.put(item, ((Number) call(food, "nutrition")).intValue());
            }
            for (Map.Entry<String, Integer> food : VILLAGER_FOODS.entrySet()) {
                Object item = item(food.getKey());
                if (item != null) foods.put(item, food.getValue());
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not read the villager foods of this game: " + cause(e));
        }
        return Collections.unmodifiableMap(foods);
    }

    /** Gives each item in the new map its villager food component, which is what the old map set. */
    public static synchronized void setVillagerFoodPoints(Object value) {
        if (!(value instanceof Map<?, ?> foods)) return;
        for (Map.Entry<?, ?> food : foods.entrySet()) {
            if (!(food.getValue() instanceof Number points)) continue;
            try {
                String id = id(food.getKey());
                if (id == null || Integer.valueOf(points.intValue()).equals(VILLAGER_FOODS.get(id))) continue;
                addVillagerFood(food.getKey(), points.intValue());
                VILLAGER_FOODS.put(id, points.intValue());
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                log("could not make " + food.getKey() + " a villager food: " + cause(e));
            }
        }
    }

    // ---- item tags ---------------------------------------------------------------------------

    /** An ingredient that matches the tag, built on the bound tag once there is one. */
    static synchronized Object ingredient(String tag) {
        try {
            Object key = tagKey(tag);
            Object bound = boundTag(key);
            Object[] cached = INGREDIENTS.get(tag);
            if (cached != null && cached[0] == bound) return cached[1];
            Object view = ingredientOf(key, bound);
            INGREDIENTS.put(tag, new Object[] {bound, view});
            VIEWS.add(view);
            return view;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Retromod could not read the item tag minecraft:" + tag
                    + " that replaced an old food field: " + cause(e), e);
        }
    }

    /** Adds the items the new ingredient matches beyond the tag, skipping the views this class handed out. */
    static synchronized void setIngredient(String tag, Object value) {
        if (value == null || VIEWS.contains(value)) return;
        List<String> ids = new ArrayList<>();
        try {
            collect(value, ids);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not read every item a mod gave minecraft:" + tag + ": " + cause(e));
        }
        add(tag, ids);
    }

    static synchronized Set<Object> itemSet(String tag) {
        return SETS.computeIfAbsent(tag, LegacyTagItemSet::new);
    }

    static synchronized void setItems(String tag, Object value) {
        if (!(value instanceof Collection<?> items) || value instanceof LegacyTagItemSet) return;
        List<String> ids = new ArrayList<>();
        for (Object item : items) {
            try {
                String id = id(item);
                if (id != null && !inTag(tag, item)) ids.add(id);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                log("could not add " + item + " to minecraft:" + tag + ": " + cause(e));
            }
        }
        add(tag, ids);
    }

    /** Whether the item is in the tag or was added to it; false while tags are unbound and nothing added it. */
    static synchronized boolean inTag(String tag, Object item) {
        try {
            String id = id(item);
            if (id != null && ADDED.getOrDefault(tag, Set.of()).contains(id)) return true;
            Object holder = call(item, "builtInRegistryHolder");
            Class<?> tagKey = Class.forName("net.minecraft.tags.TagKey", false, loader());
            return (Boolean) LegacyPublicMethods.method(holder.getClass(), "is", tagKey).invoke(holder, tagKey(tag));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unbound) {
            // An item's tags are unbound until a world loads, and then it is in no tag yet.
            return false;
        }
    }

    /** The tag's items once tags bind, then the items mods added. */
    static synchronized List<Object> members(String tag) {
        List<Object> members = new ArrayList<>();
        try {
            Object bound = boundTag(tagKey(tag));
            if (bound != null) {
                for (Object holder : (Iterable<?>) bound) members.add(call(holder, "value"));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unbound) {
            members.clear();
        }
        for (String id : ADDED.getOrDefault(tag, Set.of())) {
            Object item = item(id);
            if (item != null && !members.contains(item)) members.add(item);
        }
        return Collections.unmodifiableList(members);
    }

    static synchronized boolean addItem(String tag, Object item) {
        try {
            String id = id(item);
            if (id == null || inTag(tag, item)) return false;
            add(tag, List.of(id));
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not add " + item + " to minecraft:" + tag + ": " + cause(e));
            return false;
        }
    }

    /** Records the ids and rewrites the pack, which the next data pack load reads. */
    private static void add(String tag, List<String> ids) {
        if (ids.isEmpty() || !ADDED.computeIfAbsent(tag, t -> new TreeSet<>()).addAll(ids)) return;
        // A loader that cannot offer the pack gets no files, which nothing would read.
        if (!listen()) return;
        try {
            writePack(packRoot(), LegacyDataPackSource.packFormat());
        } catch (IOException | LinkageError | RuntimeException e) {
            log("could not write the items added to minecraft:" + tag + " as a data pack: " + cause(e));
        }
    }

    /** Writes each tag with what mods added to it, after clearing what an earlier session wrote. */
    static void writePack(Path root, int packFormat) throws IOException {
        deleteTree(root);
        write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + packFormat + ",\"min_format\":"
                + packFormat + ",\"max_format\":" + packFormat + ",\"description\":\"Retromod legacy item tags\"}}");
        String folder = packFormat >= FIRST_SINGULAR_TAG_FORMAT ? "item" : "items";
        for (Map.Entry<String, Set<String>> tag : ADDED.entrySet()) {
            StringBuilder values = new StringBuilder();
            for (String id : tag.getValue()) {
                if (values.length() > 0) values.append(',');
                values.append("{\"id\":\"").append(id).append("\",\"required\":false}");
            }
            write(root.resolve("data").resolve("minecraft").resolve("tags").resolve(folder).resolve(tag.getKey() + ".json"),
                    "{\"replace\":false,\"values\":[" + values + "]}");
        }
    }

    /** The items an ingredient matches, walking an old compound's children. */
    private static void collect(Object ingredient, List<String> ids) throws ReflectiveOperationException {
        if (VIEWS.contains(ingredient)) return;
        Method children = publicMethod(ingredient.getClass(), "getChildren");
        if (children != null && Collection.class.isAssignableFrom(children.getReturnType())) {
            for (Object child : (Collection<?>) children.invoke(ingredient)) collect(child, ids);
            return;
        }
        // 1.21.2 and newer list holders; 1.20.5 to 1.21.1 list stacks.
        Method items = publicMethod(ingredient.getClass(), "items");
        if (items != null && Stream.class.isAssignableFrom(items.getReturnType())) {
            for (Object holder : ((Stream<?>) items.invoke(ingredient)).toList()) addId(call(holder, "value"), ids);
            return;
        }
        for (Object stack : (Object[]) call(ingredient, "getItems")) addId(call(stack, "getItem"), ids);
    }

    private static void addId(Object item, List<String> ids) throws ReflectiveOperationException {
        String id = id(item);
        if (id != null && !ids.contains(id)) ids.add(id);
    }

    // ---- villager food component ---------------------------------------------------------------

    /** Adds an initializer that sets the item's villager food when components are built, as each world load does. */
    private static void addVillagerFood(Object item, int nutrition) throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> initializer = Class.forName("net.minecraft.core.component.DataComponentInitializers$Initializer", true, loader);
        Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", true, loader);
        Object food = Class.forName("net.minecraft.world.food.VillagerFood", true, loader)
                .getConstructor(int.class).newInstance(nutrition);
        Object component = villagerFoodComponent();
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("run") && args != null && args.length == 3) {
                LegacyPublicMethods.method(args[0].getClass(), "set", componentType, Object.class).invoke(args[0], component, food);
                return null;
            }
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Retromod villager food " + nutrition;
                default -> InvocationHandler.invokeDefault(proxy, method, args);
            };
        };
        Object init = Proxy.newProxyInstance(initializer.getClassLoader(), new Class<?>[] {initializer}, handler);
        Object initializers = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                .getField("DATA_COMPONENT_INITIALIZERS").get(null);
        Object key = call(call(item, "builtInRegistryHolder"), "key");
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
        LegacyPublicMethods.method(initializers.getClass(), "add", resourceKey, initializer).invoke(initializers, key, init);
    }

    private static Object villagerFoodComponent() throws ReflectiveOperationException {
        return Class.forName("net.minecraft.core.component.DataComponents", true, loader()).getField("VILLAGER_FOOD").get(null);
    }

    // ---- registry access ---------------------------------------------------------------------

    private static Object tagKey(String tag) throws ReflectiveOperationException {
        return Class.forName("net.minecraft.tags.ItemTags", true, loader()).getField(tag.toUpperCase(Locale.ROOT)).get(null);
    }

    private static Object items() throws ReflectiveOperationException {
        return Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader()).getField("ITEM").get(null);
    }

    /** The item registry's set for the tag, or null while tags are unbound, which 1.21.2 and newer report by throwing. */
    private static Object boundTag(Object key) throws ReflectiveOperationException {
        Object registry = items();
        Class<?> tagKey = Class.forName("net.minecraft.tags.TagKey", false, loader());
        Method get;
        try {
            get = LegacyPublicMethods.method(registry.getClass(), "get", tagKey);
        } catch (NoSuchMethodException before1212) {
            get = LegacyPublicMethods.method(registry.getClass(), "getTag", tagKey);
        }
        try {
            return get.invoke(registry, key) instanceof Optional<?> named ? named.orElse(null) : null;
        } catch (InvocationTargetException unbound) {
            return null;
        }
    }

    /** {@code Ingredient.of(set)} on 1.21.2 and newer, with an unbound named set until tags bind; {@code Ingredient.of(tag)} before. */
    private static Object ingredientOf(Object key, Object bound) throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> ingredient = Class.forName("net.minecraft.world.item.crafting.Ingredient", true, loader);
        Class<?> holderSet = Class.forName("net.minecraft.core.HolderSet", true, loader);
        Class<?> tagKey = Class.forName("net.minecraft.tags.TagKey", true, loader);
        Method ofSet;
        try {
            ofSet = ingredient.getMethod("of", holderSet);
        } catch (NoSuchMethodException before1212) {
            return ingredient.getMethod("of", tagKey).invoke(null, key);
        }
        // A named set matches through each item's own tags, so it already answers for the tag before the set is bound.
        Object values = bound != null ? bound : holderSet.getMethod("emptyNamed",
                Class.forName("net.minecraft.core.HolderOwner", true, loader), tagKey).invoke(null, items(), key);
        return ofSet.invoke(null, values);
    }

    private static String id(Object item) throws ReflectiveOperationException {
        if (item == null) return null;
        Object registry = items();
        Object id = LegacyPublicMethods.method(registry.getClass(), "getKey", Object.class).invoke(registry, item);
        return id == null ? null : id.toString();
    }

    private static Object item(String id) {
        try {
            Object registry = items();
            for (Object item : (Iterable<?>) registry) {
                if (id.equals(id(item))) return item;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not find the item " + id + ": " + cause(e));
        }
        return null;
    }

    // ---- data pack -----------------------------------------------------------------------------

    /** Offers the pack whenever the game looks for server data packs; false when this loader cannot. */
    private static boolean listen() {
        if (listening) return offerable;
        listening = true;
        Consumer<Object> offer = LegacyAnimalFoodTags::onAddPackFinders;
        try {
            if (exists(FORGE_EVENT_BUS)) {
                // Forge 26.2 and newer keep the event's bus in a static BUS field.
                Object bus = Class.forName("net.minecraftforge.event.AddPackFindersEvent").getField("BUS").get(null);
                Class.forName(FORGE_EVENT_BUS).getMethod("addListener", Consumer.class).invoke(bus, offer);
                offerable = true;
                return true;
            }
            // NeoForge and older Forge post the event to every mod's bus, so Retromod's own bus hears it.
            Class<?> modList = firstClass("net.neoforged.fml.ModList", "net.minecraftforge.fml.ModList");
            Object mods = modList.getMethod("get").invoke(null);
            Object found = LegacyPublicMethods.method(mods.getClass(), "getModContainerById", String.class).invoke(mods, "retromod");
            Object container = found instanceof Optional<?> optional ? optional.orElse(null) : null;
            if (container == null) throw new IllegalStateException("Retromod's mod container is missing");
            Object bus = LegacyPublicMethods.method(container.getClass(), "getEventBus").invoke(container);
            Class<?> priority = firstClass("net.neoforged.bus.api.EventPriority", "net.minecraftforge.eventbus.api.EventPriority");
            Class<?> event = firstClass("net.neoforged.neoforge.event.AddPackFindersEvent", "net.minecraftforge.event.AddPackFindersEvent");
            firstClass("net.neoforged.bus.api.IEventBus", "net.minecraftforge.eventbus.api.IEventBus")
                    .getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                    .invoke(bus, priority.getField("NORMAL").get(null), false, event, offer);
            offerable = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("cannot offer added food items as a data pack on this loader, so they are not added: " + cause(e));
        }
        return offerable;
    }

    private static void onAddPackFinders(Object event) {
        try {
            Object packType = LegacyPublicMethods.method(event.getClass(), "getPackType").invoke(event);
            if (!(packType instanceof Enum<?> type) || !type.name().equals("SERVER_DATA")) return;
            Object source = LegacyDataPackSource.create(packId(), packRoot(), "Retromod legacy item tags");
            if (source == null) return;
            for (Method method : LegacyPublicMethods.methods(event.getClass())) {
                if (method.getName().equals("addRepositorySource") && method.getParameterCount() == 1) {
                    method.invoke(event, source);
                    LegacyLog.info("Legacy food items: offered " + summary() + " as data pack " + packId());
                    return;
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not offer the added food items as a data pack: " + cause(e));
        }
    }

    /** Each tag with how many items mods added to it, such as {@code minecraft:chicken_food (3)}. */
    private static synchronized String summary() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Set<String>> tag : ADDED.entrySet()) {
            if (out.length() > 0) out.append(", ");
            out.append("minecraft:").append(tag.getKey()).append(" (").append(tag.getValue().size()).append(')');
        }
        return out.toString();
    }

    /**
     * One pack per mod, named after the mod's module, which Forge and NeoForge name after its mod id.
     * A new build of the mod then writes over its old pack instead of leaving one behind per jar hash.
     * Without a module, as on Fabric, the copy's embed prefix names it.
     */
    static String packId() {
        String module = LegacyAnimalFoodTags.class.getModule().getName();
        String self = LegacyAnimalFoodTags.class.getName();
        String prefix = self.substring(0, self.length() - SELF.length());
        String mod = module != null ? module : prefix.replaceFirst("^com\\.retromod\\.embedded\\.", "");
        mod = mod.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_").replaceAll("_+$", "");
        return mod.isEmpty() ? "retromod_legacy_item_tags" : "retromod_legacy_item_tags_" + mod;
    }

    private static Path packRoot() {
        return gameDirectory().resolve("config").resolve("retromod").resolve("legacy-item-tags").resolve(packId());
    }

    private static Path gameDirectory() {
        for (String paths : new String[] {"net.neoforged.fml.loading.FMLPaths", "net.minecraftforge.fml.loading.FMLPaths"}) {
            try {
                Object gameDir = Class.forName(paths).getField("GAMEDIR").get(null);
                if (LegacyPublicMethods.method(gameDir.getClass(), "get").invoke(gameDir) instanceof Path path) return path;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException notThisLoader) {
                // The working directory is the game directory on every launcher.
            }
        }
        return Path.of("").toAbsolutePath();
    }

    // ---- small helpers -------------------------------------------------------------------------

    private static ClassLoader loader() {
        return LegacyAnimalFoodTags.class.getClassLoader();
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return LegacyPublicMethods.method(target.getClass(), name).invoke(target);
    }

    private static Method publicMethod(Class<?> type, String name) {
        try {
            return LegacyPublicMethods.method(type, name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, loader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    private static Class<?> firstClass(String neoForge, String forge) throws ClassNotFoundException {
        try {
            return Class.forName(neoForge);
        } catch (ClassNotFoundException e) {
            return Class.forName(forge);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Throwable cause(Throwable e) {
        return e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
    }

    private static void log(String message) {
        LegacyLog.warn("Legacy food items: " + message);
    }

    /** Records an addition without Minecraft, then clears it. Tests only. */
    static synchronized void addForTests(String tag, List<String> ids) {
        ADDED.computeIfAbsent(tag, t -> new TreeSet<>()).addAll(ids);
    }

    static synchronized void resetForTests() {
        ADDED.clear();
        VILLAGER_FOODS.clear();
        INGREDIENTS.clear();
        VIEWS.clear();
        SETS.clear();
        listening = false;
        offerable = false;
    }
}
