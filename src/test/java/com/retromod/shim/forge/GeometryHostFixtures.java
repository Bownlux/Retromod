/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import java.util.HashMap;
import java.util.Map;

/** Stand-in Forge 26.2 and 26.3 geometry and model baking classes, and Forge 1.20.1's geometry API as old mods compiled against it. */
final class GeometryHostFixtures {

    private GeometryHostFixtures() {}

    /** B27's host, with the geometry loader event, model baking, texture slots and client resources added. */
    static Map<String, String> hostSources(String host) {
        Map<String, String> sources = new HashMap<>(BakedModelHostFixtures.hostSources(host));
        String quads = sources.get("net/minecraft/client/resources/model/geometry/QuadCollection.java");
        // As on the host: a builder that took no quad hands back the one shared empty collection.
        sources.put("net/minecraft/client/resources/model/geometry/QuadCollection.java", quads.replace(
                "public Builder addUnculledFace(BakedQuad quad) { unculled.add(quad); return this; }",
                "public Builder addUnculledFace(BakedQuad quad) { unculled.add(quad); return this; }\n"
                        + "public Builder addAll(QuadCollection other) { other.culled.forEach((side, list) -> list.forEach(q -> addCulledFace(side, q)));"
                        + " other.unculled.forEach(this::addUnculledFace); return this; }").replace(
                "public QuadCollection build() { return new QuadCollection(culled, unculled); }",
                "public QuadCollection build() { return culled.isEmpty() && unculled.isEmpty() ? EMPTY : new QuadCollection(culled, unculled); }"));
        // The game's logger, which the bus bridge's generated helper writes to.
        sources.put("org/slf4j/Logger.java", """
                package org.slf4j;
                public interface Logger {
                    default void debug(String message, Object argument) {}
                    default void warn(String message, Object first, Object second) {}
                }""");
        // Named in the signatures of the bus bridge's generated helper, which reflection reads whole.
        sources.put("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.java",
                "package net.minecraftforge.fml.javafmlmod; public class FMLJavaModLoadingContext {}");
        sources.put("com/mojang/logging/LogUtils.java", """
                package com.mojang.logging;
                public final class LogUtils {
                    public static org.slf4j.Logger getLogger() { return new org.slf4j.Logger() {}; }
                }""");
        sources.put("net/minecraftforge/client/event/ModelEvent.java", """
                package net.minecraftforge.client.event;
                import net.minecraft.client.resources.model.ModelBakery;
                import net.minecraft.resources.Identifier;
                import net.minecraftforge.client.model.geometry.IGeometryLoader;
                import net.minecraftforge.eventbus.api.bus.EventBus;
                public interface ModelEvent {
                    record ModifyBakingResult(ModelBakery getModelBakery, ModelBakery.BakingResult getResults)
                            implements net.minecraftforge.eventbus.api.event.RecordEvent, ModelEvent {
                        public static final EventBus<ModifyBakingResult> BUS = EventBus.create(ModifyBakingResult.class);
                    }
                    record BakingCompleted(net.minecraft.client.resources.model.ModelManager getModelManager, ModelBakery getModelBakery)
                            implements net.minecraftforge.eventbus.api.event.RecordEvent, ModelEvent {
                        public static final EventBus<BakingCompleted> BUS = EventBus.create(BakingCompleted.class);
                    }
                    // As on the host: one map per reload, and a name registered twice is refused.
                    final class RegisterGeometryLoaders extends net.minecraftforge.eventbus.api.event.MutableEvent implements ModelEvent {
                        public static final EventBus<RegisterGeometryLoaders> BUS = EventBus.create(RegisterGeometryLoaders.class);
                        private final java.util.Map<Identifier, IGeometryLoader> loaders;
                        public RegisterGeometryLoaders(java.util.Map<Identifier, IGeometryLoader> loaders) { this.loaders = loaders; }
                        public void register(Identifier name, IGeometryLoader loader) {
                            if (loaders.containsKey(name)) throw new IllegalArgumentException("Geometry loader already registered: " + name);
                            loaders.put(name, loader);
                        }
                    }
                }""");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public static Minecraft getInstance() { return INSTANCE; }
                    public net.minecraft.server.packs.resources.ResourceManager getResourceManager() {
                        return id -> java.util.Optional.ofNullable(testhost.Resources.FILES.get(id.toString()))
                                .map(net.minecraft.server.packs.resources.Resource::new);
                    }
                }""");
        sources.put("testhost/Resources.java", """
                package testhost;
                public final class Resources {
                    public static final java.util.Map<String, String> FILES = new java.util.HashMap<>();
                }""");
        sources.put("net/minecraft/server/packs/resources/ResourceProvider.java", """
                package net.minecraft.server.packs.resources;
                public interface ResourceProvider {
                    java.util.Optional<Resource> getResource(net.minecraft.resources.Identifier id);
                }""");
        sources.put("net/minecraft/server/packs/resources/ResourceManager.java",
                "package net.minecraft.server.packs.resources; public interface ResourceManager extends ResourceProvider {}");
        sources.put("net/minecraft/server/packs/resources/Resource.java", """
                package net.minecraft.server.packs.resources;
                public class Resource {
                    private final String text;
                    public Resource(String text) { this.text = text; }
                    public java.io.BufferedReader openAsReader() { return new java.io.BufferedReader(new java.io.StringReader(text)); }
                }""");
        geometrySources(sources);
        modelSources(sources);
        return sources;
    }

    // Forge 26.2's geometry loader, baking context and helpers.
    private static void geometrySources(Map<String, String> sources) {
        sources.put("net/minecraftforge/client/model/geometry/IGeometryLoader.java", """
                package net.minecraftforge.client.model.geometry;
                public interface IGeometryLoader {
                    net.minecraft.client.resources.model.geometry.UnbakedGeometry read(com.google.gson.JsonObject json,
                            com.google.gson.JsonDeserializationContext context) throws com.google.gson.JsonParseException;
                }""");
        sources.put("net/minecraftforge/client/model/geometry/IGeometryBakingContext.java", """
                package net.minecraftforge.client.model.geometry;
                import net.minecraft.resources.Identifier;
                import net.minecraftforge.client.RenderTypeGroup;
                public interface IGeometryBakingContext {
                    boolean isGui3d();
                    boolean useBlockLight();
                    boolean useAmbientOcclusion();
                    net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms();
                    com.mojang.math.Transformation getRootTransform();
                    Identifier getRenderTypeHint();
                    default Identifier getRenderTypeFastHint() { return null; }
                    boolean isComponentVisible(String component, boolean fallback);
                    // Forge looks the name up among the named render types; the stand-in knows cutout.
                    default RenderTypeGroup getRenderType(Identifier name) {
                        return name != null && name.getPath().equals("cutout")
                                ? new RenderTypeGroup(net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT,
                                        net.minecraft.client.renderer.rendertype.RenderTypes.cutoutMovingBlock())
                                : RenderTypeGroup.EMPTY;
                    }
                }""");
        sources.put("net/minecraftforge/client/model/geometry/StandaloneGeometryBakingContext.java", """
                package net.minecraftforge.client.model.geometry;
                public class StandaloneGeometryBakingContext implements IGeometryBakingContext {
                    public static final StandaloneGeometryBakingContext INSTANCE = new StandaloneGeometryBakingContext();
                    public boolean isGui3d() { return true; }
                    public boolean useBlockLight() { return true; }
                    public boolean useAmbientOcclusion() { return true; }
                    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() {
                        return net.minecraft.client.resources.model.cuboid.ItemTransforms.NO_TRANSFORMS;
                    }
                    public com.mojang.math.Transformation getRootTransform() { return com.mojang.math.Transformation.IDENTITY; }
                    public net.minecraft.resources.Identifier getRenderTypeHint() { return null; }
                    public boolean isComponentVisible(String component, boolean fallback) { return fallback; }
                }""");
        sources.put("net/minecraftforge/client/model/geometry/UnbakedGeometryHelper.java", """
                package net.minecraftforge.client.model.geometry;
                public class UnbakedGeometryHelper {
                    public static net.minecraft.client.renderer.block.dispatch.ModelState composeRootTransformIntoModelState(
                            net.minecraft.client.renderer.block.dispatch.ModelState state, com.mojang.math.Transformation root) {
                        testhost.Notes.note("root transform composed");
                        return state;
                    }
                }""");
        // A model's own context, as Forge's ModelContext answers: hidden parts and a render type hint.
        sources.put("testhost/Context.java", """
                package testhost;
                public final class Context implements net.minecraftforge.client.model.geometry.IGeometryBakingContext {
                    private final java.util.Set<String> hidden;
                    private final net.minecraft.resources.Identifier hint;
                    private final com.mojang.math.Transformation root;
                    public Context(java.util.Set<String> hidden, net.minecraft.resources.Identifier hint, com.mojang.math.Transformation root) {
                        this.hidden = hidden;
                        this.hint = hint;
                        this.root = root;
                    }
                    public boolean isGui3d() { return true; }
                    public boolean useBlockLight() { return false; }
                    public boolean useAmbientOcclusion() { return true; }
                    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() {
                        return net.minecraft.client.resources.model.cuboid.ItemTransforms.NO_TRANSFORMS;
                    }
                    public com.mojang.math.Transformation getRootTransform() { return root; }
                    public net.minecraft.resources.Identifier getRenderTypeHint() { return hint; }
                    public boolean isComponentVisible(String component, boolean fallback) { return !hidden.contains(component) && fallback; }
                }""");
        sources.put("com/mojang/math/Transformation.java", """
                package com.mojang.math;
                public final class Transformation {
                    public static final Transformation IDENTITY = new Transformation(new org.joml.Matrix4f());
                    private final org.joml.Matrix4f matrix;
                    public Transformation(org.joml.Matrix4fc matrix) { this.matrix = new org.joml.Matrix4f(matrix); }
                    public org.joml.Matrix4fc getMatrix() { return matrix; }
                    @Override public boolean equals(Object other) { return other instanceof Transformation t && t.matrix.equals(matrix); }
                    @Override public int hashCode() { return matrix.hashCode(); }
                }""");
    }

    // Minecraft 26.2's unbaked models, resolved models, texture slots and their baker.
    private static void modelSources(Map<String, String> sources) {
        sources.put("net/minecraft/client/resources/model/sprite/Material.java", """
                package net.minecraft.client.resources.model.sprite;
                import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                public record Material(net.minecraft.resources.Identifier sprite) {
                    public record Baked(TextureAtlasSprite sprite, boolean forceTranslucent) {}
                }""");
        sources.put("net/minecraft/client/resources/model/sprite/SpriteId.java", """
                package net.minecraft.client.resources.model.sprite;
                public record SpriteId(net.minecraft.resources.Identifier atlasLocation, net.minecraft.resources.Identifier texture) {}""");
        sources.put("net/minecraft/client/resources/model/sprite/MaterialBaker.java", """
                package net.minecraft.client.resources.model.sprite;
                // The stand-in atlas holds every texture; each sprite is named for its texture.
                public class MaterialBaker {
                    public Material.Baked get(Material material, net.minecraft.client.resources.model.ModelDebugName name) {
                        testhost.Notes.note("sprite " + material.sprite() + " for " + name.debugName());
                        return new Material.Baked(testhost.Sprites.of(material.sprite().toString()), false);
                    }
                }""");
        sources.put("testhost/Sprites.java", """
                package testhost;
                public final class Sprites {
                    private static final java.util.Map<String, net.minecraft.client.renderer.texture.TextureAtlasSprite> SPRITES = new java.util.HashMap<>();
                    public static synchronized net.minecraft.client.renderer.texture.TextureAtlasSprite of(String name) {
                        return SPRITES.computeIfAbsent(name, n -> new net.minecraft.client.renderer.texture.TextureAtlasSprite(n,
                                com.mojang.blaze3d.platform.Transparency.NONE));
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/sprite/TextureSlots.java", """
                package net.minecraft.client.resources.model.sprite;
                import java.util.*;
                public class TextureSlots {
                    public static final TextureSlots EMPTY = new TextureSlots(Map.of());
                    private final Map<String, Material> values;
                    TextureSlots(Map<String, Material> values) { this.values = values; }
                    public Material getMaterial(String name) { return values.get(name); }
                    public static Data parseTextureMap(com.google.gson.JsonObject json) {
                        Map<String, String> raw = new LinkedHashMap<>();
                        json.entrySet().forEach(e -> raw.put(e.getKey(), e.getValue().getAsString()));
                        return new Data(raw);
                    }
                    public record Data(Map<String, String> values) { public static final Data EMPTY = new Data(Map.of()); }
                    // Nearer entries win; a "#name" value reads another slot.
                    public static class Resolver {
                        private final List<Data> entries = new ArrayList<>();
                        public Resolver addLast(Data data) { entries.add(data); return this; }
                        public TextureSlots resolve(net.minecraft.client.resources.model.ModelDebugName name) {
                            Map<String, String> merged = new HashMap<>();
                            for (Data data : entries) data.values().forEach(merged::putIfAbsent);
                            Map<String, Material> resolved = new HashMap<>();
                            for (String key : merged.keySet()) {
                                String value = merged.get(key);
                                for (int hops = 0; value != null && value.startsWith("#") && hops < 8; hops++) value = merged.get(value.substring(1));
                                if (value != null && !value.startsWith("#")) resolved.put(key, new Material(net.minecraft.resources.Identifier.tryParse(value)));
                            }
                            return new TextureSlots(resolved);
                        }
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelDebugName.java",
                "package net.minecraft.client.resources.model; public interface ModelDebugName { String debugName(); }");
        sources.put("net/minecraft/client/renderer/block/dispatch/ModelState.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public interface ModelState {}""");
        sources.put("net/minecraft/client/renderer/block/dispatch/SingleVariant.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public class SingleVariant implements BlockStateModel {
                    private final BlockStateModelPart model;
                    public SingleVariant(BlockStateModelPart model) { this.model = model; }
                    public void collectParts(net.minecraft.util.RandomSource random, java.util.List<BlockStateModelPart> parts) { parts.add(model); }
                    public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() { return model.particleMaterial(); }
                    public int materialFlags() { return 0; }
                }""");
        sources.put("net/minecraft/client/renderer/block/model/BlockModel.java", """
                package net.minecraft.client.renderer.block.model;
                public interface BlockModel {
                    void update(net.minecraft.client.renderer.block.BlockModelRenderState renderState,
                            net.minecraft.world.level.block.state.BlockState state, BlockDisplayContext context, long seed);
                }""");
        sources.put("net/minecraft/client/renderer/block/model/BlockDisplayContext.java",
                "package net.minecraft.client.renderer.block.model; public class BlockDisplayContext {}");
        sources.put("net/minecraft/client/renderer/block/BlockModelRenderState.java",
                "package net.minecraft.client.renderer.block; public class BlockModelRenderState {}");
        sources.put("net/minecraft/client/renderer/texture/TextureAtlas.java", """
                package net.minecraft.client.renderer.texture;
                public class TextureAtlas {
                    public static final net.minecraft.resources.Identifier LOCATION_BLOCKS =
                            net.minecraft.resources.Identifier.withDefaultNamespace("textures/atlas/blocks.png");
                }""");
        sources.put("net/minecraft/client/renderer/texture/MissingTextureAtlasSprite.java", """
                package net.minecraft.client.renderer.texture;
                public final class MissingTextureAtlasSprite {
                    public static net.minecraft.resources.Identifier getLocation() { return net.minecraft.resources.Identifier.withDefaultNamespace("missingno"); }
                }""");
        sources.put("net/minecraft/client/resources/model/geometry/UnbakedGeometry.java", """
                package net.minecraft.client.resources.model.geometry;
                import net.minecraft.client.renderer.block.dispatch.ModelState;
                import net.minecraft.client.resources.model.*;
                import net.minecraft.client.resources.model.sprite.TextureSlots;
                public interface UnbakedGeometry {
                    UnbakedGeometry EMPTY = (slots, baker, state, name) -> QuadCollection.EMPTY;
                    QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name);
                    // Forge's patch: the same bake with the model's own context.
                    default QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name,
                            net.minecraftforge.client.model.geometry.IGeometryBakingContext context) {
                        return bake(slots, baker, state, name);
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/UnbakedModel.java", """
                package net.minecraft.client.resources.model;
                public interface UnbakedModel {
                    default Boolean ambientOcclusion() { return null; }
                    default GuiLight guiLight() { return null; }
                    default net.minecraft.client.resources.model.cuboid.ItemTransforms transforms() { return null; }
                    default net.minecraft.client.resources.model.sprite.TextureSlots.Data textureSlots() {
                        return net.minecraft.client.resources.model.sprite.TextureSlots.Data.EMPTY;
                    }
                    default net.minecraft.client.resources.model.geometry.UnbakedGeometry geometry() { return null; }
                    default net.minecraft.resources.Identifier parent() { return null; }
                    enum GuiLight { FRONT, SIDE; public boolean lightLikeBlock() { return this == SIDE; } }
                }""");
        // Minecraft's resolved model defaults: the nearest model's textures, geometry and display settings win.
        sources.put("net/minecraft/client/resources/model/ResolvedModel.java", """
                package net.minecraft.client.resources.model;
                import net.minecraft.client.renderer.block.dispatch.ModelState;
                import net.minecraft.client.resources.model.cuboid.ItemTransforms;
                import net.minecraft.client.resources.model.geometry.*;
                import net.minecraft.client.resources.model.sprite.*;
                public interface ResolvedModel extends ModelDebugName {
                    UnbakedModel wrapped();
                    ResolvedModel parent();
                    default TextureSlots getTopTextureSlots() {
                        TextureSlots.Resolver resolver = new TextureSlots.Resolver();
                        for (ResolvedModel m = this; m != null; m = m.parent()) resolver.addLast(m.wrapped().textureSlots());
                        return resolver.resolve(this);
                    }
                    default boolean getTopAmbientOcclusion() {
                        for (ResolvedModel m = this; m != null; m = m.parent()) if (m.wrapped().ambientOcclusion() != null) return m.wrapped().ambientOcclusion();
                        return true;
                    }
                    default UnbakedModel.GuiLight getTopGuiLight() {
                        for (ResolvedModel m = this; m != null; m = m.parent()) if (m.wrapped().guiLight() != null) return m.wrapped().guiLight();
                        return UnbakedModel.GuiLight.SIDE;
                    }
                    default UnbakedGeometry getTopGeometry() {
                        for (ResolvedModel m = this; m != null; m = m.parent()) if (m.wrapped().geometry() != null) return m.wrapped().geometry();
                        return UnbakedGeometry.EMPTY;
                    }
                    // As on Forge 26.2 and 26.3: the geometry bakes with the model's own context, whose default is
                    // Forge's real ModelContext, which casts every parent it walks to a discovered model.
                    default QuadCollection bakeTopGeometry(TextureSlots slots, ModelBaker baker, ModelState state) {
                        return getTopGeometry().bake(slots, baker, state, this, getContext());
                    }
                    default net.minecraftforge.client.model.geometry.IGeometryBakingContext getContext() {
                        return new net.minecraftforge.client.model.geometry.ModelContext(this);
                    }
                    default Material.Baked resolveParticleMaterial(TextureSlots slots, ModelBaker baker) {
                        Material particle = slots.getMaterial("particle");
                        return particle == null ? null : baker.materials().get(particle, this);
                    }
                    default ItemTransforms getTopTransforms() {
                        for (ResolvedModel m = this; m != null; m = m.parent()) if (m.wrapped().transforms() != null) return m.wrapped().transforms();
                        return ItemTransforms.NO_TRANSFORMS;
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelBaker.java", """
                package net.minecraft.client.resources.model;
                public interface ModelBaker {
                    ResolvedModel getModel(net.minecraft.resources.Identifier id);
                    net.minecraft.client.renderer.block.dispatch.BlockStateModelPart missingBlockModelPart();
                    net.minecraft.client.resources.model.sprite.MaterialBaker materials();
                }""");
        // The models Minecraft discovers itself, the only kind Forge's ModelContext accepts as a parent's parent.
        sources.put("net/minecraft/client/resources/model/ModelDiscovery.java", """
                package net.minecraft.client.resources.model;
                public class ModelDiscovery {
                    public abstract static class ModelWrapper implements ResolvedModel {}
                }""");
        // The baker the tests bake with: only builtin models are discovered, as for models only a geometry names.
        sources.put("testhost/Baker.java", """
                package testhost;
                import net.minecraft.client.resources.model.*;
                public final class Baker implements ModelBaker {
                    private final net.minecraft.client.resources.model.sprite.MaterialBaker materials = new net.minecraft.client.resources.model.sprite.MaterialBaker();
                    public ResolvedModel getModel(net.minecraft.resources.Identifier id) {
                        Notes.note("baker model " + id);
                        net.minecraft.client.resources.model.geometry.UnbakedGeometry missing = (slots, baker, state, name) ->
                                new net.minecraft.client.resources.model.geometry.QuadCollection.Builder().addUnculledFace(World.quad(null, 99, World.STONE)).build();
                        UnbakedModel model = new UnbakedModel() {
                            public net.minecraft.client.resources.model.geometry.UnbakedGeometry geometry() { return missing; }
                        };
                        return new ResolvedModel() {
                            public UnbakedModel wrapped() { return model; }
                            public ResolvedModel parent() { return null; }
                            public String debugName() { return id.toString(); }
                        };
                    }
                    public net.minecraft.client.renderer.block.dispatch.BlockStateModelPart missingBlockModelPart() {
                        return new SimpleModelWrapper(new net.minecraft.client.resources.model.geometry.QuadCollection.Builder()
                                .addUnculledFace(World.quad(null, 99, World.STONE)).build(), true, null);
                    }
                    public net.minecraft.client.resources.model.sprite.MaterialBaker materials() { return materials; }
                }""");
        // Minecraft's model JSON, reduced to parents, textures and elements whose one face carries a tint index.
        sources.put("net/minecraft/client/resources/model/cuboid/CuboidModel.java", """
                package net.minecraft.client.resources.model.cuboid;
                import net.minecraft.client.resources.model.UnbakedModel;
                import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
                import net.minecraft.client.resources.model.sprite.TextureSlots;
                import net.minecraft.resources.Identifier;
                public record CuboidModel(UnbakedGeometry geometry, UnbakedModel.GuiLight guiLight, Boolean ambientOcclusion,
                        ItemTransforms transforms, TextureSlots.Data textureSlots, Identifier parent) implements UnbakedModel {
                    public static CuboidModel fromStream(java.io.Reader reader) {
                        return fromJson(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject());
                    }
                    public static CuboidModel fromJson(com.google.gson.JsonObject json) {
                        java.util.List<CuboidModelElement> elements = new java.util.ArrayList<>();
                        if (json.has("elements")) json.getAsJsonArray("elements").forEach(e -> elements.add(CuboidModelElement.fromJson(e)));
                        TextureSlots.Data textures = json.has("textures") ? TextureSlots.parseTextureMap(json.getAsJsonObject("textures"))
                                : TextureSlots.Data.EMPTY;
                        Identifier parent = json.has("parent") ? Identifier.tryParse(json.get("parent").getAsString()) : null;
                        return new CuboidModel(elements.isEmpty() ? null : new UnbakedCuboidGeometry(elements), null,
                                json.has("ambientocclusion") ? json.get("ambientocclusion").getAsBoolean() : null, null, textures, parent);
                    }
                    // Forge's own model data, which the stand-in parser leaves empty.
                    public net.minecraftforge.client.model.ForgeBlockModelData forgeData() { return null; }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/CuboidModelElement.java", """
                package net.minecraft.client.resources.model.cuboid;
                public record CuboidModelElement(int tint) {
                    public static CuboidModelElement fromJson(com.google.gson.JsonElement json) {
                        return new CuboidModelElement(json.getAsJsonObject().get("tint").getAsInt());
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/UnbakedCuboidGeometry.java", """
                package net.minecraft.client.resources.model.cuboid;
                import net.minecraft.client.resources.model.geometry.*;
                import net.minecraft.client.resources.model.sprite.*;
                // Each element is one unculled face, drawn with the "all" texture.
                public record UnbakedCuboidGeometry(java.util.List<CuboidModelElement> elements) implements UnbakedGeometry {
                    public QuadCollection bake(TextureSlots slots, net.minecraft.client.resources.model.ModelBaker baker,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, net.minecraft.client.resources.model.ModelDebugName name) {
                        Material all = slots.getMaterial("all");
                        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = all == null ? testhost.World.STONE
                                : baker.materials().get(all, name).sprite();
                        QuadCollection.Builder quads = new QuadCollection.Builder();
                        for (CuboidModelElement element : elements) quads.addUnculledFace(testhost.World.quad(null, element.tint(), sprite));
                        return quads.build();
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/ItemModelGenerator.java", """
                package net.minecraft.client.resources.model.cuboid;
                import net.minecraft.client.resources.model.geometry.*;
                // Vanilla's generated item geometry: one face per layer texture, tinted with the layer's index.
                public class ItemModelGenerator implements net.minecraft.client.resources.model.UnbakedModel {
                    public UnbakedGeometry geometry() {
                        return (slots, baker, state, name) -> {
                            QuadCollection.Builder quads = new QuadCollection.Builder();
                            for (int layer = 0; layer < 5; layer++) {
                                if (slots.getMaterial("layer" + layer) != null) quads.addUnculledFace(testhost.World.quad(null, layer, testhost.World.STONE));
                            }
                            return quads.build();
                        };
                    }
                }""");
        // Minecraft's JSON context for nested models, which reads them as the stand-in parser does.
        sources.put("testhost/JsonContext.java", """
                package testhost;
                public final class JsonContext implements com.google.gson.JsonDeserializationContext {
                    @SuppressWarnings("unchecked")
                    public <T> T deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type) {
                        if (type == net.minecraft.client.resources.model.cuboid.CuboidModel.class) {
                            return (T) net.minecraft.client.resources.model.cuboid.CuboidModel.fromJson(json.getAsJsonObject());
                        }
                        if (type == net.minecraft.client.resources.model.cuboid.CuboidModelElement.class) {
                            return (T) net.minecraft.client.resources.model.cuboid.CuboidModelElement.fromJson(json);
                        }
                        throw new com.google.gson.JsonParseException("The stand-in context reads no " + type);
                    }
                }""");
    }

    /** Forge 1.20.1's geometry API and the vanilla model calls old geometry made, in Minecraft 26.x class names. */
    static Map<String, String> oldApiSources() {
        Map<String, String> sources = new HashMap<>(BakedModelHostFixtures.oldApiSources());
        sources.put("net/minecraftforge/client/model/geometry/IGeometryLoader.java", """
                package net.minecraftforge.client.model.geometry;
                public interface IGeometryLoader<T extends IUnbakedGeometry<T>> {
                    T read(com.google.gson.JsonObject json, com.google.gson.JsonDeserializationContext context) throws com.google.gson.JsonParseException;
                }""");
        sources.put("net/minecraftforge/client/model/geometry/IUnbakedGeometry.java", """
                package net.minecraftforge.client.model.geometry;
                import java.util.function.Function;
                import net.minecraft.resources.Identifier;
                public interface IUnbakedGeometry<T extends IUnbakedGeometry<T>> {
                    net.minecraft.client.resources.model.BakedModel bake(IGeometryBakingContext context, net.minecraft.client.resources.model.ModelBaker baker,
                            Function<net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, net.minecraft.client.renderer.block.model.ItemOverrides overrides,
                            Identifier location);
                    default void resolveParents(Function<Identifier, net.minecraft.client.resources.model.UnbakedModel> getter, IGeometryBakingContext context) {}
                }""");
        sources.put("net/minecraftforge/client/model/geometry/IGeometryBakingContext.java", """
                package net.minecraftforge.client.model.geometry;
                public interface IGeometryBakingContext {
                    String getModelName();
                    boolean hasMaterial(String name);
                    net.minecraft.client.resources.model.sprite.SpriteId getMaterial(String name);
                    boolean isGui3d();
                    boolean useBlockLight();
                    boolean useAmbientOcclusion();
                    net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms();
                    com.mojang.math.Transformation getRootTransform();
                    net.minecraft.resources.Identifier getRenderTypeHint();
                    boolean isComponentVisible(String component, boolean fallback);
                }""");
        sources.put("net/minecraftforge/client/event/ModelEvent.java", """
                package net.minecraftforge.client.event;
                import java.util.Map;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.resources.Identifier;
                public abstract class ModelEvent {
                    public static class ModifyBakingResult extends ModelEvent { public Map<Identifier, BakedModel> getModels() { return null; } }
                    public static class BakingCompleted extends ModelEvent { public Map<Identifier, BakedModel> getModels() { return null; } }
                    public static class RegisterAdditional extends ModelEvent { public void register(Identifier model) {} }
                    public static class RegisterGeometryLoaders extends ModelEvent {
                        public void register(String name, net.minecraftforge.client.model.geometry.IGeometryLoader<?> loader) {}
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/UnbakedModel.java", """
                package net.minecraft.client.resources.model;
                import java.util.function.Function;
                import net.minecraft.resources.Identifier;
                public interface UnbakedModel {
                    BakedModel bake(ModelBaker baker, Function<net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, Identifier location);
                    void resolveParents(Function<Identifier, UnbakedModel> getter);
                    enum GuiLight { FRONT, SIDE }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelBaker.java", """
                package net.minecraft.client.resources.model;
                public interface ModelBaker {
                    UnbakedModel getModel(net.minecraft.resources.Identifier id);
                    BakedModel bake(net.minecraft.resources.Identifier id, net.minecraft.client.renderer.block.dispatch.ModelState state);
                }""");
        sources.put("net/minecraft/client/resources/model/ModelBakery.java", """
                package net.minecraft.client.resources.model;
                public class ModelBakery {
                    public static final ModelResourceLocation MISSING_MODEL_LOCATION = null;
                    public UnbakedModel getModel(net.minecraft.resources.Identifier id) { return null; }
                }""");
        // Minecraft 1.20.1's JSON model, a class with these members.
        sources.put("net/minecraft/client/renderer/block/model/BlockModel.java", """
                package net.minecraft.client.renderer.block.model;
                import java.util.function.Function;
                import net.minecraft.client.resources.model.*;
                import net.minecraft.resources.Identifier;
                public class BlockModel implements UnbakedModel {
                    public BlockModel(Identifier parent, java.util.List<net.minecraft.client.resources.model.cuboid.CuboidModelElement> elements,
                            java.util.Map<String, ?> textures, Boolean ambientOcclusion, UnbakedModel.GuiLight guiLight,
                            net.minecraft.client.resources.model.cuboid.ItemTransforms transforms, java.util.List<?> overrides) {}
                    public java.util.List<net.minecraft.client.resources.model.cuboid.CuboidModelElement> getElements() { return null; }
                    public BakedModel bake(ModelBaker baker, Function<net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, Identifier location) { return null; }
                    public void resolveParents(Function<Identifier, UnbakedModel> getter) {}
                }""");
        sources.put("net/minecraft/client/renderer/block/model/ItemOverrides.java", """
                package net.minecraft.client.renderer.block.model;
                public class ItemOverrides {
                    public static final ItemOverrides EMPTY = null;
                    public ItemOverrides() {}
                    public ItemOverrides(net.minecraft.client.resources.model.ModelBaker baker, BlockModel model, java.util.List<?> overrides) {}
                    public net.minecraft.client.resources.model.BakedModel resolve(net.minecraft.client.resources.model.BakedModel model,
                            net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.world.entity.LivingEntity entity, int seed) { return null; }
                }""");
        return sources;
    }

    /**
     * Two old mods: moda's loader bakes fixed quads and a nested model, as lootr's barrel does; modb's loader bakes a
     * model drawn per model data and builds a model on a part file, as Sophisticated Backpacks does.
     */
    static Map<String, String> oldModSources() {
        Map<String, String> sources = new HashMap<>();
        sources.put("moda/CubeLoader.java", """
                package moda;
                import net.minecraft.client.renderer.block.model.BlockModel;
                import net.minecraft.client.resources.model.UnbakedModel;
                public class CubeLoader implements net.minecraftforge.client.model.geometry.IGeometryLoader<CubeGeometry> {
                    public CubeGeometry read(com.google.gson.JsonObject json, com.google.gson.JsonDeserializationContext context) {
                        UnbakedModel inner = json.has("inner") ? (UnbakedModel) context.deserialize(json.get("inner"), BlockModel.class) : null;
                        return new CubeGeometry(json.get("tint").getAsInt(), inner);
                    }
                }""");
        sources.put("moda/CubeGeometry.java", """
                package moda;
                import java.util.*;
                import java.util.function.Function;
                import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                import net.minecraft.client.resources.model.*;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.client.resources.model.sprite.SpriteId;
                import net.minecraft.resources.Identifier;
                import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
                import testhost.*;
                public class CubeGeometry implements net.minecraftforge.client.model.geometry.IUnbakedGeometry<CubeGeometry> {
                    private final int tint;
                    private final UnbakedModel inner;
                    public CubeGeometry(int tint, UnbakedModel inner) { this.tint = tint; this.inner = inner; }
                    public void resolveParents(Function<Identifier, UnbakedModel> getter, IGeometryBakingContext context) {
                        if (inner != null) inner.resolveParents(getter);
                    }
                    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<SpriteId, TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, net.minecraft.client.renderer.block.model.ItemOverrides overrides,
                            Identifier location) {
                        SpriteId side = context.getMaterial("side");
                        TextureAtlasSprite sprite = sprites.apply(side);
                        Notes.note("A bake " + location + " " + context.getModelName() + " side=" + side.texture() + " has="
                                + context.hasMaterial("side") + "/" + context.hasMaterial("nope") + " missing=" + context.getMaterial("nope").texture());
                        List<BakedQuad> quads = new ArrayList<>(List.of(World.quad(null, tint, sprite)));
                        if (inner != null) quads.addAll(inner.bake(baker, sprites, state, location).getQuads(null, null, null));
                        return new StaticModel(quads);
                    }
                }""");
        sources.put("moda/StaticModel.java", """
                package moda;
                import java.util.List;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                public class StaticModel implements net.minecraft.client.resources.model.BakedModel {
                    private final List<BakedQuad> quads;
                    public StaticModel(List<BakedQuad> quads) { this.quads = quads; }
                    public List<BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random) { return side == null ? quads : List.of(); }
                    public boolean useAmbientOcclusion() { return true; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return null; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
                        return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                    }
                }""");
        sources.put("moda/Listeners.java", """
                package moda;
                import net.minecraftforge.client.event.ModelEvent;
                public class Listeners {
                    public static void onRegister(ModelEvent.RegisterGeometryLoaders event) { event.register("cube", new CubeLoader()); }
                    // Patchouli's book model: overrides built from the missing model, which it casts to BlockModel.
                    public static Object bookOverrides(net.minecraft.client.resources.model.ModelBakery bakery) {
                        net.minecraft.client.renderer.block.model.BlockModel missing = (net.minecraft.client.renderer.block.model.BlockModel)
                                bakery.getModel(net.minecraft.client.resources.model.ModelBakery.MISSING_MODEL_LOCATION);
                        return new net.minecraft.client.renderer.block.model.ItemOverrides(null, missing, java.util.List.of()) {};
                    }
                }""");
        sources.put("modb/SpinnerLoader.java", """
                package modb;
                public class SpinnerLoader implements net.minecraftforge.client.model.geometry.IGeometryLoader<SpinnerGeometry> {
                    public SpinnerGeometry read(com.google.gson.JsonObject json, com.google.gson.JsonDeserializationContext context) {
                        return new SpinnerGeometry();
                    }
                }""");
        sources.put("modb/SpinnerGeometry.java", """
                package modb;
                import java.util.*;
                import java.util.function.Function;
                import net.minecraft.client.renderer.block.model.BlockModel;
                import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                import net.minecraft.client.resources.model.*;
                import net.minecraft.client.resources.model.sprite.SpriteId;
                import net.minecraft.resources.Identifier;
                import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
                import testhost.Notes;
                public class SpinnerGeometry implements net.minecraftforge.client.model.geometry.IUnbakedGeometry<SpinnerGeometry> {
                    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<SpriteId, TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, net.minecraft.client.renderer.block.model.ItemOverrides overrides,
                            Identifier location) {
                        // A model on a part file, its textures overridden, read and baked as Sophisticated Backpacks does.
                        BlockModel part = new BlockModel(Identifier.fromNamespaceAndPath("modb", "block/part"), List.of(), Map.of(), true, null, null, List.of());
                        part.resolveParents(baker::getModel);
                        BakedModel baked = part.bake(baker, sprites, state, location);
                        Notes.note("B part " + part.getElements().size() + " " + baked.getQuads(null, null, null).size());
                        return new SpinnerModel(baked);
                    }
                }""");
        sources.put("modb/SpinnerModel.java", """
                package modb;
                import java.util.List;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.core.Direction;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraftforge.client.model.data.ModelData;
                import testhost.World;
                // Tint 20 with no model data and 21 with the block entity's; the part's quads stay under it.
                public class SpinnerModel implements net.minecraftforge.client.model.IDynamicBakedModel {
                    private final BakedModel part;
                    public SpinnerModel(BakedModel part) { this.part = part; }
                    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
                        if (side != null) return List.of();
                        int tint = data != null && data.tag().equals("spin") ? 21 : 20;
                        return List.of(World.quad(null, tint, World.STONE), part.getQuads(state, null, random).get(0));
                    }
                    public ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                            BlockState state, ModelData data) { return ModelData.of("spin"); }
                    public boolean useAmbientOcclusion() { return true; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return World.STONE; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
                        return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                    }
                }""");
        sources.put("modb/Listeners.java", """
                package modb;
                import net.minecraftforge.client.event.ModelEvent;
                public class Listeners {
                    public static void onRegister(ModelEvent.RegisterGeometryLoaders event) {
                        event.register("spinner", new SpinnerLoader());
                        event.register("ghost", new net.minecraftforge.client.model.geometry.IGeometryLoader<GhostGeometry>() {
                            public GhostGeometry read(com.google.gson.JsonObject json, com.google.gson.JsonDeserializationContext context) {
                                return new GhostGeometry();
                            }
                        });
                        event.register("broken", new net.minecraftforge.client.model.geometry.IGeometryLoader<BrokenGeometry>() {
                            public BrokenGeometry read(com.google.gson.JsonObject json, com.google.gson.JsonDeserializationContext context) {
                                return new BrokenGeometry();
                            }
                        });
                        event.register("throwing", new net.minecraftforge.client.model.geometry.IGeometryLoader<ThrowingGeometry>() {
                            public ThrowingGeometry read(com.google.gson.JsonObject json, com.google.gson.JsonDeserializationContext context) {
                                return new ThrowingGeometry();
                            }
                        });
                    }
                    // Moonlight's helper: an addon hands it a loader name, and it registers the path alone.
                    public static void registerForAddon(ModelEvent.RegisterGeometryLoaders event) {
                        event.register("library_gear", new SpinnerLoader());
                    }
                }""");
        // A model that draws only from a block entity's model data, as Supplementaries' sign posts do.
        sources.put("modb/GhostGeometry.java", """
                package modb;
                import java.util.List;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.core.Direction;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraftforge.client.model.data.ModelData;
                public class GhostGeometry implements net.minecraftforge.client.model.geometry.IUnbakedGeometry<GhostGeometry> {
                    public net.minecraft.client.resources.model.BakedModel bake(net.minecraftforge.client.model.geometry.IGeometryBakingContext context,
                            net.minecraft.client.resources.model.ModelBaker baker,
                            java.util.function.Function<net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, net.minecraft.client.renderer.block.model.ItemOverrides overrides,
                            net.minecraft.resources.Identifier location) {
                        return new net.minecraftforge.client.model.IDynamicBakedModel() {
                            public List<BakedQuad> getQuads(BlockState s, Direction side, RandomSource random, ModelData data, RenderType type) {
                                return side == null && data != null && data.tag().equals("spin") ? List.of(testhost.World.quad(null, 31, testhost.World.STONE)) : List.of();
                            }
                            public boolean useAmbientOcclusion() { return true; }
                            public boolean isGui3d() { return true; }
                            public boolean usesBlockLight() { return true; }
                            public boolean isCustomRenderer() { return false; }
                            public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return testhost.World.STONE; }
                            public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
                                return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                            }
                        };
                    }
                }""");
        // A model whose quads and methods name a class missing from the host, which the test game leaves out.
        sources.put("modb/Absent.java", "package modb; public class Absent {}");
        sources.put("modb/ThrowingGeometry.java", """
                package modb;
                public class ThrowingGeometry implements net.minecraftforge.client.model.geometry.IUnbakedGeometry<ThrowingGeometry> {
                    public net.minecraft.client.resources.model.BakedModel bake(net.minecraftforge.client.model.geometry.IGeometryBakingContext context,
                            net.minecraft.client.resources.model.ModelBaker baker,
                            java.util.function.Function<net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, net.minecraft.client.renderer.block.model.ItemOverrides overrides,
                            net.minecraft.resources.Identifier location) {
                        return new ThrowingModel();
                    }
                }""");
        sources.put("modb/ThrowingModel.java", """
                package modb;
                public class ThrowingModel implements net.minecraft.client.resources.model.BakedModel {
                    public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(
                            net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random) { throw new NoClassDefFoundError("modb/Absent"); }
                    public void uses(Absent absent) {}
                    public boolean useAmbientOcclusion() { return true; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return null; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
                        return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                    }
                }""");
        // A geometry whose bake meets a class or member missing from the host.
        sources.put("modb/BrokenGeometry.java", """
                package modb;
                public class BrokenGeometry implements net.minecraftforge.client.model.geometry.IUnbakedGeometry<BrokenGeometry> {
                    public net.minecraft.client.resources.model.BakedModel bake(net.minecraftforge.client.model.geometry.IGeometryBakingContext context,
                            net.minecraft.client.resources.model.ModelBaker baker,
                            java.util.function.Function<net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites,
                            net.minecraft.client.renderer.block.dispatch.ModelState state, net.minecraft.client.renderer.block.model.ItemOverrides overrides,
                            net.minecraft.resources.Identifier location) {
                        throw new NoSuchMethodError("net.minecraft.client.resources.model.SimpleBakedModel$Builder.build()");
                    }
                }""");
        return sources;
    }
}
