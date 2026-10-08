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
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":"
                + with.packFormat() + ",\"description\":\"" + description + "\"}}",
                StandardCharsets.UTF_8);

        int written = 0;
        for (Map.Entry<String, Map<String, Object>> registry : ENTRIES.entrySet()) {
            for (Map.Entry<String, Object> entry : registry.getValue().entrySet()) {
                String json = encode(with, registry.getKey(), entry.getValue());
                if (json != null && writeEntry(root, registry.getKey(), entry.getKey(), json)) {
                    written++;
                }
            }
        }
        for (Map.Entry<String, Object> biome : legacyBiomes().entrySet()) {
            String json = encode(with, BIOME, biome.getValue());
            if (json != null && writeEntry(root, BIOME, biome.getKey(), json)) written++;
        }
        return written;
    }

    private static String encode(Codecs with, String registry, Object value) {
        try {
            return switch (registry) {
                case CONFIGURED -> with.encodeConfigured(value);
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

    // ---- Forge glue (reflection on Forge's own, unobfuscated API) -------------------------

    /**
     * Biomes the mod put in Forge's biome registry. 1.19.3 stopped reading that registry for
     * worlds, so each one is written into the pack instead. Only this mod's namespaces are taken,
     * which keeps a current mod's biomes, already shipped as JSON, out of the pack.
     */
    private static Map<String, Object> legacyBiomes() {
        Map<String, Object> biomes = new LinkedHashMap<>();
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

    private static void listenForPacks(String namespace) {
        if (listening) return;
        try {
            Object bus = modEventBus(namespace);
            if (bus == null) return;
            Class<?> eventType = Class.forName("net.minecraftforge.event.AddPackFindersEvent");
            Class<?> priorityType = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
            Object normal = priorityType.getField("NORMAL").get(null);
            Method addListener = Class.forName("net.minecraftforge.eventbus.api.IEventBus")
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
        Object modList = Class.forName("net.minecraftforge.fml.ModList").getMethod("get").invoke(null);
        Object found = modList.getClass().getMethod("getModContainerById", String.class)
                .invoke(modList, namespace);
        Object container = found instanceof java.util.Optional<?> optional ? optional.orElse(null) : null;
        if (container == null) return null;
        Method getEventBus = container.getClass().getMethod("getEventBus");
        return getEventBus.invoke(container);
    }

    private static String activeNamespace() {
        try {
            Object context = Class.forName("net.minecraftforge.fml.ModLoadingContext")
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
            Class<?> paths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
            Object gameDir = paths.getField("GAMEDIR").get(null);
            Object path = gameDir.getClass().getMethod("get").invoke(gameDir);
            if (path instanceof Path resolved) return resolved;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // Fall back to the working directory, which is the game directory on every launcher.
        }
        return Path.of("").toAbsolutePath();
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
