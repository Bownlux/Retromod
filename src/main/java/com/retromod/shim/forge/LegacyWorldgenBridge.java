/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Restores worldgen that a pre-1.19.3 Forge mod registers in code.
 *
 * <p>Through 1.19.2 a mod called {@code FeatureUtils.register(id, feature, config)} and
 * {@code PlacementUtils.register(id, configured, modifiers)}, and built biomes with
 * {@code new BiomeGenerationSettings.Builder()}. 1.19.3 made configured features, placed features
 * and biomes data pack registries: the register helpers became bootstrap-only, the builder needs
 * registry lookups, and every vanilla worldgen constant became a {@code ResourceKey}. An MCreator
 * mod of that era dies registering its first feature (#264, #290), and if that call merely
 * returned a holder, the mod's own {@code forge/biome_modifier} files would still name features
 * that no registry contains, which stops the world from loading.
 *
 * <p>The generated {@code LegacyWorldgen} class answers the old calls. Each registration builds
 * the real object, hands back a stand-alone holder carrying the mod's id, and records the object in
 * {@code LegacyWorldgenStore}. When the server asks for data packs, the store writes every recorded
 * feature, plus the mod's code-built biomes, through Minecraft's own codecs into a generated pack.
 * Holders serialize as their ids, so a placed feature points at its configured feature, and a biome
 * points at vanilla features such as {@code minecraft:patch_grass} by key.
 *
 * <p>Two limits remain. The pack is offered through Forge's {@code AddPackFindersEvent}, so the
 * restore is Forge only; on other loaders the calls still succeed and the features are simply
 * absent. A vanilla constant read through the converted field is an unbound reference, so it works
 * inside worldgen definitions but a mod that calls {@code value()} on it directly still fails.
 * The pack source and the overworld splice link version-specific APIs, so each is generated only
 * on the hosts {@link #packApiLinks} and {@link #overworldInjectionLinks} accept.
 */
public final class LegacyWorldgenBridge {

    static final String GENERATED = "com/retromod/generated/LegacyWorldgen";
    static final String OWNER = "com/retromod/generated/LegacyWorldgenOwner";
    static final String PACK_SOURCE = "com/retromod/generated/LegacyWorldgenPackSource";
    static final String BIOME_BUILDER_BRIDGE = "com/retromod/generated/LegacyBiomeBuilder";
    static final String STORE = "com/retromod/shim/forge/embedded/LegacyWorldgenStore";
    static final String CODECS = STORE + "$Codecs";

    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String REFERENCE = "net/minecraft/core/Holder$Reference";
    private static final String HOLDER_OWNER = "net/minecraft/core/HolderOwner";
    private static final String HOLDER_GETTER = "net/minecraft/core/HolderGetter";
    private static final String INFO_LOOKUP = "net/minecraft/resources/RegistryOps$RegistryInfoLookup";
    private static final String REGISTRY_INFO = "net/minecraft/resources/RegistryOps$RegistryInfo";
    private static final String REGISTRY_OPS = "net/minecraft/resources/RegistryOps";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String FEATURE_UTILS = "net/minecraft/data/worldgen/features/FeatureUtils";
    private static final String PLACEMENT_UTILS = "net/minecraft/data/worldgen/placement/PlacementUtils";
    private static final String CONFIGURED = "net/minecraft/world/level/levelgen/feature/ConfiguredFeature";
    private static final String FEATURE = "net/minecraft/world/level/levelgen/feature/Feature";
    private static final String CONFIG =
            "net/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration";
    private static final String NONE_CONFIG =
            "net/minecraft/world/level/levelgen/feature/configurations/NoneFeatureConfiguration";
    private static final String PLACED = "net/minecraft/world/level/levelgen/placement/PlacedFeature";
    private static final String AQUATIC_PLACEMENTS = "net/minecraft/data/worldgen/placement/AquaticPlacements";
    private static final String PLACEMENT = "net/minecraft/world/level/levelgen/placement/";
    private static final String MODIFIER = "net/minecraft/world/level/levelgen/placement/PlacementModifier";
    private static final String BIOME = "net/minecraft/world/level/biome/Biome";
    private static final String BIOME_BUILDER = "net/minecraft/world/level/biome/Biome$BiomeBuilder";
    private static final String PRECIPITATION = "net/minecraft/world/level/biome/Biome$Precipitation";
    private static final String GEN_BUILDER = "net/minecraft/world/level/biome/BiomeGenerationSettings$Builder";
    private static final String PLAIN_BUILDER =
            "net/minecraft/world/level/biome/BiomeGenerationSettings$PlainBuilder";
    private static final String DECORATION = "net/minecraft/world/level/levelgen/GenerationStep$Decoration";
    private static final String CARVING = "net/minecraft/world/level/levelgen/GenerationStep$Carving";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String DYNAMIC_OPS = "com/mojang/serialization/DynamicOps";
    private static final String JSON_OPS = "com/mojang/serialization/JsonOps";
    private static final String DATA_RESULT = "com/mojang/serialization/DataResult";
    private static final String LIFECYCLE = "com/mojang/serialization/Lifecycle";
    private static final String REPOSITORY_SOURCE = "net/minecraft/server/packs/repository/RepositorySource";
    private static final String PACK = "net/minecraft/server/packs/repository/Pack";
    private static final String RESOURCES_SUPPLIER = "net/minecraft/server/packs/repository/Pack$ResourcesSupplier";
    private static final String PACK_POSITION = "net/minecraft/server/packs/repository/Pack$Position";
    private static final String PACK_SOURCE_TYPE = "net/minecraft/server/packs/repository/PackSource";
    private static final String PACK_RESOURCES = "net/minecraft/server/packs/PackResources";
    private static final String PATH_RESOURCES = "net/minecraft/server/packs/PathPackResources";
    private static final String PACK_TYPE = "net/minecraft/server/packs/PackType";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String MUTABLE_COMPONENT = "net/minecraft/network/chat/MutableComponent";
    private static final String SHARED_CONSTANTS = "net/minecraft/SharedConstants";
    private static final String WORLD_DATA = "net/minecraft/world/level/storage/WorldData";
    private static final String WORLD_GEN_SETTINGS = "net/minecraft/world/level/levelgen/WorldGenSettings";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String LEVEL_STEM = "net/minecraft/world/level/dimension/LevelStem";
    private static final String MULTI_NOISE = "net/minecraft/world/level/biome/MultiNoiseBiomeSource";
    private static final String PARAMETER_LIST = "net/minecraft/world/level/biome/Climate$ParameterList";
    private static final String MINECRAFT_SERVER = "net/minecraft/server/MinecraftServer";
    private static final String REGISTRY_ACCESS = "net/minecraft/core/RegistryAccess";
    private static final String FROZEN_ACCESS = "net/minecraft/core/RegistryAccess$Frozen";
    private static final String SERVER_HOOKS = "net/minecraftforge/server/ServerLifecycleHooks";
    private static final String NEOFORGE_SERVER_HOOKS = "net/neoforged/neoforge/server/ServerLifecycleHooks";
    private static final String WORLD_VERSION = "net/minecraft/WorldVersion";

    private static final String L_HOLDER = "L" + HOLDER + ";";
    private static final String L_KEY = "L" + RESOURCE_KEY + ";";
    private static final String L_STRING = "Ljava/lang/String;";
    private static final String L_OBJECT = "Ljava/lang/Object;";
    private static final String L_OPTIONAL = "Ljava/util/Optional;";
    private static final String L_LIST = "Ljava/util/List;";
    private static final String L_GEN_BUILDER = "L" + GEN_BUILDER + ";";
    private static final String L_OWNER = "L" + OWNER + ";";

    static final String REGISTER_CONFIGURED_DESC = "(" + L_STRING + "L" + FEATURE + ";L" + CONFIG + ";)" + L_HOLDER;
    static final String REGISTER_CONFIGURED_NONE_DESC = "(" + L_STRING + "L" + FEATURE + ";)" + L_HOLDER;
    static final String REGISTER_PLACED_DESC = "(" + L_STRING + L_HOLDER + L_LIST + ")" + L_HOLDER;
    static final String REGISTER_PLACED_ARRAY_DESC = "(" + L_STRING + L_HOLDER + "[L" + MODIFIER + ";)" + L_HOLDER;
    static final String KEY_HOLDER_DESC = "(" + L_KEY + ")" + L_HOLDER;

    private LegacyWorldgenBridge() {}

    /** The 1.19.2 to 1.19.3 worldgen bridge: feature registration, biome builders, constants. */
    public static void register(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, STORE, LegacyWorldgenBridge.class);
        SyntheticEmbedder.registerClassResource(transformer, CODECS, LegacyWorldgenBridge.class);
        transformer.registerSyntheticClass(GENERATED, generateWorldgen());
        transformer.registerSyntheticClass(OWNER, generateOwner());
        if (packApiLinks(RetromodVersion.TARGET_MC_VERSION)) {
            transformer.registerSyntheticClass(PACK_SOURCE, generatePackSource());
        }

        transformer.registerMethodRedirect(FEATURE_UTILS, "register", REGISTER_CONFIGURED_DESC,
                GENERATED, "registerConfigured", REGISTER_CONFIGURED_DESC);
        transformer.registerMethodRedirect(FEATURE_UTILS, "register", REGISTER_CONFIGURED_NONE_DESC,
                GENERATED, "registerConfiguredNone", REGISTER_CONFIGURED_NONE_DESC);
        transformer.registerMethodRedirect(PLACEMENT_UTILS, "register", REGISTER_PLACED_DESC,
                GENERATED, "registerPlaced", REGISTER_PLACED_DESC);
        transformer.registerMethodRedirect(PLACEMENT_UTILS, "register", REGISTER_PLACED_ARRAY_DESC,
                GENERATED, "registerPlacedArray", REGISTER_PLACED_ARRAY_DESC);

        transformer.registerConstructorRedirect(GEN_BUILDER, "()V",
                GENERATED, "newGenerationBuilder", "()" + L_GEN_BUILDER);
        // The old builder's adders returned the Builder; 1.19.3 moved them to PlainBuilder, which
        // returns PlainBuilder, so the mod's chained call no longer links.
        transformer.registerMethodRedirect(GEN_BUILDER, "addFeature",
                "(L" + DECORATION + ";" + L_HOLDER + ")" + L_GEN_BUILDER,
                GENERATED, "addFeature", "(" + L_GEN_BUILDER + "L" + DECORATION + ";" + L_HOLDER + ")" + L_GEN_BUILDER,
                true);
        transformer.registerMethodRedirect(GEN_BUILDER, "addFeature",
                "(I" + L_HOLDER + ")" + L_GEN_BUILDER,
                GENERATED, "addFeatureAt", "(" + L_GEN_BUILDER + "I" + L_HOLDER + ")" + L_GEN_BUILDER,
                true);
        transformer.registerMethodRedirect(GEN_BUILDER, "addCarver",
                "(L" + CARVING + ";" + L_HOLDER + ")" + L_GEN_BUILDER,
                GENERATED, "addCarver", "(" + L_GEN_BUILDER + "L" + CARVING + ";" + L_HOLDER + ")" + L_GEN_BUILDER,
                true);

        // 1.19.3 made this vanilla helper private; it is rebuilt from the same four modifiers.
        // The method still exists, so an SRG host keeps its SRG name through the remap. Shims
        // register before the target SRG table loads, so that name is spelled out here; SRG ids
        // do not change while a method keeps its signature.
        String seagrass = "(I)" + L_LIST;
        for (String name : new String[]{"seagrassPlacement", "m_195233_"}) {
            transformer.registerMethodRedirect(AQUATIC_PLACEMENTS, name, seagrass,
                    GENERATED, "seagrassPlacement", seagrass);
        }

        // VegetationFeatures.PATCH_GRASS, TreePlacements.OAK_CHECKED, Carvers.CAVE, and the rest.
        transformer.registerStaticFieldTypeDrift("net/minecraft/data/worldgen/", L_HOLDER, L_KEY,
                GENERATED, "keyHolder", KEY_HOLDER_DESC);

        if (overworldInjectionLinks(RetromodVersion.TARGET_MC_VERSION)) {
            registerOverworldBiomeInjection(transformer);
        }
    }

    /**
     * Whether the host has the pack API the generated pack source calls: the seven-argument
     * {@code Pack.readMetaAndCreate}, the single-method {@code Pack.ResourcesSupplier} and
     * {@code PathPackResources(String, Path, boolean)}. Verified on 1.20.1; newer hosts reshaped
     * the supplier and the pack metadata, so there the code-registered worldgen is recorded but
     * not offered as a pack.
     */
    static boolean packApiLinks(String host) {
        return RetromodVersion.compareMcVersions(host, "1.20.1") <= 0;
    }

    /**
     * Whether the host still has {@code RegistryAccess.registryOrThrow} and
     * {@code Registry.getHolderOrThrow}, which the overworld splice redirects to. 1.21.2 renamed
     * both, so a newer host keeps the mod's original calls rather than linking missing ones.
     */
    static boolean overworldInjectionLinks(String host) {
        return RetromodVersion.compareMcVersions(host, "1.21.2") < 0;
    }

    /**
     * The calls an MCreator mod makes on {@code ServerAboutToStartEvent} to splice its biomes into
     * the overworld's multi-noise source. 1.19.3 removed {@code WorldData.worldGenSettings()}, made
     * {@code LevelStem} a record, and 1.19.4 hid the multi-noise source's parameter list behind an
     * {@code Either} and a factory. The handler runs on the server thread before the levels exist,
     * so a failure there stops the server (#264).
     */
    private static void registerOverworldBiomeInjection(RetromodTransformer transformer) {
        transformer.registerMethodRedirect(WORLD_DATA, "worldGenSettings", "()L" + WORLD_GEN_SETTINGS + ";",
                GENERATED, "worldGenSettings", "(L" + WORLD_DATA + ";)L" + WORLD_GEN_SETTINGS + ";", true);
        transformer.registerMethodRedirect(WORLD_GEN_SETTINGS, "dimensions", "()L" + REGISTRY + ";",
                GENERATED, "dimensions", "(L" + WORLD_GEN_SETTINGS + ";)L" + REGISTRY + ";", true);
        transformer.registerMethodRedirect(LEVEL_STEM, "typeHolder", "()" + L_HOLDER,
                LEVEL_STEM, "type", "()" + L_HOLDER);
        transformer.registerMethodRedirect(REGISTRY, "getOrCreateHolderOrThrow", "(" + L_KEY + ")" + L_HOLDER,
                REGISTRY, "getHolderOrThrow", "(" + L_KEY + ")L" + REFERENCE + ";");
        transformer.registerConstructorRedirect(MULTI_NOISE, "(L" + PARAMETER_LIST + ";" + L_OPTIONAL + ")V",
                GENERATED, "newMultiNoiseSource",
                "(L" + PARAMETER_LIST + ";" + L_OPTIONAL + ")L" + MULTI_NOISE + ";");
        // The fields are read straight off the source: the list is now private behind an Either,
        // and the preset is gone. A write could never have been meaningful, so it is dropped.
        transformer.registerFieldStaticBridge(MULTI_NOISE, "parameters", STORE,
                "biomeSourceParameters", "(" + L_OBJECT + ")" + L_OBJECT,
                "ignoreWrite", "(" + L_OBJECT + L_OBJECT + ")V");
        transformer.registerFieldStaticBridge(MULTI_NOISE, "preset", STORE,
                "noPreset", "(" + L_OBJECT + ")" + L_OPTIONAL,
                "ignoreWrite", "(" + L_OBJECT + L_OBJECT + ")V");
    }

    /** 1.19.4 replaced {@code BiomeBuilder.precipitation(Precipitation)} with a boolean. */
    public static void registerPrecipitationBridge(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(BIOME_BUILDER_BRIDGE, generateBiomeBuilderBridge());
        transformer.registerMethodRedirect(BIOME_BUILDER, "precipitation",
                "(L" + PRECIPITATION + ";)L" + BIOME_BUILDER + ";",
                BIOME_BUILDER_BRIDGE, "precipitation",
                "(L" + BIOME_BUILDER + ";L" + PRECIPITATION + ";)L" + BIOME_BUILDER + ";", true);
    }

    private static ClassWriter newWriter() {
        // No Minecraft class is on Retromod's classpath, so frame merging must not load one.
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    private static void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ---- LegacyWorldgen ---------------------------------------------------------------------

    static byte[] generateWorldgen() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, GENERATED, null, "java/lang/Object", new String[]{CODECS});
        cw.visitField(ACC_STATIC | ACC_FINAL, "OWNER", L_OWNER, null, null).visitEnd();
        emitDefaultConstructor(cw);

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, OWNER);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, OWNER, "<init>", "()V", false);
        mv.visitFieldInsn(PUTSTATIC, GENERATED, "OWNER", L_OWNER);
        mv.visitTypeInsn(NEW, GENERATED);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, GENERATED, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESTATIC, STORE, "install", "(L" + CODECS + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        emitRegisterConfigured(cw);
        emitRegisterConfiguredNone(cw);
        emitRegisterPlaced(cw);
        emitRegisterPlacedArray(cw);
        emitReference(cw);
        emitKeyHolder(cw);
        emitSeagrassPlacement(cw);
        emitNewGenerationBuilder(cw);
        emitBuilderAdder(cw, "addFeature", "L" + DECORATION + ";", ALOAD);
        emitBuilderAdder(cw, "addFeatureAt", "I", ILOAD);
        emitBuilderAdder(cw, "addCarver", "L" + CARVING + ";", ALOAD);
        emitEncoder(cw, "encodeConfigured", CONFIGURED, "configured feature");
        emitEncoder(cw, "encodePlaced", PLACED, "placed feature");
        emitEncoder(cw, "encodeBiome", BIOME, "biome");
        emitEncode(cw);
        emitPackFormat(cw);
        emitRepositorySource(cw, packApiLinks(RetromodVersion.TARGET_MC_VERSION));
        if (overworldInjectionLinks(RetromodVersion.TARGET_MC_VERSION)) {
            emitWorldGenSettings(cw);
            emitDimensions(cw);
            emitNewMultiNoiseSource(cw);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code registerConfigured(id, feature, config)}: build the configured feature, then record it. */
    private static void emitRegisterConfigured(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerConfigured",
                REGISTER_CONFIGURED_DESC, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, REGISTRIES, "CONFIGURED_FEATURE", L_KEY);
        mv.visitLdcInsn("configured_feature");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, CONFIGURED);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, CONFIGURED, "<init>",
                "(L" + FEATURE + ";L" + CONFIG + ";)V", false);
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "reference",
                "(" + L_KEY + L_STRING + L_STRING + L_OBJECT + ")" + L_HOLDER, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitRegisterConfiguredNone(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerConfiguredNone",
                REGISTER_CONFIGURED_NONE_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        // FeatureConfiguration is an interface, but a field read needs no interface flag.
        mv.visitFieldInsn(GETSTATIC, CONFIG, "NONE", "L" + NONE_CONFIG + ";");
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "registerConfigured", REGISTER_CONFIGURED_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitRegisterPlaced(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerPlaced",
                REGISTER_PLACED_DESC, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, REGISTRIES, "PLACED_FEATURE", L_KEY);
        mv.visitLdcInsn("placed_feature");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, PLACED);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "copyOf",
                "(Ljava/util/Collection;)" + L_LIST, true);
        mv.visitMethodInsn(INVOKESPECIAL, PLACED, "<init>", "(" + L_HOLDER + L_LIST + ")V", false);
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "reference",
                "(" + L_KEY + L_STRING + L_STRING + L_OBJECT + ")" + L_HOLDER, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitRegisterPlacedArray(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerPlacedArray",
                REGISTER_PLACED_ARRAY_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "([Ljava/lang/Object;)" + L_LIST, true);
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "registerPlaced", REGISTER_PLACED_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code reference(registry, folder, id, value)}: record the value, then return a stand-alone
     * holder with the mod's id so codecs write the id instead of inlining the value. An id the
     * game cannot parse gets a plain direct holder: still usable by the mod, just not restorable.
     */
    private static void emitReference(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "reference",
                "(" + L_KEY + L_STRING + L_STRING + L_OBJECT + ")" + L_HOLDER, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, STORE, "record", "(" + L_STRING + L_STRING + L_OBJECT + ")V", false);

        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, RESOURCE_LOCATION, "tryParse",
                "(" + L_STRING + ")L" + RESOURCE_LOCATION + ";", false);
        mv.visitVarInsn(ASTORE, 4);
        Label parsed = new Label();
        mv.visitVarInsn(ALOAD, 4);
        mv.visitJumpInsn(IFNONNULL, parsed);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, HOLDER, "direct", "(" + L_OBJECT + ")" + L_HOLDER, true);
        mv.visitInsn(ARETURN);

        mv.visitLabel(parsed);
        mv.visitFieldInsn(GETSTATIC, GENERATED, "OWNER", L_OWNER);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKESTATIC, RESOURCE_KEY, "create",
                "(" + L_KEY + "L" + RESOURCE_LOCATION + ";)" + L_KEY, false);
        mv.visitMethodInsn(INVOKESTATIC, REFERENCE, "createStandAlone",
                "(L" + HOLDER_OWNER + ";" + L_KEY + ")L" + REFERENCE + ";", false);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, STORE, "bindValue", "(" + L_OBJECT + L_OBJECT + ")V", false);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code keyHolder(key)}: the converter for a vanilla constant that is now a key. */
    private static void emitKeyHolder(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "keyHolder", KEY_HOLDER_DESC, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, GENERATED, "OWNER", L_OWNER);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, REFERENCE, "createStandAlone",
                "(L" + HOLDER_OWNER + ";" + L_KEY + ")L" + REFERENCE + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The 1.19.2 body of {@code AquaticPlacements.seagrassPlacement(count)}. */
    private static void emitSeagrassPlacement(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "seagrassPlacement", "(I)" + L_LIST, null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, PLACEMENT + "InSquarePlacement", "spread",
                "()L" + PLACEMENT + "InSquarePlacement;", false);
        mv.visitFieldInsn(GETSTATIC, PLACEMENT_UTILS, "HEIGHTMAP_TOP_SOLID", "L" + MODIFIER + ";");
        mv.visitVarInsn(ILOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, PLACEMENT + "CountPlacement", "of",
                "(I)L" + PLACEMENT + "CountPlacement;", false);
        mv.visitMethodInsn(INVOKESTATIC, PLACEMENT + "BiomeFilter", "biome",
                "()L" + PLACEMENT + "BiomeFilter;", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of",
                "(" + L_OBJECT + L_OBJECT + L_OBJECT + L_OBJECT + ")" + L_LIST, true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code new Builder()} becomes a real builder whose lookups hand out key references. */
    private static void emitNewGenerationBuilder(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "newGenerationBuilder",
                "()" + L_GEN_BUILDER, null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, STORE, "noteBuildingMod", "()V", false);
        mv.visitTypeInsn(NEW, GEN_BUILDER);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETSTATIC, GENERATED, "OWNER", L_OWNER);
        mv.visitFieldInsn(GETSTATIC, GENERATED, "OWNER", L_OWNER);
        mv.visitMethodInsn(INVOKESPECIAL, GEN_BUILDER, "<init>",
                "(L" + HOLDER_GETTER + ";L" + HOLDER_GETTER + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code addX(builder, step, holder)}: call the PlainBuilder adder and return the builder. */
    private static void emitBuilderAdder(ClassWriter cw, String name, String stepDesc, int stepLoad) {
        String plainName = name.equals("addCarver") ? "addCarver" : "addFeature";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(" + L_GEN_BUILDER + stepDesc + L_HOLDER + ")" + L_GEN_BUILDER, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(stepLoad, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, PLAIN_BUILDER, plainName,
                "(" + stepDesc + L_HOLDER + ")L" + PLAIN_BUILDER + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitEncoder(ClassWriter cw, String name, String owner, String what) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "(" + L_OBJECT + ")" + L_STRING, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, owner, "DIRECT_CODEC", "L" + CODEC + ";");
        mv.visitLdcInsn(what);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, GENERATED, "encode",
                "(L" + CODEC + ";" + L_STRING + L_OBJECT + ")" + L_STRING, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code encode(codec, what, value)}: encode with registry ops whose lookup owns the bridge's
     * holders, so every reference handed to the mod is written as its id.
     */
    private static void emitEncode(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "encode",
                "(L" + CODEC + ";" + L_STRING + L_OBJECT + ")" + L_STRING, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, JSON_OPS, "INSTANCE", "L" + JSON_OPS + ";");
        mv.visitFieldInsn(GETSTATIC, GENERATED, "OWNER", L_OWNER);
        mv.visitMethodInsn(INVOKESTATIC, REGISTRY_OPS, "create",
                "(L" + DYNAMIC_OPS + ";L" + INFO_LOOKUP + ";)L" + REGISTRY_OPS + ";", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, CODEC, "encodeStart",
                "(L" + DYNAMIC_OPS + ";" + L_OBJECT + ")L" + DATA_RESULT + ";", true);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, DATA_RESULT, "result", "()" + L_OPTIONAL, false);
        mv.visitVarInsn(ASTORE, 4);
        Label failed = new Label();
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false);
        mv.visitJumpInsn(IFEQ, failed);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "get", "()" + L_OBJECT, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()" + L_STRING, false);
        mv.visitInsn(ARETURN);
        mv.visitLabel(failed);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()" + L_STRING, false);
        mv.visitMethodInsn(INVOKESTATIC, STORE, "encodingFailed", "(" + L_STRING + L_STRING + ")V", false);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitPackFormat(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "packFormat", "()I", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, SHARED_CONSTANTS, "getCurrentVersion", "()L" + WORLD_VERSION + ";", false);
        mv.visitFieldInsn(GETSTATIC, PACK_TYPE, "SERVER_DATA", "L" + PACK_TYPE + ";");
        mv.visitMethodInsn(INVOKEINTERFACE, WORLD_VERSION, "getPackVersion", "(L" + PACK_TYPE + ";)I", true);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Without the pack API it answers null, and the store logs that worldgen is not restored. */
    private static void emitRepositorySource(ClassWriter cw, boolean packApi) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "repositorySource",
                "(" + L_STRING + "Ljava/nio/file/Path;)" + L_OBJECT, null, null);
        mv.visitCode();
        if (!packApi) {
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }
        mv.visitTypeInsn(NEW, PACK_SOURCE);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, PACK_SOURCE, "<init>", "(" + L_STRING + "Ljava/nio/file/Path;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code worldGenSettings(worldData)}: a placeholder the redirected {@code dimensions()} ignores. */
    private static void emitWorldGenSettings(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "worldGenSettings",
                "(L" + WORLD_DATA + ";)L" + WORLD_GEN_SETTINGS + ";", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code dimensions(settings)}: the running server's level stem registry, which is where the
     * dimensions moved. The event that calls this fires after the server is published to Forge's
     * lifecycle hooks and before any level is created from those stems.
     */
    private static void emitDimensions(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "dimensions",
                "(L" + WORLD_GEN_SETTINGS + ";)L" + REGISTRY + ";", null, null);
        mv.visitCode();
        // NeoForge kept Forge's package on 1.20.1 and moved to its own from 1.20.2.
        String hooks = com.retromod.util.McReflect.isNeoForge()
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.2") >= 0
                ? NEOFORGE_SERVER_HOOKS : SERVER_HOOKS;
        mv.visitMethodInsn(INVOKESTATIC, hooks, "getCurrentServer", "()L" + MINECRAFT_SERVER + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, MINECRAFT_SERVER, "registryAccess", "()L" + FROZEN_ACCESS + ";", false);
        mv.visitFieldInsn(GETSTATIC, REGISTRIES, "LEVEL_STEM", L_KEY);
        mv.visitMethodInsn(INVOKEINTERFACE, REGISTRY_ACCESS, "registryOrThrow",
                "(" + L_KEY + ")L" + REGISTRY + ";", true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code new MultiNoiseBiomeSource(list, preset)}: the preset went away in 1.19.4. */
    private static void emitNewMultiNoiseSource(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "newMultiNoiseSource",
                "(L" + PARAMETER_LIST + ";" + L_OPTIONAL + ")L" + MULTI_NOISE + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, MULTI_NOISE, "createFromList",
                "(L" + PARAMETER_LIST + ";)L" + MULTI_NOISE + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ---- LegacyWorldgenOwner ----------------------------------------------------------------

    /**
     * One object that owns the bridge's holders, looks up vanilla keys for the biome builder, and
     * tells the codecs which registries those holders belong to. A stand-alone reference only
     * serializes as its id when the encoding ops name its owner for that registry.
     */
    static byte[] generateOwner() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, OWNER, null, "java/lang/Object",
                new String[]{HOLDER_OWNER, HOLDER_GETTER, INFO_LOOKUP});
        emitDefaultConstructor(cw);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "get", "(" + L_KEY + ")" + L_OPTIONAL, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, REFERENCE, "createStandAlone",
                "(L" + HOLDER_OWNER + ";" + L_KEY + ")L" + REFERENCE + ";", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "of", "(" + L_OBJECT + ")" + L_OPTIONAL, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC, "get", "(L" + TAG_KEY + ";)" + L_OPTIONAL, null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "empty", "()" + L_OPTIONAL, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC, "lookup", "(" + L_KEY + ")" + L_OPTIONAL, null, null);
        mv.visitCode();
        Label owned = new Label();
        for (String registry : new String[]{"CONFIGURED_FEATURE", "PLACED_FEATURE", "CONFIGURED_CARVER"}) {
            mv.visitFieldInsn(GETSTATIC, REGISTRIES, registry, L_KEY);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(" + L_OBJECT + ")Z", false);
            mv.visitJumpInsn(IFNE, owned);
        }
        // Any other registry keeps the plain encoding, which writes tags and builtin entries by name.
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "empty", "()" + L_OPTIONAL, false);
        mv.visitInsn(ARETURN);
        mv.visitLabel(owned);
        mv.visitTypeInsn(NEW, REGISTRY_INFO);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, LIFECYCLE, "stable", "()L" + LIFECYCLE + ";", false);
        mv.visitMethodInsn(INVOKESPECIAL, REGISTRY_INFO, "<init>",
                "(L" + HOLDER_OWNER + ";L" + HOLDER_GETTER + ";L" + LIFECYCLE + ";)V", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "of", "(" + L_OBJECT + ")" + L_OPTIONAL, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- LegacyWorldgenPackSource -----------------------------------------------------------

    /** Offers the generated folder as a required server data pack, placed above the mod packs. */
    static byte[] generatePackSource() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, PACK_SOURCE, null, "java/lang/Object",
                new String[]{REPOSITORY_SOURCE, RESOURCES_SUPPLIER});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "id", L_STRING, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "root", "Ljava/nio/file/Path;", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_STRING + "Ljava/nio/file/Path;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, PACK_SOURCE, "id", L_STRING);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(PUTFIELD, PACK_SOURCE, "root", "Ljava/nio/file/Path;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC, "loadPacks", "(Ljava/util/function/Consumer;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, PACK_SOURCE, "id", L_STRING);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, PACK_SOURCE, "id", L_STRING);
        mv.visitMethodInsn(INVOKESTATIC, COMPONENT, "literal", "(" + L_STRING + ")L" + MUTABLE_COMPONENT + ";", true);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, PACK_TYPE, "SERVER_DATA", "L" + PACK_TYPE + ";");
        mv.visitFieldInsn(GETSTATIC, PACK_POSITION, "TOP", "L" + PACK_POSITION + ";");
        mv.visitFieldInsn(GETSTATIC, PACK_SOURCE_TYPE, "BUILT_IN", "L" + PACK_SOURCE_TYPE + ";");
        mv.visitMethodInsn(INVOKESTATIC, PACK, "readMetaAndCreate",
                "(" + L_STRING + "L" + COMPONENT + ";ZL" + RESOURCES_SUPPLIER + ";L" + PACK_TYPE
                        + ";L" + PACK_POSITION + ";L" + PACK_SOURCE_TYPE + ";)L" + PACK + ";", false);
        mv.visitVarInsn(ASTORE, 2);
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNULL, done);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(" + L_OBJECT + ")V", true);
        mv.visitLabel(done);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC, "open", "(" + L_STRING + ")L" + PACK_RESOURCES + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, PATH_RESOURCES);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, PACK_SOURCE, "root", "Ljava/nio/file/Path;");
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESPECIAL, PATH_RESOURCES, "<init>",
                "(" + L_STRING + "Ljava/nio/file/Path;Z)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- LegacyBiomeBuilder -----------------------------------------------------------------

    /** {@code precipitation(builder, kind)} becomes {@code hasPrecipitation(kind != NONE)}. */
    static byte[] generateBiomeBuilderBridge() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, BIOME_BUILDER_BRIDGE, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "precipitation",
                "(L" + BIOME_BUILDER + ";L" + PRECIPITATION + ";)L" + BIOME_BUILDER + ";", null, null);
        mv.visitCode();
        Label none = new Label();
        Label call = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, PRECIPITATION, "NONE", "L" + PRECIPITATION + ";");
        mv.visitJumpInsn(IF_ACMPEQ, none);
        mv.visitInsn(ICONST_1);
        mv.visitJumpInsn(GOTO, call);
        mv.visitLabel(none);
        mv.visitInsn(ICONST_0);
        mv.visitLabel(call);
        mv.visitMethodInsn(INVOKEVIRTUAL, BIOME_BUILDER, "hasPrecipitation", "(Z)L" + BIOME_BUILDER + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
