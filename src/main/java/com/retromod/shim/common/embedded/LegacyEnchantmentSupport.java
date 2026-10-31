/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import com.retromod.shim.forge.embedded.LegacyDataPackSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Runtime half of {@link com.retromod.shim.common.LegacyEnchantmentBridge}: keeps the enchantments
 * an older mod defines in code, writes them into a data pack, and answers the old level lookups.
 * Embedded into each mod and reflective, because Retromod is not compiled against Minecraft or a
 * loader; the names are Mojang's on every host the bridge runs on.
 *
 * <p>1.21 made enchantments data pack entries and {@code Enchantment} a final record. A mod's
 * enchantment now builds on a generated class with the old methods, and the registration that used
 * to put it in the registry records it here instead. When the game looks for server data packs,
 * each recorded enchantment becomes {@code enchantment/<name>.json} with its old maximum level,
 * costs, weight, slots, and items, and joins the enchanting table, loot, and trading tags its old
 * flags put it in. A level lookup reads the item's enchantments and matches them by id.
 */
public final class LegacyEnchantmentSupport {

    private static final String SELF = "com.retromod.shim.common.embedded.LegacyEnchantmentSupport";
    private static final Map<String, Object> REGISTERED = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Object> VIEWS = new ConcurrentHashMap<>();
    private static String owner;
    private static boolean listening;

    private LegacyEnchantmentSupport() {}

    // ---- registration ----------------------------------------------------------------------

    /** The mod registered {@code enchantment} as {@code id}; it goes into the legacy enchantment pack. */
    public static synchronized void record(String id, Object enchantment) {
        if (id == null || enchantment == null) return;
        setField(enchantment, "id", id);
        REGISTERED.put(id, enchantment);
        if (owner == null) owner = namespace(id);
        listen();
    }

    /** {@code ResourceKey.create(registryKey, id)} for a holder of a recorded enchantment. */
    public static Object key(Object registryKey, String id) {
        try {
            ClassLoader loader = registryKey.getClass().getClassLoader();
            Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
            Object location = identifier(loader, id);
            for (Method method : keyClass.getMethods()) {
                if (method.getName().equals("create") && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == keyClass) {
                    return method.invoke(null, registryKey, location);
                }
            }
            throw new NoSuchMethodException("ResourceKey.create");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not name the enchantment " + id, e);
        }
    }

    /** The old enchantment object for a vanilla constant, now a registry key: one per id, so {@code ==} holds. */
    public static Object view(Object resourceKey, Class<?> legacyClass) {
        String id = keyId(resourceKey);
        return VIEWS.computeIfAbsent(id, key -> {
            try {
                return legacyClass.getConstructor(String.class).newInstance(key);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not stand in for the enchantment " + key, e);
            }
        });
    }

    // ---- Architectury registration ---------------------------------------------------------

    // Architectury's registers for minecraft:enchantment. Their register() asks for a registrar,
    // which a data-driven registry no longer has, so it is skipped for them.
    private static final Set<Object> ARCHITECTURY_ENCHANTMENT_REGISTERS =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new WeakHashMap<>()));

    /** Architectury {@code DeferredRegister.register(name, supplier)}. */
    public static Object architecturyRegister(Object register, String name, Supplier<?> supplier) {
        return architecturyEntry(register, invokeRegister(register, new Class<?>[] {String.class, Supplier.class},
                name, supplier), supplier);
    }

    /** Architectury {@code DeferredRegister.register(id, supplier)}. */
    public static Object architecturyRegisterId(Object register, Object id, Supplier<?> supplier) {
        return architecturyEntry(register, invokeRegister(register, null, id, supplier), supplier);
    }

    /** Architectury {@code DeferredRegister.register()}, which an enchantment register skips. */
    public static void architecturyRegisterAll(Object register) {
        if (ARCHITECTURY_ENCHANTMENT_REGISTERS.contains(register)) return;
        invokeRegister(register, new Class<?>[0]);
    }

    // Architectury's own entry only records the supplier, so it is safe to create; for an
    // enchantment, the entry is built now, recorded for the pack, and answered by a stand-in.
    private static Object architecturyEntry(Object register, Object entry, Supplier<?> supplier) {
        try {
            if (!"minecraft:enchantment".equals(String.valueOf(publicCall(entry, "getRegistryId")))) return entry;
            ARCHITECTURY_ENCHANTMENT_REGISTERS.add(register);
            Object enchantment = supplier.get();
            record(String.valueOf(publicCall(entry, "getId")), enchantment);
            Class<?> type = Class.forName("dev.architectury.registry.registries.RegistrySupplier", false,
                    entry.getClass().getClassLoader());
            // A lambda, not a nested class: this class is embedded into each mod on its own.
            InvocationHandler handler = (proxy, method, args) -> answer(entry, enchantment, proxy, method, args);
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not register an old enchantment through Architectury", e);
        }
    }

    /** A no-argument call through the entry's public interfaces, since its own class is package-private. */
    private static Object publicCall(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type : target.getClass().getInterfaces()) {
            try {
                return type.getMethod(name).invoke(target);
            } catch (NoSuchMethodException notHere) {
                // the next interface may declare it
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    /** {@code register.register(args)} on Architectury's own method, the overload chosen by {@code types}. */
    private static Object invokeRegister(Object register, Class<?>[] types, Object... args) {
        try {
            Method method = null;
            for (Method candidate : register.getClass().getMethods()) {
                if (!candidate.getName().equals("register") || candidate.getParameterCount() != args.length) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                // The id overload takes the host's identifier class, whose name changed in 26.1.
                boolean fits = types == null ? parameters[0] != String.class : java.util.Arrays.equals(parameters, types);
                if (fits) method = candidate;
            }
            if (method == null) throw new NoSuchMethodException("DeferredRegister.register with " + args.length + " arguments");
            return method.invoke(register, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not call Architectury's DeferredRegister.register", e);
        }
    }

    /** Answers for an Architectury entry whose enchantment lives in the legacy enchantment pack. */
    @SuppressWarnings("unchecked")
    private static Object answer(Object entry, Object enchantment, Object proxy, Method method, Object[] args)
            throws Throwable {
        switch (method.getName()) {
            case "get", "value", "getOrNull" -> {
                return enchantment;
            }
            case "isPresent", "isBound" -> {
                return true;
            }
            case "listen" -> {
                ((Consumer<Object>) args[0]).accept(enchantment);
                return null;
            }
            case "getId", "getRegistryId", "getKey", "getRegistryKey" -> {
                return method.invoke(entry, args);
            }
            case "unwrapKey" -> {
                return Optional.of(publicCall(entry, "getKey"));
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == args[0];
            }
            case "toString" -> {
                return "RegistrySupplier[" + publicCall(entry, "getId") + " (Retromod legacy enchantment)]";
            }
            default -> {
                // Default methods such as ifPresent and toOptional build on the answers above.
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                return method.invoke(entry, args);
            }
        }
    }

    // ---- lookups ---------------------------------------------------------------------------

    /** {@code EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack)}. */
    public static int level(Object enchantment, Object stack) {
        String id = idOf(enchantment);
        if (id == null || stack == null) return 0;
        try {
            Object enchantments = enchantmentsOf(stack);
            if (enchantments == null) return 0;
            Collection<?> entries = (Collection<?>) call(enchantments, "entrySet");
            for (Object entry : entries) {
                Object holder = call(entry, "getKey");
                Object key = call(holder, "unwrapKey");
                if (key instanceof Optional<?> optional && optional.isPresent() && id.equals(keyId(optional.get()))) {
                    return ((Number) call(entry, "getIntValue")).intValue();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
        return 0;
    }

    /** {@code ItemStack.getEnchantmentLevel(enchantment)}, with the stack first. */
    public static int stackLevel(Object stack, Object enchantment) {
        return level(enchantment, stack);
    }

    /** {@code EnchantmentHelper.getEnchantmentLevel(enchantment, entity)}: the highest level it wears or holds. */
    public static int levelOn(Object enchantment, Object entity) {
        if (entity == null) return 0;
        int best = 0;
        try {
            Class<?> slots = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, entity.getClass().getClassLoader());
            Method getItem = findMethod(entity.getClass(), "getItemBySlot", 1);
            for (Object slot : slots.getEnumConstants()) {
                best = Math.max(best, level(enchantment, getItem.invoke(entity, slot)));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return best;
        }
        return best;
    }

    /** {@code ItemStack.enchant(enchantment, level)}, through the server's enchantment registry. */
    public static void enchant(Object stack, Object enchantment, int level) {
        String id = idOf(enchantment);
        try {
            ClassLoader loader = stack.getClass().getClassLoader();
            Object server = currentServer(loader);
            if (server == null || id == null) return;
            Object access = call(server, "registryAccess");
            Object registryKey = Class.forName("net.minecraft.core.registries.Registries", true, loader)
                    .getField("ENCHANTMENT").get(null);
            Object lookup = findMethod(access.getClass(), "lookupOrThrow", 1).invoke(access, registryKey);
            Object holder = findMethod(lookup.getClass(), "get", 1).invoke(lookup, key(registryKey, id));
            if (holder instanceof Optional<?> optional && optional.isPresent()) {
                for (Method method : stack.getClass().getMethods()) {
                    if (method.getName().equals("enchant") && method.getParameterCount() == 2) {
                        method.invoke(stack, optional.get(), level);
                        return;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("could not put " + id + " on an item: " + e);
        }
    }

    /** The old {@code canEnchant(stack)}: what the enchantment's category accepts. */
    public static boolean canEnchant(Object enchantment, Object stack) {
        Object category = getField(enchantment, "category");
        if (category == null || stack == null) return true;
        try {
            return Boolean.TRUE.equals(findMethod(category.getClass(), "canEnchant", 1)
                    .invoke(category, call(stack, "getItem")));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** The old translation key, {@code enchantment.<namespace>.<path>} unless the mod set its own. */
    public static String descriptionId(Object enchantment) {
        Object own = getField(enchantment, "descriptionId");
        if (own != null) return String.valueOf(own);
        String id = idOf(enchantment);
        return id == null ? "enchantment.unregistered" : "enchantment." + id.replace(':', '.');
    }

    // ---- data pack -------------------------------------------------------------------------

    private static void listen() {
        if (listening || owner == null) return;
        listening = true;
        try {
            Class<?> modList = firstClass("net.neoforged.fml.ModList", "net.minecraftforge.fml.ModList");
            Object mods = modList.getMethod("get").invoke(null);
            Object found = mods.getClass().getMethod("getModContainerById", String.class).invoke(mods, owner);
            Object container = found instanceof Optional<?> optional ? optional.orElse(null) : null;
            if (container == null) {
                log("could not find mod " + owner + ", so its enchantments are not added");
                return;
            }
            Object bus = container.getClass().getMethod("getEventBus").invoke(container);
            Class<?> priority = firstClass("net.neoforged.bus.api.EventPriority", "net.minecraftforge.eventbus.api.EventPriority");
            Class<?> event = firstClass("net.neoforged.neoforge.event.AddPackFindersEvent",
                    "net.minecraftforge.event.AddPackFindersEvent");
            Consumer<Object> listener = LegacyEnchantmentSupport::onAddPackFinders;
            firstClass("net.neoforged.bus.api.IEventBus", "net.minecraftforge.eventbus.api.IEventBus")
                    .getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                    .invoke(bus, priority.getField("NORMAL").get(null), false, event, listener);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("cannot offer enchantments as a data pack on this loader: " + e);
        }
    }

    private static void onAddPackFinders(Object event) {
        try {
            Object packType = event.getClass().getMethod("getPackType").invoke(event);
            if (!(packType instanceof Enum<?> type) || !type.name().equals("SERVER_DATA")) return;
            String packId = "retromod_legacy_enchantments_" + owner;
            Path root = gameDirectory().resolve("config").resolve("retromod").resolve("legacy-enchantments").resolve(packId);
            int written = writePack(root, LegacyDataPackSource.packFormat());
            if (written == 0) return;
            Object source = LegacyDataPackSource.create(packId, root);
            if (source == null) return;
            for (Method method : event.getClass().getMethods()) {
                if (method.getName().equals("addRepositorySource") && method.getParameterCount() == 1) {
                    method.invoke(event, source);
                    log("added " + written + " enchantments from " + owner + " as data pack " + packId);
                    return;
                }
            }
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not write the enchantments of " + owner + " as a data pack: " + e);
        }
    }

    /** Writes every recorded enchantment, its tags, and its item tag, and returns how many there are. */
    static int writePack(Path root, int packFormat) throws IOException {
        deleteTree(root);
        Map<String, Object> enchantments;
        synchronized (REGISTERED) {
            enchantments = new LinkedHashMap<>(REGISTERED);
        }
        if (enchantments.isEmpty()) return 0;
        write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + packFormat + ",\"min_format\":"
                + packFormat + ",\"max_format\":" + packFormat + ",\"description\":\"Retromod legacy enchantments\"}}");
        Map<String, List<Object>> tags = new LinkedHashMap<>();
        int written = 0;
        for (Map.Entry<String, Object> entry : enchantments.entrySet()) {
            String id = entry.getKey();
            Object enchantment = entry.getValue();
            try {
                Map<String, Object> json = definition(root, id, enchantment);
                write(dataFile(root, id, "enchantment"), json(json));
                for (String tag : tagsOf(enchantment)) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", id);
                    value.put("required", false);
                    tags.computeIfAbsent(tag, t -> new ArrayList<>()).add(value);
                }
                written++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                log("could not describe the enchantment " + id + ", so it is not added: " + e);
            }
        }
        for (Map.Entry<String, List<Object>> tag : tags.entrySet()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("replace", false);
            json.put("values", tag.getValue());
            write(root.resolve("data").resolve("minecraft").resolve("tags").resolve("enchantment")
                    .resolve(tag.getKey() + ".json"), json(json));
        }
        return written;
    }

    /** The 1.21 enchantment definition the old methods describe. */
    static Map<String, Object> definition(Path root, String id, Object enchantment) throws ReflectiveOperationException, IOException {
        int maxLevel = intCall(enchantment, "getMaxLevel");
        Object rarity = call(enchantment, "getRarity");
        Map<String, Object> json = new LinkedHashMap<>();
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("translate", String.valueOf(call(enchantment, "getDescriptionId")));
        json.put("description", description);
        json.put("supported_items", supportedItems(root, id, enchantment));
        json.put("weight", rarity == null ? 10 : intCall(rarity, "getWeight"));
        json.put("max_level", Math.max(1, maxLevel));
        json.put("min_cost", cost(enchantment, "getMinCost"));
        json.put("max_cost", cost(enchantment, "getMaxCost"));
        json.put("anvil_cost", rarity == null ? 1 : intCall(rarity, "anvilCost"));
        json.put("slots", slots(enchantment));
        json.put("effects", new LinkedHashMap<>());
        return json;
    }

    /** {@code {"base", "per_level_above_first"}} from the old cost at levels 1 and 2, which was linear in vanilla. */
    private static Map<String, Object> cost(Object enchantment, String method) throws ReflectiveOperationException {
        Method cost = findMethod(enchantment.getClass(), method, 1);
        int first = ((Number) cost.invoke(enchantment, 1)).intValue();
        int second = ((Number) cost.invoke(enchantment, 2)).intValue();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("base", first);
        json.put("per_level_above_first", second - first);
        return json;
    }

    /** The category's vanilla item tag, or an item tag of everything a mod's category accepts. */
    private static String supportedItems(Path root, String id, Object enchantment) throws ReflectiveOperationException, IOException {
        Object category = getField(enchantment, "category");
        if (category == null) return "#minecraft:enchantable/durability";
        Object vanilla = call(category, "itemTag");
        if (vanilla != null) return String.valueOf(vanilla);
        if ("ALL".equals(String.valueOf(call(category, "name")))) return "#minecraft:enchantable/durability";
        Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true,
                enchantment.getClass().getClassLoader()).getField("ITEM").get(null);
        Method canEnchant = findMethod(category.getClass(), "canEnchant", 1);
        List<Object> values = new ArrayList<>();
        for (Object entry : (Collection<?>) call(items, "entrySet")) {
            Map.Entry<?, ?> item = (Map.Entry<?, ?>) entry;
            if (Boolean.TRUE.equals(canEnchant.invoke(category, item.getValue()))) values.add(keyId(item.getKey()));
        }
        String path = "legacy_enchantable/" + path(id);
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("replace", false);
        tag.put("values", values);
        write(root.resolve("data").resolve(namespace(id)).resolve("tags").resolve("item").resolve(path + ".json"), json(tag));
        return "#" + namespace(id) + ":" + path;
    }

    /** The slot groups the old slots name; none meant any slot. */
    private static List<Object> slots(Object enchantment) {
        List<Object> groups = new ArrayList<>();
        Object slots = getField(enchantment, "slots");
        if (slots instanceof Object[] array) {
            for (Object slot : array) {
                if (slot == null) continue;
                String name;
                try {
                    name = String.valueOf(call(slot, "getSerializedName"));
                } catch (ReflectiveOperationException e) {
                    name = String.valueOf(slot).toLowerCase(java.util.Locale.ROOT);
                }
                if (!groups.contains(name)) groups.add(name);
            }
        }
        if (groups.isEmpty()) groups.add("any");
        return groups;
    }

    /** The enchantment tags the old flags put it in. */
    private static List<String> tagsOf(Object enchantment) throws ReflectiveOperationException {
        List<String> tags = new ArrayList<>();
        boolean curse = boolCall(enchantment, "isCurse");
        boolean treasure = boolCall(enchantment, "isTreasureOnly");
        boolean discoverable = boolCall(enchantment, "isDiscoverable");
        if (curse) tags.add("curse");
        if (treasure) tags.add("treasure");
        else if (discoverable) {
            tags.add("non_treasure");
            tags.add("in_enchanting_table");
        }
        if (discoverable) tags.add("on_random_loot");
        if (boolCall(enchantment, "isTradeable")) tags.add("tradeable");
        return tags;
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The id a recorded or viewed enchantment has, or null. */
    public static String idOf(Object enchantment) {
        Object id = getField(enchantment, "id");
        return id == null ? null : String.valueOf(id);
    }

    /** {@code ResourceKey[minecraft:enchantment / minecraft:sharpness]} -> {@code minecraft:sharpness}. */
    static String keyId(Object resourceKey) {
        String text = String.valueOf(resourceKey);
        int slash = text.lastIndexOf(" / ");
        return slash < 0 ? text : text.substring(slash + 3).replace("]", "").trim();
    }

    private static Object enchantmentsOf(Object stack) throws ReflectiveOperationException {
        try {
            return stack.getClass().getMethod("getEnchantments").invoke(stack);
        } catch (NoSuchMethodException newer) {
            ClassLoader loader = stack.getClass().getClassLoader();
            Object type = Class.forName("net.minecraft.core.component.DataComponents", true, loader)
                    .getField("ENCHANTMENTS").get(null);
            return findMethod(stack.getClass(), "get", 1).invoke(stack, type);
        }
    }

    private static Object identifier(ClassLoader loader, String id) throws ReflectiveOperationException {
        Class<?> type;
        try {
            type = Class.forName("net.minecraft.resources.Identifier", true, loader);
        } catch (ClassNotFoundException before26) {
            type = Class.forName("net.minecraft.resources.ResourceLocation", true, loader);
        }
        return type.getMethod("parse", String.class).invoke(null, id);
    }

    private static Object currentServer(ClassLoader loader) {
        for (String hooks : new String[] {"net.neoforged.neoforge.server.ServerLifecycleHooks",
                "net.minecraftforge.server.ServerLifecycleHooks"}) {
            try {
                return Class.forName(hooks, true, loader).getMethod("getCurrentServer").invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                // not this loader
            }
        }
        return null;
    }

    private static Path dataFile(Path root, String id, String folder) {
        return root.resolve("data").resolve(namespace(id)).resolve(folder).resolve(path(id) + ".json");
    }

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), name, 0);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return method.invoke(target);
    }

    private static int intCall(Object target, String name) throws ReflectiveOperationException {
        return ((Number) call(target, name)).intValue();
    }

    private static boolean boolCall(Object target, String name) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(target, name));
    }

    /** A public or inherited method by name and arity, made accessible. */
    private static Method findMethod(Class<?> type, String name, int arity) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) return method;
        }
        return null;
    }

    private static Object getField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // keep looking up the hierarchy
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // keep looking up the hierarchy
            } catch (ReflectiveOperationException | RuntimeException e) {
                return;
            }
        }
    }

    private static Class<?> firstClass(String neoForge, String forge) throws ClassNotFoundException {
        try {
            return Class.forName(neoForge);
        } catch (ClassNotFoundException e) {
            return Class.forName(forge);
        }
    }

    private static Path gameDirectory() {
        for (String paths : new String[] {"net.neoforged.fml.loading.FMLPaths", "net.minecraftforge.fml.loading.FMLPaths"}) {
            try {
                Object gameDir = Class.forName(paths).getField("GAMEDIR").get(null);
                if (gameDir.getClass().getMethod("get").invoke(gameDir) instanceof Path path) return path;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                // not this loader
            }
        }
        return Path.of("").toAbsolutePath();
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

    /** JSON for the maps, lists, strings, numbers, and booleans this class builds. */
    static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(json(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) {
                if (out.length() > 1) out.append(',');
                out.append(json(item));
            }
            return out.append(']').toString();
        }
        if (value instanceof String text) return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        return String.valueOf(value);
    }

    private static void log(String message) {
        System.out.println("[Retromod] Legacy enchantments: " + message);
    }

    /** Clears recorded state. Tests only. */
    static synchronized void resetForTests() {
        REGISTERED.clear();
        VIEWS.clear();
        owner = null;
        listening = true;
    }

    static void recordForTests(String id, Object enchantment) {
        setField(enchantment, "id", id);
        REGISTERED.put(id, enchantment);
    }
}
