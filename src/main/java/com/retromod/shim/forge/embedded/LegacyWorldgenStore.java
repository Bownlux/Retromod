/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Collects the worldgen a pre-1.19.3 mod registers in code and serves it as a data pack.
 *
 * <p>Up to 1.19.2 a mod registered configured and placed features with
 * {@code FeatureUtils.register} and {@code PlacementUtils.register}, and built its biomes in code.
 * 1.19.3 moved all three into data pack registries, so the calls are gone and nothing the mod builds
 * reaches the world. The mod's own {@code forge/biome_modifier} files still name those features,
 * and a reference to a missing entry stops the world from loading.
 *
 * <p>The generated {@code LegacyWorldgen} class answers the old calls and records each object here
 * under its id. When the loader asks for server data packs, this class writes the recorded objects
 * as JSON, encoded with Minecraft's own codecs, and offers the folder as an always-enabled pack.
 * The entries then load through the normal registry path under the ids the mod used.
 *
 * <p>This class names no Minecraft member, because a Forge host runs SRG member names and this code
 * is not remapped. Everything that needs a Minecraft name lives in the generated class behind
 * {@link Codecs}; the loader calls here use Forge's own API names, which are never obfuscated.
 * Embedded per mod, so each mod keeps its own entries and its own pack.
 */
public final class LegacyWorldgenStore {

    /** The parts that need Minecraft names, implemented by the generated class. */
    public interface Codecs {
        /** JSON for a configured feature, or null when the codec rejects it. */
        String encodeConfigured(Object configuredFeature);

        /** JSON for a placed feature, or null when the codec rejects it. */
        String encodePlaced(Object placedFeature);

        /** JSON for a biome, or null when the codec rejects it. */
        String encodeBiome(Object biome);

        /** The data pack format of the running game. */
        int packFormat();

        /** A {@code RepositorySource} offering {@code root} as a required data pack. */
        Object repositorySource(String packId, Path root);
    }

    static final String CONFIGURED = "configured_feature";
    /** Where 26.3 keeps configured features, which it merged into features. */
    static final String FEATURE = "feature";
    static final String PLACED = "placed_feature";
    static final String BIOME = "biome";

    private static final Map<String, Map<String, Object>> ENTRIES = new LinkedHashMap<>();
    private static final Set<String> NAMESPACES = new TreeSet<>();
    private static Codecs codecs;
    private static boolean listening;

    private LegacyWorldgenStore() {}

    public static synchronized void install(Codecs implementation) {
        if (codecs == null) codecs = implementation;
    }

    /** Remember an object the mod registered in code, keyed by registry folder and id. */
    public static synchronized void record(String registry, String id, Object value) {
        if (id == null || value == null) return;
        ENTRIES.computeIfAbsent(registry, ignored -> new LinkedHashMap<>()).put(id, value);
        int colon = id.indexOf(':');
        String namespace = colon > 0 ? id.substring(0, colon) : null;
        addNamespace(namespace);
        listenForPacks(namespace);
    }

    /**
     * Track a namespace whose biomes belong in the pack. The namespaces also name the pack folder
     * that {@link #writePack} deletes and rewrites, so a namespace that is not a plain resource
     * namespace is refused. {@code minecraft} is refused too: the pack sits above every other pack,
     * so taking vanilla biomes would override other packs' changes to them.
     */
    private static void addNamespace(String namespace) {
        if (namespace == null || "minecraft".equals(namespace)) return;
        if (!isSafeIdPart(namespace) || namespace.contains("/")) return;
        NAMESPACES.add(namespace);
    }

    /** The pack id, built only from namespaces {@link #addNamespace} accepted. */
    static synchronized String packId() {
        return "retromod_legacy_worldgen_" + String.join("_", NAMESPACES);
    }

    /**
     * Note the mod that is building a biome. The biome itself is registered by the mod's
     * {@code DeferredRegister} afterwards, so the store finds it later by this namespace.
     */
    public static synchronized void noteBuildingMod() {
        String namespace = activeNamespace();
        addNamespace(namespace);
        listenForPacks(namespace);
    }

    /**
     * Give a stand-alone holder its value. A stand-alone reference serializes as its id, which is
     * what the generated JSON needs, but it starts unbound. Binding it keeps {@code holder.value()}
     * working for a mod that reads its own feature back.
     */
    public static void bindValue(Object reference, Object value) {
        try {
            for (Field field : reference.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == Object.class) {
                    field.setAccessible(true);
                    if (field.get(reference) == null) field.set(reference, value);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("could not bind a legacy worldgen holder, so its value stays unreadable: " + e);
        }
    }

    public static void encodingFailed(String what, String reason) {
        log("skipped a legacy " + what + " the current codec rejects: " + reason);
    }

    // ---- overworld biome injection ------------------------------------------------------

    /**
     * The parameter list of a multi-noise biome source. 1.19.4 replaced the public list field
     * with an {@code Either} and a private accessor, so the accessor is found by its return type,
     * which reads the same on SRG and Mojang hosts.
     */
    public static Object biomeSourceParameters(Object source) {
        for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                        && method.getReturnType().getName()
                                .equals("net.minecraft.world.level.biome.Climate$ParameterList")) {
                    try {
                        method.setAccessible(true);
                        return method.invoke(source);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        throw new IllegalStateException(
                                "cannot read the biome parameters of " + source.getClass().getName(), e);
                    }
                }
            }
        }
        throw new IllegalStateException("no biome parameter list on " + source.getClass().getName());
    }

    /** The removed {@code MultiNoiseBiomeSource.preset}: no source has one any more. */
    public static java.util.Optional<?> noPreset(Object source) {
        return java.util.Optional.empty();
    }

    /** A write to a field that no longer exists. */
    public static void ignoreWrite(Object owner, Object value) {
        // Nothing to store: the field is gone and its replacement is derived, not assigned.
    }

    // ---- pack generation ------------------------------------------------------------------

    /** Write the recorded entries into {@code root} as a data pack. Returns the files written. */
    static synchronized int writePack(Path root, Codecs with) throws IOException {
        deleteTree(root);
        Files.createDirectories(root);
        String description = "Worldgen registered in code by " + String.join(", ", NAMESPACES)
                + ", restored by Retromod";
        // 1.21.9 replaced pack_format with a format range; each version reads its own key.
        int format = with.packFormat();
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + format
                + ",\"min_format\":" + format + ",\"max_format\":" + format
                + ",\"description\":\"" + description + "\"}}", StandardCharsets.UTF_8);

        // Built first: a biome built here registers its own placed features, which the loop
        // below has to see.
        Map<String, Object> biomes = legacyBiomes();
        int written = 0;
        for (Map.Entry<String, Map<String, Object>> registry : ENTRIES.entrySet()) {
            for (Map.Entry<String, Object> entry : registry.getValue().entrySet()) {
                String json = encode(with, registry.getKey(), entry.getValue());
                if (json != null && writeEntry(root, registry.getKey(), entry.getKey(), json)) {
                    written++;
                }
            }
        }
        for (Map.Entry<String, Object> biome : biomes.entrySet()) {
            String json = encode(with, BIOME, biome.getValue());
            if (json != null && writeEntry(root, BIOME, biome.getKey(), json)) written++;
        }
        return written + writePaintings(root);
    }

    private static String encode(Codecs with, String registry, Object value) {
        try {
            return switch (registry) {
                case CONFIGURED, FEATURE -> with.encodeConfigured(value);
                case PLACED -> with.encodePlaced(value);
                case BIOME -> with.encodeBiome(value);
                default -> null;
            };
        } catch (RuntimeException e) {
            encodingFailed(registry, e.toString());
            return null;
        }
    }

    private static boolean writeEntry(Path root, String registry, String id, String json)
            throws IOException {
        int colon = id.indexOf(':');
        String namespace = colon > 0 ? id.substring(0, colon) : "minecraft";
        String path = id.substring(colon + 1);
        if (!isSafeIdPart(namespace) || !isSafeIdPart(path)) {
            log("skipped legacy worldgen entry with an unusable id: " + id);
            return false;
        }
        Path file = root.resolve("data").resolve(namespace).resolve("worldgen")
                .resolve(registry).resolve(path + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return true;
    }

    /** Resource ids allow only these characters, and no segment may climb out of the pack. */
    static boolean isSafeIdPart(String part) {
        if (part.isEmpty() || part.startsWith("/") || part.endsWith("/")) return false;
        for (String segment : part.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return part.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '/');
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    // ---- Loader glue (reflection on Forge's and NeoForge's own, unobfuscated API) ---------

    /**
     * Biomes the mod put in Forge's biome registry. 1.19.3 stopped reading that registry for
     * worlds, so each one is written into the pack instead. Only this mod's namespaces are taken,
     * which keeps a current mod's biomes, already shipped as JSON, out of the pack.
     */
    private static Map<String, Object> legacyBiomes() {
        Map<String, Object> biomes = new LinkedHashMap<>();
        Map<?, ?> deferred = deferredBiomes();
        if (deferred != null) {
            // NeoForge: the biomes were registered through Retromod's register bridge, which kept
            // their suppliers because NeoForge never builds entries of a data-driven registry.
            for (Map.Entry<?, ?> entry : List.copyOf(deferred.entrySet())) {
                String id = entry.getKey().toString();
                int colon = id.indexOf(':');
                if (colon <= 0 || !NAMESPACES.contains(id.substring(0, colon))) continue;
                try {
                    Object biome = ((java.util.function.Supplier<?>) entry.getValue()).get();
                    if (biome != null) biomes.put(id, biome);
                } catch (RuntimeException | LinkageError e) {
                    // The biome is already named in its registry, and an unbound name stops the
                    // world from loading, so it gets a plain stand-in instead.
                    Object standIn = plainBiome();
                    if (standIn != null) biomes.put(id, standIn);
                    log("could not build the code-registered biome " + id + ", so it loads as a plain biome"
                            + " with no features or spawns: " + e);
                }
            }
            return biomes;
        }
        try {
            Object registry = Class.forName("net.minecraftforge.registries.ForgeRegistries")
                    .getField("BIOMES").get(null);
            // Look members up on the public interface: the implementation class sits in a
            // package the mod's module may not be allowed to reflect into.
            Class<?> registryApi = Class.forName("net.minecraftforge.registries.IForgeRegistry");
            Method keys = registryApi.getMethod("getKeys");
            for (Object key : (Set<?>) keys.invoke(registry)) {
                String id = key.toString();
                int colon = id.indexOf(':');
                if (colon <= 0 || !NAMESPACES.contains(id.substring(0, colon))) continue;
                Method getValue = findSingleArgMethod(registryApi, "getValue", key.getClass());
                Object biome = getValue == null ? null : getValue.invoke(registry, key);
                if (biome != null) biomes.put(id, biome);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not read code-registered biomes, so they are not restored: " + e);
        }
        return biomes;
    }

    /**
     * Painting variants the mod registered in code, which 1.21 made data. Each is written under
     * its registry id, which names its texture as before, with its size, and joins the
     * {@code placeable} tag, because every registered variant could be placed before 1.21.
     */
    private static int writePaintings(Path root) throws IOException {
        Map<?, ?> paintings = deferred("LEGACY_PAINTINGS");
        if (paintings == null) return 0;
        List<String> placeable = new ArrayList<>();
        for (Map.Entry<?, ?> entry : List.copyOf(paintings.entrySet())) {
            String id = entry.getKey().toString();
            int colon = id.indexOf(':');
            if (colon <= 0 || !NAMESPACES.contains(id.substring(0, colon)) || !isSafeIdPart(id.substring(colon + 1))) continue;
            try {
                Object variant = ((java.util.function.Supplier<?>) entry.getValue()).get();
                int width = (Integer) variant.getClass().getMethod("width").invoke(variant);
                int height = (Integer) variant.getClass().getMethod("height").invoke(variant);
                Path file = root.resolve("data").resolve(id.substring(0, colon)).resolve("painting_variant")
                        .resolve(id.substring(colon + 1) + ".json");
                Files.createDirectories(file.getParent());
                Files.writeString(file, "{\"asset_id\":\"" + id + "\",\"width\":" + width + ",\"height\":" + height + "}",
                        StandardCharsets.UTF_8);
                placeable.add("{\"id\":\"" + id + "\",\"required\":false}");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                log("could not write the painting " + id + ": " + e);
            }
        }
        if (placeable.isEmpty()) return 0;
        Path tag = root.resolve("data").resolve("minecraft").resolve("tags").resolve("painting_variant").resolve("placeable.json");
        Files.createDirectories(tag.getParent());
        Files.writeString(tag, "{\"replace\":false,\"values\":[" + String.join(",", placeable) + "]}", StandardCharsets.UTF_8);
        return placeable.size();
    }

    /** A biome with vanilla's void colors and no features or spawns, built with the host's builders. */
    private static Object plainBiome() {
        try {
            ClassLoader loader = LegacyWorldgenStore.class.getClassLoader();
            String biome = "net.minecraft.world.level.biome.";
            Object effects = Class.forName(biome + "BiomeSpecialEffects$Builder", true, loader).getConstructor().newInstance();
            // 1.21.11 moved fog and sky colors out of the effects; older builders require them.
            for (Object[] color : new Object[][] {{"waterColor", 0x3F76E4}, {"fogColor", 0xC0D8FF},
                    {"waterFogColor", 0x050533}, {"skyColor", 0x7BA4FF}}) {
                Method setter = findSingleArgMethod(effects.getClass(), (String) color[0], int.class);
                if (setter != null) setter.invoke(effects, color[1]);
            }
            Object builder = Class.forName(biome + "Biome$BiomeBuilder", true, loader).getConstructor().newInstance();
            builder.getClass().getMethod("hasPrecipitation", boolean.class).invoke(builder, false);
            builder.getClass().getMethod("temperature", float.class).invoke(builder, 0.5F);
            builder.getClass().getMethod("downfall", float.class).invoke(builder, 0.5F);
            Object built = effects.getClass().getMethod("build").invoke(effects);
            findSingleArgMethod(builder.getClass(), "specialEffects", built.getClass()).invoke(builder, built);
            Object spawns = Class.forName(biome + "MobSpawnSettings", true, loader).getField("EMPTY").get(null);
            findSingleArgMethod(builder.getClass(), "mobSpawnSettings", spawns.getClass()).invoke(builder, spawns);
            Object generation = Class.forName(biome + "BiomeGenerationSettings", true, loader).getField("EMPTY").get(null);
            findSingleArgMethod(builder.getClass(), "generationSettings", generation.getClass()).invoke(builder, generation);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log("could not build a plain stand-in biome: " + e);
            return null;
        }
    }

    /**
     * The biome suppliers recorded by this mod's embedded register bridge, or null when there is
     * none (a Forge host, or a host without either bridge). The bridge is a sibling in this package.
     */
    private static Map<?, ?> deferredBiomes() {
        return deferred("LEGACY_BIOMES");
    }

    /** The recorded suppliers in one of the register bridge's maps, or null. */
    private static Map<?, ?> deferred(String map) {
        // RegistryIdBridge on 26.1 and newer, LegacyBiomeRecorder on NeoForge 1.20.5 to 1.21.11.
        for (String recorder : new String[] {"RegistryIdBridge", "LegacyBiomeRecorder"}) {
            try {
                Class<?> bridge = Class.forName(LegacyWorldgenStore.class.getPackageName() + "." + recorder,
                        true, LegacyWorldgenStore.class.getClassLoader());
                return (Map<?, ?>) bridge.getField(map).get(null);
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                // Not in this mod; try the other one.
            }
        }
        return null;
    }

    private static void listenForPacks(String namespace) {
        if (listening) return;
        try {
            Object bus = modEventBus(namespace);
            if (bus == null) return;
            Class<?> eventType = firstClass("net.minecraftforge.event.AddPackFindersEvent",
                    "net.neoforged.neoforge.event.AddPackFindersEvent");
            Class<?> priorityType = firstClass("net.minecraftforge.eventbus.api.EventPriority",
                    "net.neoforged.bus.api.EventPriority");
            Object normal = priorityType.getField("NORMAL").get(null);
            Method addListener = firstClass("net.minecraftforge.eventbus.api.IEventBus", "net.neoforged.bus.api.IEventBus")
                    .getMethod("addListener", priorityType, boolean.class, Class.class, Consumer.class);
            Consumer<Object> listener = LegacyWorldgenStore::onAddPackFinders;
            addListener.invoke(bus, normal, false, eventType, listener);
            listening = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("cannot offer code-registered worldgen as a data pack on this loader: " + e);
            listening = true;
        }
    }

    private static Object modEventBus(String namespace) throws ReflectiveOperationException {
        try {
            Object context = Class.forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext")
                    .getMethod("get").invoke(null);
            if (context != null) return context.getClass().getMethod("getModEventBus").invoke(context);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No active mod context on this thread: look the mod up by its namespace instead.
        }
        if (namespace == null) return null;
        Object modList = firstClass("net.minecraftforge.fml.ModList", "net.neoforged.fml.ModList")
                .getMethod("get").invoke(null);
        Object found = modList.getClass().getMethod("getModContainerById", String.class)
                .invoke(modList, namespace);
        Object container = found instanceof java.util.Optional<?> optional ? optional.orElse(null) : null;
        if (container == null) return null;
        Method getEventBus = container.getClass().getMethod("getEventBus");
        return getEventBus.invoke(container);
    }

    private static String activeNamespace() {
        try {
            Object context = firstClass("net.minecraftforge.fml.ModLoadingContext", "net.neoforged.fml.ModLoadingContext")
                    .getMethod("get").invoke(null);
            Object namespace = context.getClass().getMethod("getActiveNamespace").invoke(context);
            return namespace == null ? null : namespace.toString();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static void onAddPackFinders(Object event) {
        Codecs with;
        synchronized (LegacyWorldgenStore.class) {
            with = codecs;
            if (with == null || (ENTRIES.isEmpty() && NAMESPACES.isEmpty())) return;
        }
        try {
            Object packType = event.getClass().getMethod("getPackType").invoke(event);
            if (!(packType instanceof Enum<?> type) || !type.name().equals("SERVER_DATA")) return;

            String packId = packId();
            Path root = gameDirectory().resolve("config").resolve("retromod")
                    .resolve("legacy-worldgen").resolve(packId);
            Object source = with.repositorySource(packId, root);
            if (source == null) {
                log("this Minecraft version's pack API is not bridged, so legacy worldgen is not restored");
                return;
            }
            int written = writePack(root, with);
            Method add = findSingleArgMethod(event.getClass(), "addRepositorySource", source.getClass());
            if (add == null) {
                log("this loader's pack event has no addRepositorySource, so legacy worldgen is not restored");
                return;
            }
            add.invoke(event, source);
            log("restored " + written + " code-registered worldgen entries for "
                    + String.join(", ", NAMESPACES) + " as data pack " + packId);
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not write the legacy worldgen data pack: " + e);
        }
    }

    private static Path gameDirectory() {
        try {
            Class<?> paths = firstClass("net.minecraftforge.fml.loading.FMLPaths", "net.neoforged.fml.loading.FMLPaths");
            Object gameDir = paths.getField("GAMEDIR").get(null);
            Object path = gameDir.getClass().getMethod("get").invoke(gameDir);
            if (path instanceof Path resolved) return resolved;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // Fall back to the working directory, which is the game directory on every launcher.
        }
        return Path.of("").toAbsolutePath();
    }

    /** Forge's class, or NeoForge's when the host is NeoForge. */
    private static Class<?> firstClass(String forge, String neoForge) throws ClassNotFoundException {
        try {
            return Class.forName(forge);
        } catch (ClassNotFoundException e) {
            return Class.forName(neoForge);
        }
    }

    private static Method findSingleArgMethod(Class<?> owner, String name, Class<?> argument) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(argument)) {
                return method;
            }
        }
        return null;
    }

    private static void log(String message) {
        System.out.println("[Retromod] Legacy worldgen: " + message);
    }

    // ---- test hooks -----------------------------------------------------------------------

    /** Clears recorded state. Tests only: production state lives for the whole game session. */
    static synchronized void resetForTests() {
        ENTRIES.clear();
        NAMESPACES.clear();
        codecs = null;
        listening = false;
    }

    static synchronized Map<String, Map<String, Object>> entriesForTests() {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        ENTRIES.forEach((registry, entries) -> copy.put(registry, new LinkedHashMap<>(entries)));
        return copy;
    }
}
