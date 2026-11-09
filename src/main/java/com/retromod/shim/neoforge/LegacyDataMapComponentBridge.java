/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.core.ClassResourceInspector;
import com.retromod.inplace.TranslatedJarMarkers;
import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.shim.common.LegacyItemComponentValues;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import com.retromod.util.RetromodLog;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/** Carries old NeoForge fuel and compost data maps into the item components NeoForge 26.3 uses instead. */
public final class LegacyDataMapComponentBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    static final String FUEL_FILE = "data/neoforge/data_maps/item/furnace_fuels.json";
    static final String COMPOST_FILE = "data/neoforge/data_maps/item/compostables.json";
    private static final String CONDITIONS = "neoforge:conditions";
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    // The data map types NeoForge 26.3 deleted, and the class whose fields replaced them.
    static final String FURNACE_FUEL_TYPE = "net/neoforged/neoforge/registries/datamaps/builtin/FurnaceFuel";
    static final String COMPOSTABLE_TYPE = "net/neoforged/neoforge/registries/datamaps/builtin/Compostable";
    static final String DATA_COMPONENTS = "net/minecraft/core/component/DataComponents";

    // NeoForge's default component event and the two interfaces the bridge hands it.
    private static final String EVENT = "net/neoforged/neoforge/event/ModifyDefaultComponentsEvent";
    static final String PREDICATE = EVENT + "$ItemWithComponentsPredicate";
    static final String INITIALIZER = EVENT + "$Initializer";
    static final Member MODIFY_MATCHING = Member.virtual(EVENT, "modifyMatching",
            "(L" + PREDICATE + ";L" + INITIALIZER + ";)V");
    static final Member PREDICATE_TEST = Member.virtual(PREDICATE, "test",
            "(Lnet/minecraft/world/item/Item;Lnet/minecraft/core/component/DataComponentGetter;)Z");
    static final Member INITIALIZER_RUN = Member.virtual(INITIALIZER, "run",
            "(Lnet/minecraft/core/component/DataComponentMap$Builder;Lnet/minecraft/core/HolderLookup$Provider;"
                    + "Lnet/minecraft/world/item/Item;)V");

    // The Minecraft calls that name an item and test it against an item tag.
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    static final Member ITEM_HOLDER = Member.virtual("net/minecraft/world/item/Item", "builtInRegistryHolder",
            "()Lnet/minecraft/core/Holder$Reference;");
    static final Member HOLDER_KEY = Member.virtual("net/minecraft/core/Holder$Reference", "key", "()" + KEY);
    static final Member KEY_IDENTIFIER = Member.virtual("net/minecraft/resources/ResourceKey", "identifier",
            "()Lnet/minecraft/resources/Identifier;");
    static final Member ITEM_REGISTRY = new Member("net/minecraft/core/registries/Registries", "ITEM", KEY, true);
    static final Member LOOKUP_OR_THROW = Member.virtual("net/minecraft/core/HolderLookup$Provider",
            "lookupOrThrow", "(" + KEY + ")Lnet/minecraft/core/HolderLookup$RegistryLookup;");
    static final Member TAG_SET = Member.virtual("net/minecraft/core/HolderGetter", "get",
            "(Lnet/minecraft/tags/TagKey;)Ljava/util/Optional;");
    static final Member SET_CONTAINS = Member.virtual("net/minecraft/core/HolderSet", "contains",
            "(Lnet/minecraft/core/Holder;)Z");
    static final Member CREATE_TAG = Member.staticMethod("net/minecraft/tags/TagKey", "create",
            "(" + KEY + "Lnet/minecraft/resources/Identifier;)Lnet/minecraft/tags/TagKey;");
    static final Member PARSE_ID = Member.staticMethod("net/minecraft/resources/Identifier", "tryParse",
            "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;");

    /** The two old data maps and the field each value sits in. */
    enum Kind {
        FUEL(FUEL_FILE, "burn_time"),
        COMPOST(COMPOST_FILE, "chance");

        final String file;
        final String field;

        Kind(String file, String field) {
            this.file = file;
            this.field = field;
        }
    }

    private LegacyDataMapComponentBridge() {}

    /** Subscribes on Retromod's own mod bus when this host dropped a data map it still has a component for. */
    public static void listen() {
        Gate gate = Gate.probe(ClassResourceInspector::read);
        if (!gate.any()) return;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> event = Class.forName(EVENT.replace('/', '.'), false, loader);
            Class<?> loadingContext = Class.forName("net.neoforged.fml.ModLoadingContext", false, loader);
            Object context = loadingContext.getMethod("get").invoke(null);
            Object container = loadingContext.getMethod("getActiveContainer").invoke(context);
            Object bus = LegacyPublicMethods.method(container.getClass(), "getEventBus").invoke(container);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object normal = Enum.valueOf((Class) Class.forName("net.neoforged.bus.api.EventPriority", false, loader),
                    "NORMAL");
            Method addListener = Class.forName("net.neoforged.bus.api.IEventBus", false, loader)
                    .getMethod("addListener", normal.getClass(), Class.class, Consumer.class);
            Consumer<Object> listener = posted -> modifyComponents(posted, gate);
            addListener.invoke(bus, normal, event, listener);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Retromod could not listen for NeoForge's default item components, so fuel and "
                    + "compost data maps from translated mods are not applied: {}", e.toString());
        }
    }

    /** Reads the translated jars' old data maps and registers one initializer that applies them. */
    static void modifyComponents(Object event, Gate gate) {
        try {
            Map<Kind, Table> tables = collect(modFilePaths(), gate);
            if (tables.isEmpty()) return;
            Applier applier = new Applier(tables);
            Object predicate = proxy(PREDICATE, (proxy, method, args) -> method.getName().equals("test")
                    ? applier.mayMatch(args[0]) : objectMethod(proxy, method, args));
            Object initializer = proxy(INITIALIZER, (proxy, method, args) -> {
                if (!method.getName().equals("run")) return objectMethod(proxy, method, args);
                applier.apply(args[0], args[1], args[2]);
                return null;
            });
            call(HostLink.require(MODIFY_MATCHING), event, predicate, initializer);
            tables.forEach((kind, table) -> LOGGER.debug(RetromodLog.DETAIL, "Applying {} from {} translated mod(s) as item "
                    + "components, because this NeoForge no longer reads it", kind.file, table.files.size()));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Retromod could not apply the fuel and compost data maps of translated mods, so "
                    + "their items do not burn or compost on this NeoForge: {}", e.toString());
        }
    }

    /** Which of the two maps this host dropped while it has the item component that replaced it. */
    record Gate(boolean fuel, boolean compost) {
        boolean any() {
            return fuel || compost;
        }

        boolean bridges(Kind kind) {
            return kind == Kind.FUEL ? fuel : compost;
        }

        /** Reads the host's class files through {@code host}, which never loads a class. */
        static Gate probe(Function<String, ClassNode> host) {
            boolean fuelMapGone = host.apply(FURNACE_FUEL_TYPE) == null;
            boolean compostMapGone = host.apply(COMPOSTABLE_TYPE) == null;
            // 26.2 and older still read both maps, so the larger class is read only when one is gone.
            if (!fuelMapGone && !compostMapGone) return new Gate(false, false);
            ClassNode components = host.apply(DATA_COMPONENTS);
            return new Gate(fuelMapGone && hasField(components, "COOKING_FUEL"),
                    compostMapGone && hasField(components, "COMPOSTABLE"));
        }

        private static boolean hasField(ClassNode node, String name) {
            return node != null && node.fields.stream().anyMatch(field -> field.name.equals(name));
        }
    }

    // ---- Reading the files -------------------------------------------------------------------

    /** The bridged files of every translated jar, in mod-list order, by kind; empty kinds left out. */
    static Map<Kind, Table> collect(List<Path> jars, Gate gate) {
        Map<Kind, List<MapFile>> files = new EnumMap<>(Kind.class);
        for (Path jar : jars) {
            try {
                readJar(jar).forEach((kind, file) -> {
                    if (gate.bridges(kind)) files.computeIfAbsent(kind, k -> new ArrayList<>()).add(file);
                });
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read the fuel and compost data maps of {}: {}", jar.getFileName(), e.toString());
            }
        }
        Map<Kind, Table> tables = new EnumMap<>(Kind.class);
        files.forEach((kind, list) -> tables.put(kind, new Table(list)));
        return tables;
    }

    /** Both files from a jar Retromod translated; nothing from any other jar. */
    static Map<Kind, MapFile> readJar(Path jar) throws IOException {
        Map<Kind, MapFile> found = new EnumMap<>(Kind.class);
        if (!Files.isRegularFile(jar) || !jar.toString().endsWith(".jar")) return found;
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            // A native 26.3 mod has no reason to ship these, so only translated jars are read.
            if (!TranslatedJarMarkers.isRetromodOutput(file.getManifest())) return found;
            for (Kind kind : Kind.values()) {
                ZipEntry entry = file.getEntry(kind.file);
                if (entry == null) continue;
                try (InputStream input = file.getInputStream(entry)) {
                    String json = new String(ZipSecurity.safeReadAllBytes(input, MAX_FILE_BYTES),
                            StandardCharsets.UTF_8);
                    MapFile parsed = parse(JsonParser.parseString(json), kind);
                    if (parsed != null) found.put(kind, parsed);
                } catch (JsonParseException | IllegalStateException e) {
                    LOGGER.warn("Skipping {} in {}: it is not valid JSON ({})", kind.file, jar.getFileName(),
                            e.getMessage());
                }
            }
        }
        return found;
    }

    /** One file: whether it clears earlier files, its values in order, and the keys it removes. */
    record MapFile(boolean replace, List<Map.Entry<String, Number>> values, List<String> removals) {}

    /** A data map file as NeoForge 1.20.4 to 26.2 read it, or null when it is conditional or not an object. */
    static MapFile parse(JsonElement root, Kind kind) {
        if (!(root instanceof JsonObject object) || object.has(CONDITIONS)) return null;
        List<Map.Entry<String, Number>> values = new ArrayList<>();
        if (object.get("values") instanceof JsonObject entries) {
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                Number value = value(entry.getValue(), kind);
                if (value != null) values.add(Map.entry(normalizedKey(entry.getKey()), value));
            }
        }
        List<String> removals = new ArrayList<>();
        if (object.get("remove") instanceof JsonArray removed) {
            for (JsonElement element : removed) {
                // A removal is a key, or an object naming one; a conditional one is skipped.
                if (element instanceof JsonPrimitive key && key.isString()) {
                    removals.add(normalizedKey(key.getAsString()));
                } else if (element instanceof JsonObject keyed && !keyed.has(CONDITIONS)
                        && keyed.get("key") instanceof JsonPrimitive key && key.isString()) {
                    removals.add(normalizedKey(key.getAsString()));
                }
            }
        }
        boolean replace = object.get("replace") instanceof JsonPrimitive flag && flag.isBoolean()
                && flag.getAsBoolean();
        return new MapFile(replace, values, removals);
    }

    /** {@code n}, {@code {"burn_time": n}}, or either inside {@code {"value": ..., "replace": ...}}. */
    private static Number value(JsonElement element, Kind kind) {
        if (element instanceof JsonObject object) {
            if (object.has(CONDITIONS)) return null;
            if (object.has("value")) return value(object.get("value"), kind);
            element = object.get(kind.field);
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) return null;
        Number number = primitive.getAsNumber();
        if (kind == Kind.FUEL) return number.intValue();
        return number.floatValue();
    }

    /** An item id or {@code #tag} with the namespace NeoForge assumes when one is missing. */
    private static String normalizedKey(String key) {
        boolean tag = key.startsWith("#");
        String id = tag ? key.substring(1) : key;
        if (!id.contains(":")) id = "minecraft:" + id;
        return tag ? "#" + id : id;
    }

    // ---- Working out an item's value ---------------------------------------------------------

    /** One kind's files, indexed so a lookup touches only id hits and tag entries. */
    static final class Table {
        private final List<IndexedFile> files = new ArrayList<>();
        private final Set<String> idKeys = new HashSet<>();
        private boolean hasTagValues;

        Table(List<MapFile> mapFiles) {
            for (MapFile file : mapFiles) {
                IndexedFile indexed = new IndexedFile(file);
                files.add(indexed);
                idKeys.addAll(indexed.idValues.keySet());
                hasTagValues |= !indexed.tagValues.isEmpty();
            }
        }

        /** Whether an item could get a value: its id is named, or some file names a tag. */
        boolean mayMatch(String itemId) {
            return hasTagValues || idKeys.contains(itemId);
        }

        /** The value after every file in order, as NeoForge merged them; null when none is left. */
        Number valueFor(String itemId, Predicate<String> inTag) {
            Number value = null;
            for (IndexedFile file : files) {
                if (file.file.replace()) value = null;
                Number hit = file.lastValue(itemId, inTag);
                if (hit != null) value = hit;
                // NeoForge applies a file's removals after its values.
                if (file.removes(itemId, inTag)) value = null;
            }
            return value;
        }
    }

    private static final class IndexedFile {
        final MapFile file;
        final Map<String, Integer> idValues = new HashMap<>();
        final List<Integer> tagValues = new ArrayList<>();
        final Set<String> idRemovals = new HashSet<>();
        final List<String> tagRemovals = new ArrayList<>();

        IndexedFile(MapFile file) {
            this.file = file;
            for (int index = 0; index < file.values().size(); index++) {
                String key = file.values().get(index).getKey();
                if (key.startsWith("#")) tagValues.add(index);
                else idValues.put(key, index);
            }
            for (String key : file.removals()) {
                if (key.startsWith("#")) tagRemovals.add(key.substring(1));
                else idRemovals.add(key);
            }
        }

        /** The last entry in the file that names the item, by id or by a tag it is in. */
        Number lastValue(String itemId, Predicate<String> inTag) {
            Integer byId = idValues.get(itemId);
            for (int i = tagValues.size() - 1; i >= 0; i--) {
                int index = tagValues.get(i);
                if (byId != null && index < byId) break;
                if (inTag.test(file.values().get(index).getKey().substring(1))) {
                    return file.values().get(index).getValue();
                }
            }
            return byId == null ? null : file.values().get(byId).getValue();
        }

        boolean removes(String itemId, Predicate<String> inTag) {
            if (idRemovals.contains(itemId)) return true;
            for (String tag : tagRemovals) {
                if (inTag.test(tag)) return true;
            }
            return false;
        }
    }

    // ---- Host side ---------------------------------------------------------------------------

    /** Sets each item's component from the old maps whenever the host builds default components. */
    private static final class Applier {
        private final Map<Kind, Table> tables;
        private final Map<String, Optional<Object>> tagKeys = new ConcurrentHashMap<>();

        Applier(Map<Kind, Table> tables) {
            this.tables = tables;
        }

        boolean mayMatch(Object item) {
            try {
                String id = itemId(call(HostLink.require(ITEM_HOLDER), item));
                return tables.values().stream().anyMatch(table -> table.mayMatch(id));
            } catch (RuntimeException e) {
                warnOnce("match", "Could not name an item for old fuel and compost data maps: " + e);
                return false;
            }
        }

        void apply(Object builder, Object provider, Object item) {
            try {
                Object holder = call(HostLink.require(ITEM_HOLDER), item);
                String id = itemId(holder);
                Predicate<String> inTag = tagTester(provider, holder);
                Table fuel = tables.get(Kind.FUEL);
                Number ticks = fuel == null ? null : fuel.valueFor(id, inTag);
                // Only an old value is set; a component the host or another mod gave is never cleared.
                if (ticks != null && ticks.intValue() > 0) {
                    set(builder, LegacyItemComponentValues.COOKING_FUEL,
                            LegacyItemComponentValues.cookingFuel(ticks.intValue()));
                }
                Table compost = tables.get(Kind.COMPOST);
                Number chance = compost == null ? null : compost.valueFor(id, inTag);
                if (chance != null && chance.floatValue() > 0) {
                    set(builder, LegacyItemComponentValues.COMPOSTABLE,
                            LegacyItemComponentValues.compostable(chance.floatValue()));
                }
            } catch (RuntimeException e) {
                warnOnce("apply", "Could not apply an old fuel or compost data map value to " + item + ": " + e);
            }
        }

        /** Tests tags through the lookup the host passes, which holds the reloaded tags. */
        private Predicate<String> tagTester(Object provider, Object holder) {
            Object[] items = new Object[1];
            return tag -> {
                Object key = tagKeys.computeIfAbsent(tag, LegacyDataMapComponentBridge::tagKey).orElse(null);
                if (key == null) return false;
                if (items[0] == null) {
                    items[0] = call(HostLink.require(LOOKUP_OR_THROW), provider,
                            call(HostLink.require(ITEM_REGISTRY)));
                }
                Optional<?> set = (Optional<?>) call(HostLink.require(TAG_SET), items[0], key);
                return set.isPresent() && (boolean) call(HostLink.require(SET_CONTAINS), set.get(), holder);
            };
        }
    }

    private static Optional<Object> tagKey(String tag) {
        Object id = call(HostLink.require(PARSE_ID), tag);
        if (id == null) return Optional.empty();
        return Optional.of(call(HostLink.require(CREATE_TAG), call(HostLink.require(ITEM_REGISTRY)), id));
    }

    /** The {@code namespace:path} id of an item's registry holder. */
    private static String itemId(Object holder) {
        Object key = call(HostLink.require(HOLDER_KEY), holder);
        return String.valueOf(call(HostLink.require(KEY_IDENTIFIER), key));
    }

    private static void set(Object builder, Member type, Object value) {
        call(HostLink.require(LegacyItemComponentValues.BUILDER_SET), builder, call(HostLink.require(type)), value);
    }

    /** Every mod file's path, in the order the mod list holds them. */
    private static List<Path> modFilePaths() {
        List<Path> paths = new ArrayList<>();
        try {
            Class<?> modList = HostLink.type("net/neoforged/fml/ModList").orElseThrow();
            Method getFile = HostLink.type("net/neoforged/neoforgespi/language/IModFileInfo").orElseThrow()
                    .getMethod("getFile");
            Method getFilePath = HostLink.type("net/neoforged/neoforgespi/locating/IModFile").orElseThrow()
                    .getMethod("getFilePath");
            Object mods = modList.getMethod("get").invoke(null);
            for (Object info : (List<?>) modList.getMethod("getModFiles").invoke(mods)) {
                if (getFilePath.invoke(getFile.invoke(info)) instanceof Path path) paths.add(path);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not list the mod files for old fuel and compost data maps: {}", e.toString());
        }
        return paths;
    }

    private static Object proxy(String interfaceName, InvocationHandler handler) {
        Class<?> type = HostLink.type(interfaceName).orElseThrow(() -> new IllegalStateException(
                "This NeoForge has no " + interfaceName + ", so old data maps cannot become item components."));
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** Object methods by identity, and the host interface's own default for anything else. */
    private static Object objectMethod(Object proxy, Method method, Object[] args) throws Throwable {
        boolean noArgs = method.getParameterCount() == 0;
        if (method.getName().equals("equals") && method.getParameterCount() == 1) return proxy == args[0];
        if (method.getName().equals("hashCode") && noArgs) return System.identityHashCode(proxy);
        if (method.getName().equals("toString") && noArgs) return "Retromod legacy data map components";
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        throw new UnsupportedOperationException("Retromod's data map bridge has no answer for " + method.getName());
    }

    private static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.asFixedArity().invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("A host call failed: " + handle, e);
        }
    }

    private static void warnOnce(String key, String message) {
        if (REPORTED.add(key)) LOGGER.warn(message);
    }
}
