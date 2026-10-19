/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

/** Rebuilds the armor trim atlas 26.3 removed, from 26.3's own trim textures and palettes. */
public final class LegacyArmorTrims {

    // Everything is reflective, because this class is embedded into each mod and compiled without
    // Minecraft. Each host member is written as {kind, owner, name, descriptor}.
    private static final String STATIC = "static";
    private static final String VIRTUAL = "virtual";
    private static final String CONSTRUCTOR = "constructor";
    private static final String STATIC_FIELD = "static field";

    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String EVENT_LOOP = "net/minecraft/util/thread/BlockableEventLoop";
    private static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String ABSTRACT_TEXTURE = "net/minecraft/client/renderer/texture/AbstractTexture";
    private static final String ATLAS = "net/minecraft/client/renderer/texture/TextureAtlas";
    private static final String ATLAS_MANAGER = "net/minecraft/client/resources/model/sprite/AtlasManager";
    private static final String SPRITE_LOADER = "net/minecraft/client/renderer/texture/SpriteLoader";
    private static final String PREPARATIONS = SPRITE_LOADER + "$Preparations";
    private static final String RENDER_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String RESOURCE_MANAGER = "net/minecraft/server/packs/resources/ResourceManager";
    private static final String RESOURCE_PROVIDER = "net/minecraft/server/packs/resources/ResourceProvider";
    private static final String RESOURCE = "net/minecraft/server/packs/resources/Resource";
    private static final String RESOURCE_METADATA = "net/minecraft/server/packs/resources/ResourceMetadata";
    private static final String SECTION_TYPE = "net/minecraft/server/packs/metadata/MetadataSectionType";
    private static final String PACK = "net/minecraft/server/packs/PackResources";
    private static final String IO_SUPPLIER = "net/minecraft/server/packs/resources/IoSupplier";
    private static final String FILE_TO_ID = "net/minecraft/resources/FileToIdConverter";
    private static final String PALETTE = "net/minecraft/client/resources/palette/Palette";
    private static final String PALETTE_SECTION = "net/minecraft/client/resources/metadata/texture/PaletteMetadataSection";
    private static final String ARMOR_TRIM = "net/minecraft/world/item/equipment/trim/ArmorTrim";
    private static final String TRIM_PATTERN = "net/minecraft/world/item/equipment/trim/TrimPattern";
    private static final String TRIM_MATERIAL = "net/minecraft/world/item/equipment/trim/TrimMaterial";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String EQUIPMENT_INFO = "net/minecraft/client/resources/model/EquipmentClientInfo";
    private static final String TRIM_OVERRIDE = EQUIPMENT_INFO + "$TrimOverride";
    private static final String TRIM_PREDICATE = EQUIPMENT_INFO + "$TrimPredicate";
    private static final String JSON_PARSER = "net/minecraft/util/StrictJsonParser";
    /** Minecraft's Gson. The name is built at runtime so shading cannot point it at Retromod's copy. */
    private static final String JSON_ELEMENT = String.join("/", "com", "google", "gson") + "/JsonElement";
    private static final String DECODER = "com/mojang/serialization/Decoder";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String DATA_RESULT = "com/mojang/serialization/DataResult";
    private static final String DYNAMIC_OPS = "com/mojang/serialization/DynamicOps";
    private static final String JSON_OPS = "com/mojang/serialization/JsonOps";

    private static final String ID = "L" + IDENTIFIER + ";";
    private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
    private static final String OPTIONAL = "Ljava/util/Optional;";

    static final String[] GET_INSTANCE = {STATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";"};
    static final String[] GET_TEXTURE_MANAGER = {VIRTUAL, MINECRAFT, "getTextureManager", "()L" + TEXTURE_MANAGER + ";"};
    static final String[] GET_RESOURCE_MANAGER = {VIRTUAL, MINECRAFT, "getResourceManager", "()L" + RESOURCE_MANAGER + ";"};
    static final String[] SCHEDULE = {VIRTUAL, EVENT_LOOP, "schedule", "(Ljava/lang/Runnable;)V"};
    static final String[] GET_ATLAS_MANAGER = {VIRTUAL, MINECRAFT, "getAtlasManager", "()L" + ATLAS_MANAGER + ";"};
    static final String[] ATLAS_BY_ID = {VIRTUAL, ATLAS_MANAGER, "getAtlasOrThrow", "(" + ID + ")L" + ATLAS + ";"};
    static final String[] BLOCK_ATLAS_ID = {STATIC_FIELD, "net/minecraft/data/AtlasIds", "BLOCKS", ID};
    static final String[] MISSING_SPRITE = {VIRTUAL, ATLAS, "missingSprite",
            "()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"};
    static final String[] CLOSE_ATLAS = {VIRTUAL, ATLAS, "close", "()V"};
    static final String[] ON_RENDER_THREAD = {STATIC, RENDER_SYSTEM, "isOnRenderThread", "()Z"};
    static final String[] IDENTIFIER_OF = {STATIC, IDENTIFIER, "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)" + ID};
    static final String[] NAMESPACE = {VIRTUAL, IDENTIFIER, "getNamespace", "()Ljava/lang/String;"};
    static final String[] PATH = {VIRTUAL, IDENTIFIER, "getPath", "()Ljava/lang/String;"};
    static final String[] REGISTER_TEXTURE = {VIRTUAL, TEXTURE_MANAGER, "register",
            "(" + ID + "L" + ABSTRACT_TEXTURE + ";)V"};
    static final String[] NEW_ATLAS = {CONSTRUCTOR, ATLAS, "<init>", "(" + ID + ")V"};
    static final String[] UPLOAD = {VIRTUAL, ATLAS, "upload", "(L" + PREPARATIONS + ";)V"};
    static final String[] LOADER_FOR = {STATIC, SPRITE_LOADER, "create", "(L" + ATLAS + ";)L" + SPRITE_LOADER + ";"};
    static final String[] LOAD_AND_STITCH = {VIRTUAL, SPRITE_LOADER, "loadAndStitch",
            "(L" + RESOURCE_MANAGER + ";" + ID + "ILjava/util/concurrent/Executor;Ljava/util/Set;)" + FUTURE};
    static final String[] READY_FOR_UPLOAD = {VIRTUAL, PREPARATIONS, "readyForUpload", "()" + FUTURE};
    static final String[] TRIM_RENDER_TYPE = {STATIC, RENDER_TYPES, "armorTrim", "(" + ID + "Z)L" + RENDER_TYPE + ";"};
    static final String[] LIST_PACKS = {VIRTUAL, RESOURCE_MANAGER, "listPacks", "()Ljava/util/stream/Stream;"};
    static final String[] GET_RESOURCE = {VIRTUAL, RESOURCE_PROVIDER, "getResource", "(" + ID + ")" + OPTIONAL};
    static final String[] NEW_RESOURCE = {CONSTRUCTOR, RESOURCE, "<init>", "(L" + PACK + ";L" + IO_SUPPLIER + ";)V"};
    static final String[] METADATA = {VIRTUAL, RESOURCE, "metadata", "()L" + RESOURCE_METADATA + ";"};
    static final String[] OPEN_READER = {VIRTUAL, RESOURCE, "openAsReader", "()Ljava/io/BufferedReader;"};
    static final String[] GET_SECTION = {VIRTUAL, RESOURCE_METADATA, "getSection", "(L" + SECTION_TYPE + ";)" + OPTIONAL};
    static final String[] PALETTE_SECTION_TYPE = {STATIC_FIELD, PALETTE_SECTION, "TYPE", "L" + SECTION_TYPE + ";"};
    static final String[] BASE_PALETTE = {VIRTUAL, PALETTE_SECTION, "basePalette", "()" + ID};
    static final String[] NEW_FILE_LISTER = {CONSTRUCTOR, FILE_TO_ID, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V"};
    static final String[] LIST_FILES = {VIRTUAL, FILE_TO_ID, "listMatchingResources", "(L" + RESOURCE_MANAGER + ";)Ljava/util/Map;"};
    static final String[] FILE_TO_ID_OF = {VIRTUAL, FILE_TO_ID, "fileToId", "(" + ID + ")" + ID};
    static final String[] PALETTE_FILES = {STATIC_FIELD, PALETTE, "ID_CONVERTER", "L" + FILE_TO_ID + ";"};
    static final String[] LOAD_PALETTE = {STATIC, PALETTE, "load", "(L" + RESOURCE + ";)L" + PALETTE + ";"};
    static final String[] PALETTE_SIZE = {VIRTUAL, PALETTE, "size", "()I"};
    static final String[] TRIM_PATTERN_OF = {VIRTUAL, ARMOR_TRIM, "pattern", "()L" + HOLDER + ";"};
    static final String[] TRIM_MATERIAL_OF = {VIRTUAL, ARMOR_TRIM, "material", "()L" + HOLDER + ";"};
    static final String[] HOLDER_VALUE = {VIRTUAL, HOLDER, "value", "()Ljava/lang/Object;"};
    static final String[] PATTERN_TEXTURE = {VIRTUAL, TRIM_PATTERN, "assetId", "()" + ID};
    static final String[] MATERIAL_PALETTE = {VIRTUAL, TRIM_MATERIAL, "paletteId", "()" + ID};
    static final String[] KEY_ID = {VIRTUAL, RESOURCE_KEY, "identifier", "()" + ID};
    static final String[] EQUIPMENT_CODEC = {STATIC_FIELD, EQUIPMENT_INFO, "CODEC", "L" + CODEC + ";"};
    static final String[] TRIM_OVERRIDES = {VIRTUAL, EQUIPMENT_INFO, "trimOverrides", "()Ljava/util/List;"};
    static final String[] OVERRIDE_PREDICATE = {VIRTUAL, TRIM_OVERRIDE, "predicate", "()L" + TRIM_PREDICATE + ";"};
    static final String[] OVERRIDE_TEXTURE = {VIRTUAL, TRIM_OVERRIDE, "textureId", "()" + OPTIONAL};
    static final String[] OVERRIDE_PALETTE = {VIRTUAL, TRIM_OVERRIDE, "paletteId", "()" + OPTIONAL};
    static final String[] PREDICATE_MATCHES = {VIRTUAL, TRIM_PREDICATE, "matches", "(L" + ARMOR_TRIM + ";)Z"};
    static final String[] PARSE_JSON = {STATIC, JSON_PARSER, "parse", "(Ljava/io/Reader;)L" + JSON_ELEMENT + ";"};
    static final String[] JSON_OPS_INSTANCE = {STATIC_FIELD, JSON_OPS, "INSTANCE", "L" + JSON_OPS + ";"};
    static final String[] DECODE = {VIRTUAL, DECODER, "parse", "(L" + DYNAMIC_OPS + ";Ljava/lang/Object;)L" + DATA_RESULT + ";"};
    static final String[] DECODED = {VIRTUAL, DATA_RESULT, "result", "()" + OPTIONAL};

    /** Every host member this class resolves, so a test can check each against the real client jar. */
    static final String[][] MEMBERS = {
        GET_INSTANCE, GET_TEXTURE_MANAGER, GET_RESOURCE_MANAGER, SCHEDULE, GET_ATLAS_MANAGER, ATLAS_BY_ID,
        BLOCK_ATLAS_ID, MISSING_SPRITE, CLOSE_ATLAS, ON_RENDER_THREAD,
        IDENTIFIER_OF, NAMESPACE, PATH, REGISTER_TEXTURE, NEW_ATLAS, UPLOAD, LOADER_FOR,
        LOAD_AND_STITCH, READY_FOR_UPLOAD, TRIM_RENDER_TYPE, LIST_PACKS, GET_RESOURCE, NEW_RESOURCE,
        METADATA, OPEN_READER, GET_SECTION, PALETTE_SECTION_TYPE, BASE_PALETTE, NEW_FILE_LISTER,
        LIST_FILES, FILE_TO_ID_OF, PALETTE_FILES, LOAD_PALETTE, PALETTE_SIZE, TRIM_PATTERN_OF,
        TRIM_MATERIAL_OF, HOLDER_VALUE, PATTERN_TEXTURE, MATERIAL_PALETTE, KEY_ID, EQUIPMENT_CODEC,
        TRIM_OVERRIDES, OVERRIDE_PREDICATE, OVERRIDE_TEXTURE, OVERRIDE_PALETTE, PREDICATE_MATCHES,
        PARSE_JSON, JSON_OPS_INSTANCE, DECODE, DECODED,
    };

    /** Interface methods this class answers through a proxy, as {interface, name, descriptor}. */
    static final String[][] IMPLEMENTED = {
        {RESOURCE_MANAGER, "getResourceStack", "(" + ID + ")Ljava/util/List;"},
        {IO_SUPPLIER, "get", "()Ljava/lang/Object;"},
    };

    /** Trim textures, which 26.3 recolors from the base palette in their metadata. */
    static final String TRIM_TEXTURES = "textures/trims/entity";
    private static final String VANILLA_TRIM_PALETTES = "trim/";
    /** The wait before a pack check or a retry, so a mod drawing every frame does not repeat them. */
    private static final long PACK_CHECK_NANOS = 500_000_000L;

    // Resolved members, then the caches and atlas state a rebuild resets.
    private static final Map<String[], MethodHandle> HANDLES = new ConcurrentHashMap<>();
    private static final Map<List<Object>, Object> SPRITE_NAMES = new ConcurrentHashMap<>();
    private static final Map<Object, Optional<Object>> EQUIPMENT = new ConcurrentHashMap<>();
    private static final Object[] SHEET_TYPES = new Object[2];
    private static Object sheetId;
    private static Object atlas;
    private static List<?> builtFrom = List.of();
    private static long nextCheck;
    private static boolean rebuildPending;
    private static boolean unavailable;
    private static volatile boolean warned;

    private LegacyArmorTrims() {}

    /** {@code Sheets.ARMOR_TRIMS_SHEET}: this copy's atlas, built first when the client can. */
    public static Object sheet() {
        // Up to 26.2 every trim was a sprite in one atlas, and LegacyArmorTrimBridge still sends the
        // old atlas calls here. 26.3 recolors each trim texture with a palette when first drawn.
        refresh();
        return sheetId();
    }

    /** {@code Sheets.armorTrimsSheet(decal)}: 26.3's trim render type over this copy's atlas. */
    public static Object armorTrimsSheet(boolean decal) {
        refresh();
        // One render type each for plain and decal trims, made once.
        int index = decal ? 1 : 0;
        Object type = SHEET_TYPES[index];
        if (type == null) {
            type = invoke(TRIM_RENDER_TYPE, sheetId(), decal);
            SHEET_TYPES[index] = type;
        }
        return type;
    }

    /** {@code RenderTypes.createArmorDecalCutoutNoCull}: 26.3's decal trim type, the same setup. */
    public static Object armorDecalCutoutNoCull(Object texture) {
        return invoke(TRIM_RENDER_TYPE, texture, true);
    }

    /** {@code ArmorTrim.layerAssetId}: the sprite for {@code trim}, or the missing texture's name. */
    public static Object layerAssetId(Object trim, String layerPrefix, Object equipmentAsset) {
        Objects.requireNonNull(trim, "trim");
        refresh();
        List<Object> key = Arrays.asList(trim, layerPrefix, equipmentAsset);
        Object cached = SPRITE_NAMES.get(key);
        if (cached != null) return cached;
        Object name;
        try {
            name = spriteName(trim, layerPrefix, equipmentAsset);
        } catch (RuntimeException failure) {
            warn("name an armor trim sprite", failure);
            return invoke(IDENTIFIER_OF, "minecraft", "missingno");
        }
        // Keep the cache bounded: start over rather than grow without limit.
        if (SPRITE_NAMES.size() > 1024) SPRITE_NAMES.clear();
        SPRITE_NAMES.put(key, name);
        return name;
    }

    /** The name a vanilla atlas gave a palette's permutation, such as {@code iron} or {@code iron_darker}. */
    static String permutationName(String paletteId) {
        int colon = paletteId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : paletteId.substring(0, colon);
        String path = paletteId.substring(colon + 1);
        // A palette under minecraft:trim/ keeps the suffix the old trim materials had. Any other is
        // named by its namespace and path, which cannot clash with those.
        if (namespace.equals("minecraft") && path.startsWith(VANILLA_TRIM_PALETTES)) {
            return path.substring(VANILLA_TRIM_PALETTES.length());
        }
        return namespace + "." + path.replace('/', '.');
    }

    /** The atlas definition as JSON for 26.3's own atlas builder. Ids are {@code namespace:path}. */
    static String definitionJson(Map<String, List<String>> texturesByBasePalette, Map<String, Integer> paletteSizes) {
        // The old shape, as the removed atlases/armor_trims.json described: one paletted permutation
        // source per base palette, over its trim textures and every other palette of the same size.
        StringBuilder json = new StringBuilder("{\"sources\":[");
        for (Map.Entry<String, List<String>> group : new TreeMap<>(texturesByBasePalette).entrySet()) {
            Integer size = paletteSizes.get(group.getKey());
            Map<String, String> permutations = new TreeMap<>();
            for (Map.Entry<String, Integer> palette : paletteSizes.entrySet()) {
                if (size != null && palette.getValue().equals(size) && !palette.getKey().equals(group.getKey())) {
                    permutations.put(permutationName(palette.getKey()), palette.getKey());
                }
            }
            if (permutations.isEmpty()) continue;
            // Sprites keep the old names, <trim texture>_<palette>, so a mod's lookups resolve as before.
            json.append("{\"type\":\"minecraft:paletted_permutations\",\"textures\":[");
            String separator = "";
            for (String texture : new TreeSet<>(group.getValue())) {
                json.append(separator).append(quote(texture));
                separator = ",";
            }
            json.append("],\"palette_key\":").append(quote(group.getKey())).append(",\"permutations\":{");
            separator = "";
            for (Map.Entry<String, String> permutation : permutations.entrySet()) {
                json.append(separator).append(quote(permutation.getKey())).append(':').append(quote(permutation.getValue()));
                separator = ",";
            }
            json.append("}},");
        }
        // Every trim texture unrecolored too, for a trim override that takes the palette away.
        String root = TRIM_TEXTURES.substring("textures/".length());
        json.append("{\"type\":\"minecraft:directory\",\"source\":").append(quote(root))
                .append(",\"prefix\":").append(quote(root + "/")).append("}]}");
        return json.toString();
    }

    /** The texture, then the palette, 26.3 draws {@code trim} with on the equipment asset. */
    private static Object spriteName(Object trim, String layerPrefix, Object equipmentAsset) {
        Object texture = invoke(PATTERN_TEXTURE, invoke(HOLDER_VALUE, invoke(TRIM_PATTERN_OF, trim)));
        Object palette = invoke(MATERIAL_PALETTE, invoke(HOLDER_VALUE, invoke(TRIM_MATERIAL_OF, trim)));
        // The equipment asset's trim overrides replaced the old _darker material variants.
        Object override = null;
        try {
            override = trimOverride(trim, equipmentAsset);
        } catch (RuntimeException failure) {
            // The material's own palette still fits.
            warn("apply an equipment asset's trim overrides", failure);
        }
        if (override != null) {
            texture = ((Optional<?>) invoke(OVERRIDE_TEXTURE, override)).map(Object.class::cast).orElse(texture);
            palette = ((Optional<?>) invoke(OVERRIDE_PALETTE, override)).orElse(null);
        }
        String path = layerPrefix + "/" + invoke(PATH, texture);
        if (palette != null) path += "_" + permutationName(String.valueOf(palette));
        return invoke(IDENTIFIER_OF, invoke(NAMESPACE, texture), path);
    }

    /** The first trim override of the equipment asset that matches {@code trim}, or null. */
    private static Object trimOverride(Object trim, Object equipmentAsset) {
        if (equipmentAsset == null) return null;
        Optional<Object> info = EQUIPMENT.computeIfAbsent(invoke(KEY_ID, equipmentAsset), LegacyArmorTrims::equipmentInfo);
        if (info.isEmpty()) return null;
        for (Object override : (List<?>) invoke(TRIM_OVERRIDES, info.get())) {
            if ((boolean) invoke(PREDICATE_MATCHES, invoke(OVERRIDE_PREDICATE, override), trim)) return override;
        }
        return null;
    }

    /** The client asset {@code equipment/<id>.json}, read with the host's own codec. */
    private static Optional<Object> equipmentInfo(Object assetId) {
        try {
            Object resources = invoke(GET_RESOURCE_MANAGER, invoke(GET_INSTANCE));
            Object file = invoke(IDENTIFIER_OF, invoke(NAMESPACE, assetId), "equipment/" + invoke(PATH, assetId) + ".json");
            Optional<?> resource = (Optional<?>) invoke(GET_RESOURCE, resources, file);
            if (resource.isEmpty()) return Optional.empty();
            try (Reader reader = (Reader) invoke(OPEN_READER, resource.get())) {
                Object decoded = invoke(DECODE, invoke(EQUIPMENT_CODEC), invoke(JSON_OPS_INSTANCE), invoke(PARSE_JSON, reader));
                return ((Optional<?>) invoke(DECODED, decoded)).map(Object.class::cast);
            }
        } catch (Exception failure) {
            warn("read an equipment asset's trim overrides", failure);
            return Optional.empty();
        }
    }

    /** Builds the atlas the first time the client can, and rebuilds it once the resource packs change. */
    private static synchronized void refresh() {
        long now = System.nanoTime();
        if (unavailable || now - nextCheck < 0) return;
        try {
            Object minecraft;
            try {
                minecraft = invoke(GET_INSTANCE);
            } catch (IllegalStateException noClient) {
                // A dedicated server: there is nothing to draw.
                unavailable = true;
                return;
            }
            // Textures are only touched on the render thread, so anywhere else this does nothing.
            if (minecraft == null || !(boolean) invoke(ON_RENDER_THREAD)) return;
            Object textures = invoke(GET_TEXTURE_MANAGER, minecraft);
            Object resources = invoke(GET_RESOURCE_MANAGER, minecraft);
            if (textures == null || resources == null) return;
            if (atlas == null) {
                if (hostAtlasesLoaded(minecraft)) createAtlas(textures, resources);
                return;
            }
            // Rebuild once the resource packs change, checking at most every PACK_CHECK_NANOS.
            if (rebuildPending) return;
            nextCheck = now + PACK_CHECK_NANOS;
            List<?> packs = packs(resources);
            if (!samePacks(packs, builtFrom)) scheduleRebuild(minecraft, resources, packs);
        } catch (RuntimeException failure) {
            nextCheck = now + PACK_CHECK_NANOS;
            warn("build the armor trim atlas", failure);
        }
    }

    /** Whether the host has uploaded its own block atlas, which is when this atlas can upload too. */
    private static boolean hostAtlasesLoaded(Object minecraft) {
        Object atlases = invoke(GET_ATLAS_MANAGER, minecraft);
        if (atlases == null) return false;
        // The host's shaders load before its atlases, and an atlas upload draws every sprite with
        // one of them, so before that point an upload could not work.
        try {
            invoke(MISSING_SPRITE, invoke(ATLAS_BY_ID, atlases, invoke(BLOCK_ATLAS_ID)));
            return true;
        } catch (RuntimeException notUploaded) {
            return false;
        }
    }

    /** Stitches the atlas and registers it with the texture manager, where the mod looks for it. */
    private static void createAtlas(Object textures, Object resources) {
        Object created = invoke(NEW_ATLAS, sheetId());
        List<?> packs = packs(resources);
        try {
            Object preparations;
            try {
                preparations = stitch(created, resources, definition(resources)).join();
            } catch (RuntimeException failure) {
                // The trim textures cannot be read, but an atlas holding only the missing texture
                // still lets the mod start.
                warn("build the armor trim atlas from the trim textures", failure);
                preparations = stitch(created, resources, "{\"sources\":[]}").join();
            }
            invoke(UPLOAD, created, preparations);
        } catch (RuntimeException failure) {
            // A failed upload closes the half-built atlas.
            invoke(CLOSE_ATLAS, created);
            throw failure;
        }
        invoke(REGISTER_TEXTURE, textures, sheetId(), created);
        atlas = created;
        builtFrom = packs;
        nextCheck = System.nanoTime() + PACK_CHECK_NANOS;
    }

    /** Stitches off the render thread and uploads at a later frame's start, as the host does. */
    private static void scheduleRebuild(Object minecraft, Object resources, List<?> packs) {
        rebuildPending = true;
        Object target = atlas;
        CompletableFuture.supplyAsync(() -> definition(resources), ForkJoinPool.commonPool())
                .thenCompose(definition -> stitch(target, resources, definition))
                .whenComplete((preparations, failure) -> {
                    // Upload from the client's task queue, so a reload never redraws the atlas in the
                    // middle of a frame.
                    try {
                        invoke(SCHEDULE, minecraft, (Runnable) () -> finishRebuild(target, preparations, failure, packs));
                    } catch (RuntimeException notScheduled) {
                        // If the task cannot be scheduled, finish now so the pending flag clears.
                        finishRebuild(target, null, notScheduled, packs);
                    }
                });
    }

    /** Uploads a finished rebuild, or warns about the failure that stopped it. */
    private static synchronized void finishRebuild(Object target, Object preparations, Throwable failure, List<?> packs) {
        rebuildPending = false;
        builtFrom = packs;
        // Sprite names and equipment assets may have changed with the packs.
        SPRITE_NAMES.clear();
        EQUIPMENT.clear();
        if (failure != null) {
            warn("rebuild the armor trim atlas after a resource reload", failure);
            return;
        }
        try {
            invoke(UPLOAD, target, preparations);
        } catch (RuntimeException uploadFailure) {
            warn("rebuild the armor trim atlas after a resource reload", uploadFailure);
        }
    }

    /** The host's atlas builder run on {@code definition}; completes once the sprites can be uploaded. */
    private static CompletableFuture<Object> stitch(Object target, Object resources, String definition) {
        Object loader = invoke(LOADER_FOR, target);
        CompletableFuture<?> stitched = (CompletableFuture<?>) invoke(LOAD_AND_STITCH, loader,
                withDefinition(resources, definition), definitionId(), 0, (Executor) ForkJoinPool.commonPool(), Set.of());
        return stitched.thenCompose(preparations -> ((CompletableFuture<?>) invoke(READY_FOR_UPLOAD, preparations))
                .thenApply(ready -> (Object) preparations));
    }

    /** Lists the trim textures by base palette and the palettes by size, then writes the definition. */
    private static String definition(Object resources) {
        // Every palette, by id, with its size.
        Map<String, Integer> paletteSizes = new TreeMap<>();
        Object paletteFiles = invoke(PALETTE_FILES);
        for (Map.Entry<?, ?> file : ((Map<?, ?>) invoke(LIST_FILES, paletteFiles, resources)).entrySet()) {
            try {
                int size = (int) invoke(PALETTE_SIZE, invoke(LOAD_PALETTE, file.getValue()));
                paletteSizes.put(String.valueOf(invoke(FILE_TO_ID_OF, paletteFiles, file.getKey())), size);
            } catch (RuntimeException unreadable) {
                // The host's own palette loader skips it too.
            }
        }
        // Every trim texture whose metadata names a base palette, grouped by that palette.
        Map<String, List<String>> texturesByBasePalette = new TreeMap<>();
        Object sectionType = invoke(PALETTE_SECTION_TYPE);
        Object trimFiles = invoke(NEW_FILE_LISTER, TRIM_TEXTURES, ".png");
        for (Map.Entry<?, ?> file : ((Map<?, ?>) invoke(LIST_FILES, trimFiles, resources)).entrySet()) {
            Optional<?> section;
            try {
                section = (Optional<?>) invoke(GET_SECTION, invoke(METADATA, file.getValue()), sectionType);
            } catch (RuntimeException unreadable) {
                continue;
            }
            if (section.isEmpty()) continue;
            String path = (String) invoke(PATH, file.getKey());
            String texture = invoke(NAMESPACE, file.getKey()) + ":"
                    + path.substring("textures/".length(), path.length() - ".png".length());
            texturesByBasePalette.computeIfAbsent(String.valueOf(invoke(BASE_PALETTE, section.get())),
                    ignored -> new ArrayList<>()).add(texture);
        }
        return definitionJson(texturesByBasePalette, paletteSizes);
    }

    /** {@code resources}, plus this copy's atlas definition, which no resource pack provides. */
    private static Object withDefinition(Object resources, String definition) {
        ClassLoader loader = LegacyArmorTrims.class.getClassLoader();
        // The definition file's contents, served from memory as a resource of the first pack.
        byte[] bytes = definition.getBytes(StandardCharsets.UTF_8);
        Object contents = Proxy.newProxyInstance(loader, new Class<?>[] {type(IO_SUPPLIER)}, (proxy, method, args) ->
                method.getName().equals("get") && method.getParameterCount() == 0
                        ? new ByteArrayInputStream(bytes) : objectMethod(proxy, method, args));
        Object pack = ((Stream<?>) invoke(LIST_PACKS, resources)).findFirst().orElse(null);
        List<Object> stack = List.of(invoke(NEW_RESOURCE, pack, contents));
        // A resource manager that answers that one file and passes every other call through.
        Object file = definitionFile();
        return Proxy.newProxyInstance(loader, new Class<?>[] {type(RESOURCE_MANAGER)}, (proxy, method, args) -> {
            if (method.getName().equals("getResourceStack") && args != null && args.length == 1 && file.equals(args[0])) {
                return stack;
            }
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            try {
                return method.invoke(resources, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        });
    }

    /** Answers the {@code Object} methods a proxy receives. */
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "Retromod armor trim atlas definition";
            default:
                throw new UnsupportedOperationException(method.getName());
        }
    }

    private static List<?> packs(Object resources) {
        return ((Stream<?>) invoke(LIST_PACKS, resources)).toList();
    }

    /** A reload opens every pack again, so the same pack objects mean the same resources. */
    private static boolean samePacks(List<?> current, List<?> built) {
        if (current.size() != built.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i) != built.get(i)) return false;
        }
        return true;
    }

    /** This copy's atlas id. */
    private static Object sheetId() {
        Object id = sheetId;
        if (id == null) {
            // Retromod's own texture, which 26.3 never reads. Each embedded copy uses its own id, so
            // one mod never closes another's atlas.
            id = invoke(IDENTIFIER_OF, "retromod", "textures/atlas/legacy_armor_trims/" + copyName() + ".png");
            sheetId = id;
        }
        return id;
    }

    /** The id the host's atlas builder loads this copy's definition by. */
    private static Object definitionId() {
        return invoke(IDENTIFIER_OF, "retromod", "legacy_armor_trims/" + copyName());
    }

    /** Where the host's atlas builder looks for {@link #definitionId()}. */
    private static Object definitionFile() {
        return invoke(IDENTIFIER_OF, "retromod", "atlases/legacy_armor_trims/" + copyName() + ".json");
    }

    /** The per-mod segment of this copy's package, or {@code shared} when it is not embedded. */
    static String copyName() {
        String name = LegacyArmorTrims.class.getPackageName().toLowerCase(Locale.ROOT);
        // The embedder moves a helper to com.retromod.embedded.<mod key>.<original package>, and the
        // key is unique per mod.
        String relocated = "com.retromod.embedded.";
        if (!name.startsWith(relocated)) return "shared";
        name = name.substring(relocated.length());
        int end = name.indexOf('.');
        return (end < 0 ? name : name.substring(0, end)).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Calls a host member, resolving it the first time. */
    private static Object invoke(String[] member, Object... args) {
        MethodHandle handle = HANDLES.get(member);
        try {
            if (handle == null) {
                handle = resolve(member);
                HANDLES.put(member, handle);
            }
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(member[1] + "." + member[2] + member[3] + " failed", failure);
        }
    }

    private static MethodHandle resolve(String[] member) throws ReflectiveOperationException {
        ClassLoader loader = LegacyArmorTrims.class.getClassLoader();
        Class<?> owner = type(member[1]);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        switch (member[0]) {
            case STATIC:
                return lookup.findStatic(owner, member[2], MethodType.fromMethodDescriptorString(member[3], loader));
            case VIRTUAL:
                return lookup.findVirtual(owner, member[2], MethodType.fromMethodDescriptorString(member[3], loader));
            case CONSTRUCTOR:
                return lookup.findConstructor(owner, MethodType.fromMethodDescriptorString(member[3], loader));
            case STATIC_FIELD:
                return lookup.findStaticGetter(owner, member[2], type(member[3].substring(1, member[3].length() - 1)));
            default:
                throw new IllegalArgumentException("Unknown member kind " + member[0]);
        }
    }

    private static Class<?> type(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, LegacyArmorTrims.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(internalName + " is not on this host", missing);
        }
    }

    /** Warns once that an old mod's armor trims may draw as the missing texture. */
    private static void warn(String what, Throwable failure) {
        if (warned) return;
        warned = true;
        // Unwrap what invoke and the futures add, to show the real cause.
        Throwable cause = failure;
        while ((cause instanceof IllegalStateException || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        System.err.println("[Retromod] Could not " + what + " for an old mod, so its armor trims may draw as "
                + "the missing texture: " + cause);
    }
}
