/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import java.util.HashMap;
import java.util.Map;

/** Stand-in Minecraft 26.2 and 26.3 block, item and baking classes, and Forge 1.20.1's model API as old mods compiled against it. */
final class BakedModelHostFixtures {

    /** Minecraft 26.2 whose Identifier constructor refuses a path with '#', so Retromod spells variants with path characters. */
    static final String HOST_262_CLOSED = "26.2 with Identifier's constructor closed";

    private BakedModelHostFixtures() {}

    /** The host for {@code host}: B26's quads and materials, then the block, item, baking, pack and FML classes. */
    static Map<String, String> hostSources(String host) {
        boolean split = host.equals(ModelHostFixtures.HOST_263);
        boolean closed = host.equals(HOST_262_CLOSED);
        Map<String, String> sources = new HashMap<>(ModelHostFixtures.sources(closed ? ModelHostFixtures.HOST_262 : host));
        // Every host material reports its flags, which item models read for animated textures.
        String quad = sources.get("net/minecraft/client/resources/model/geometry/BakedQuad.java");
        sources.put("net/minecraft/client/resources/model/geometry/BakedQuad.java", quad.replace(
                "public boolean isTinted() { return tintIndex != -1; }",
                "public boolean isTinted() { return tintIndex != -1; } public int flags() { return 0; }"));
        sources.put("testhost/Notes.java", """
                package testhost;
                public final class Notes {
                    public static final java.util.List<String> SEEN = new java.util.concurrent.CopyOnWriteArrayList<>();
                    public static void note(String note) { SEEN.add(note); }
                }""");
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String namespace;
                    private final String path;
                    // As on the host: the constructor takes the path as given, and only the factories check it.
                    private Identifier(String namespace, String path) {
                        %s
                        this.namespace = namespace;
                        this.path = path;
                    }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) {
                        if (!path.matches("[a-z0-9/._-]*") || !namespace.matches("[a-z0-9_.-]+")) {
                            throw new IllegalArgumentException("Non [a-z0-9/._-] character in " + namespace + ":" + path);
                        }
                        return new Identifier(namespace, path);
                    }
                    public static Identifier withDefaultNamespace(String path) { return fromNamespaceAndPath("minecraft", path); }
                    public static Identifier tryParse(String id) {
                        int colon = id.indexOf(':');
                        try {
                            return colon < 0 ? withDefaultNamespace(id) : fromNamespaceAndPath(id.substring(0, colon), id.substring(colon + 1));
                        } catch (IllegalArgumentException invalid) {
                            return null;
                        }
                    }
                    public String getNamespace() { return namespace; }
                    public String getPath() { return path; }
                    @Override public String toString() { return namespace + ":" + path; }
                    @Override public boolean equals(Object other) {
                        return other instanceof Identifier id && id.namespace.equals(namespace) && id.path.equals(path);
                    }
                    @Override public int hashCode() { return 31 * namespace.hashCode() + path.hashCode(); }
                    public Identifier withPrefix(String prefix) { return fromNamespaceAndPath(namespace, prefix + path); }
                    public String toLanguageKey() { return namespace + "." + path; }
                }""".formatted(closed ? "if (path.indexOf('#') >= 0) throw new IllegalArgumentException(\"closed\");" : ""));
        sources.put("com/google/common/collect/ImmutableList.java", """
                package com.google.common.collect;
                public class ImmutableList<E> extends java.util.AbstractList<E> {
                    private final java.util.List<E> items;
                    private ImmutableList(java.util.List<E> items) { this.items = java.util.List.copyOf(items); }
                    public static <E> ImmutableList<E> copyOf(java.util.Collection<? extends E> items) { return new ImmutableList<>(new java.util.ArrayList<>(items)); }
                    public E get(int index) { return items.get(index); }
                    public int size() { return items.size(); }
                }""");
        sources.put("net/minecraft/world/level/block/state/properties/Property.java", """
                package net.minecraft.world.level.block.state.properties;
                public abstract class Property<T extends Comparable<T>> {
                    private final String name;
                    protected Property(String name) { this.name = name; }
                    public String getName() { return name; }
                    public abstract String getName(T value);
                }""");
        sources.put("testhost/BoolProperty.java", """
                package testhost;
                public final class BoolProperty extends net.minecraft.world.level.block.state.properties.Property<Boolean> {
                    public BoolProperty(String name) { super(name); }
                    public String getName(Boolean value) { return value.toString(); }
                }""");
        sources.put("net/minecraft/world/level/block/state/StateDefinition.java", """
                package net.minecraft.world.level.block.state;
                import net.minecraft.world.level.block.state.properties.Property;
                public class StateDefinition<O, S> {
                    private final com.google.common.collect.ImmutableList<S> states;
                    private final java.util.Collection<Property<?>> properties;
                    public StateDefinition(java.util.List<S> states, java.util.Collection<Property<?>> properties) {
                        this.states = com.google.common.collect.ImmutableList.copyOf(states);
                        this.properties = properties;
                    }
                    public com.google.common.collect.ImmutableList<S> getPossibleStates() { return states; }
                    public java.util.Collection<Property<?>> getProperties() { return properties; }
                }""");
        sources.put("net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                import java.util.*;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.block.state.StateDefinition;
                import net.minecraft.world.level.block.state.properties.Property;
                public class Block {
                    private final String name;
                    private final StateDefinition<Block, BlockState> definition;
                    // A block with up to two true/false properties, one state for each combination.
                    public Block(String name, Property<?>... properties) {
                        this.name = name;
                        List<BlockState> states = new ArrayList<>();
                        int combinations = 1 << properties.length;
                        for (int bits = 0; bits < combinations; bits++) {
                            Map<Property<?>, Comparable<?>> values = new LinkedHashMap<>();
                            for (int i = 0; i < properties.length; i++) values.put(properties[i], (bits & (1 << i)) != 0);
                            states.add(new BlockState(this, values));
                        }
                        // The host's definition lists its properties sorted by name.
                        List<Property<?>> sorted = new ArrayList<>(Arrays.asList(properties));
                        sorted.sort(Comparator.comparing(Property::getName));
                        this.definition = new StateDefinition<>(states, sorted);
                    }
                    public StateDefinition<Block, BlockState> getStateDefinition() { return definition; }
                    public BlockState defaultBlockState() { return definition.getPossibleStates().get(0); }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                import net.minecraft.world.level.block.state.properties.Property;
                public class BlockState {
                    private final net.minecraft.world.level.block.Block block;
                    private final java.util.Map<Property<?>, Comparable<?>> values;
                    public BlockState(net.minecraft.world.level.block.Block block, java.util.Map<Property<?>, Comparable<?>> values) {
                        this.block = block;
                        this.values = values;
                    }
                    public BlockState(net.minecraft.world.level.block.Block block) { this(block, java.util.Map.of()); }
                    public net.minecraft.world.level.block.Block getBlock() { return block; }
                    @SuppressWarnings("unchecked")
                    public <T extends Comparable<T>> T getValue(Property<T> property) { return (T) values.get(property); }
                    @Override public String toString() { return block + "" + values; }
                }""");
        sources.put("net/minecraft/core/DefaultedRegistry.java", """
                package net.minecraft.core;
                public interface DefaultedRegistry<T> {
                    net.minecraft.resources.Identifier getKey(T value);
                    T getValue(net.minecraft.resources.Identifier id);
                }""");
        sources.put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.world.level.block.Block;
                public class BuiltInRegistries {
                    public static final java.util.Map<Identifier, Block> BLOCKS = new java.util.LinkedHashMap<>();
                    public static final Block AIR = new Block("air");
                    public static final net.minecraft.core.DefaultedRegistry<Block> BLOCK = new net.minecraft.core.DefaultedRegistry<>() {
                        public Identifier getKey(Block block) {
                            return BLOCKS.entrySet().stream().filter(e -> e.getValue() == block).map(java.util.Map.Entry::getKey)
                                    .findFirst().orElse(Identifier.withDefaultNamespace("air"));
                        }
                        public Block getValue(Identifier id) { return BLOCKS.getOrDefault(id, AIR); }
                    };
                }""");
        sources.put("net/minecraft/util/RandomSource.java", """
                package net.minecraft.util;
                public interface RandomSource {
                    static RandomSource create() { return create(0L); }
                    static RandomSource create(long seed) {
                        java.util.Random random = new java.util.Random(seed);
                        return new RandomSource() {
                            public void setSeed(long next) { random.setSeed(next); }
                            public long nextLong() { return random.nextLong(); }
                        };
                    }
                    void setSeed(long seed);
                    long nextLong();
                }""");
        sources.put("net/minecraftforge/client/model/data/ModelData.java", """
                package net.minecraftforge.client.model.data;
                public final class ModelData {
                    public static final ModelData EMPTY = new ModelData("empty");
                    private final String tag;
                    private ModelData(String tag) { this.tag = tag; }
                    public static ModelData of(String tag) { return new ModelData(tag); }
                    public String tag() { return tag; }
                }""");
        sources.put("net/minecraftforge/client/extensions/IForgeBlockStateModel.java", """
                package net.minecraftforge.client.extensions;
                import java.util.List;
                import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
                import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
                import net.minecraftforge.client.model.data.ModelData;
                public interface IForgeBlockStateModel {
                    default ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                            net.minecraft.world.level.block.state.BlockState state, ModelData data) { return data; }
                    default net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial(ModelData data) {
                        return ((BlockStateModel) this).particleMaterial();
                    }
                    default void collectParts(net.minecraft.util.RandomSource random, List<BlockStateModelPart> parts, ModelData data) {
                        ((BlockStateModel) this).collectParts(random, parts);
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/dispatch/BlockStateModel.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public interface BlockStateModel extends net.minecraftforge.client.extensions.IForgeBlockStateModel {
                    void collectParts(net.minecraft.util.RandomSource random, java.util.List<BlockStateModelPart> parts);
                    net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial();
                    int materialFlags();
                }""");
        sources.put("net/minecraft/client/renderer/block/dispatch/BlockStateModelPart.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public interface BlockStateModelPart {
                    java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.core.Direction side);
                    boolean useAmbientOcclusion();
                    net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial();
                    int materialFlags();
                }""");
        sources.put("net/minecraft/client/resources/model/geometry/QuadCollection.java", """
                package net.minecraft.client.resources.model.geometry;
                import java.util.*;
                import net.minecraft.core.Direction;
                public class QuadCollection {
                    public static final QuadCollection EMPTY = new QuadCollection(new EnumMap<>(Direction.class), List.of());
                    private final Map<Direction, List<BakedQuad>> culled;
                    private final List<BakedQuad> unculled;
                    QuadCollection(Map<Direction, List<BakedQuad>> culled, List<BakedQuad> unculled) { this.culled = culled; this.unculled = unculled; }
                    public List<BakedQuad> getQuads(Direction side) { return side == null ? unculled : culled.getOrDefault(side, List.of()); }
                    public List<BakedQuad> getAll() {
                        List<BakedQuad> all = new ArrayList<>(unculled);
                        culled.values().forEach(all::addAll);
                        return all;
                    }
                    public int materialFlags() { return 0; }
                    public static class Builder {
                        private final Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
                        private final List<BakedQuad> unculled = new ArrayList<>();
                        public Builder addCulledFace(Direction side, BakedQuad quad) {
                            culled.computeIfAbsent(side, s -> new ArrayList<>()).add(quad);
                            return this;
                        }
                        public Builder addUnculledFace(BakedQuad quad) { unculled.add(quad); return this; }
                        public QuadCollection build() { return new QuadCollection(culled, unculled); }
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/SimpleModelWrapper.java", """
                package net.minecraft.client.resources.model;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.client.resources.model.geometry.QuadCollection;
                public record SimpleModelWrapper(QuadCollection quads, boolean useAmbientOcclusion,
                        net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial)
                        implements net.minecraft.client.renderer.block.dispatch.BlockStateModelPart {
                    public java.util.List<BakedQuad> getQuads(net.minecraft.core.Direction side) { return quads.getQuads(side); }
                    public int materialFlags() { return quads.materialFlags(); }
                }""");
        // A vanilla block model: one part whose quads were baked in advance.
        sources.put("testhost/HostBlockModel.java", """
                package testhost;
                import java.util.List;
                import net.minecraft.client.renderer.block.dispatch.*;
                import net.minecraft.client.resources.model.SimpleModelWrapper;
                public final class HostBlockModel implements BlockStateModel {
                    private volatile SimpleModelWrapper part;
                    public HostBlockModel(SimpleModelWrapper part) { this.part = part; }
                    // A model whose quads change between two chunk builds, as a dynamic host model's can.
                    public void takePartOf(HostBlockModel other) { this.part = other.part; }
                    public void collectParts(net.minecraft.util.RandomSource random, List<BlockStateModelPart> parts) { parts.add(part); }
                    public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() { return part.particleMaterial(); }
                    public int materialFlags() { return 0; }
                }""");
        sources.put("net/minecraft/client/renderer/block/BlockStateModelSet.java", """
                package net.minecraft.client.renderer.block;
                import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
                public class BlockStateModelSet {
                    private final java.util.Map<net.minecraft.world.level.block.state.BlockState, BlockStateModel> models;
                    private final BlockStateModel missing;
                    public BlockStateModelSet(java.util.Map<net.minecraft.world.level.block.state.BlockState, BlockStateModel> models, BlockStateModel missing) {
                        this.models = models;
                        this.missing = missing;
                    }
                    public BlockStateModel get(net.minecraft.world.level.block.state.BlockState state) { return models.getOrDefault(state, missing); }
                    public BlockStateModel missingModel() { return missing; }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelBakery.java", """
                package net.minecraft.client.resources.model;
                import java.util.Map;
                import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
                import net.minecraft.client.renderer.item.ItemModel;
                import net.minecraft.resources.Identifier;
                import net.minecraft.world.level.block.state.BlockState;
                public class ModelBakery {
                    public record BakingResult(MissingModels missingModels, Map<BlockState, BlockStateModel> blockStateModels,
                            Map<Identifier, ItemModel> itemStackModels) {}
                    public record MissingModels(BlockStateModel block) {}
                }""");
        sources.put("net/minecraft/client/resources/model/ModelManager.java", """
                package net.minecraft.client.resources.model;
                import net.minecraft.client.renderer.item.ItemModel;
                import net.minecraft.resources.Identifier;
                public class ModelManager {
                    public java.util.Map<Identifier, ItemModel> items = java.util.Map.of();
                    public ItemModel missingItem;
                    public net.minecraft.client.renderer.block.BlockStateModelSet blocks;
                    public ItemModel getItemModel(Identifier id) { return items.getOrDefault(id, missingItem); }
                    public net.minecraft.client.renderer.block.BlockStateModelSet getBlockStateModelSet() { return blocks; }
                }""");
        sources.put("net/minecraftforge/client/event/ModelEvent.java", """
                package net.minecraftforge.client.event;
                import net.minecraft.client.resources.model.ModelBakery;
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
                }""");
        itemSources(sources, split);
        packSources(sources, split);
        sources.put("testhost/World.java", WORLD.formatted(split
                ? "Material.Baked material, Transparency transparency, int tint) { return BakedQuad.MaterialInfo.of(material, transparency, tint, null, 0); }"
                : "Material.Baked material, Transparency transparency, int tint) { return BakedQuad.MaterialInfo.of(material, transparency, tint, true, 0); }"));
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                }""");
        sources.put("net/minecraftforge/fml/ModContainer.java", """
                package net.minecraftforge.fml;
                public class ModContainer {
                    private final String id;
                    private final net.minecraftforge.eventbus.api.bus.BusGroup group;
                    public ModContainer(String id, net.minecraftforge.eventbus.api.bus.BusGroup group) { this.id = id; this.group = group; }
                    public final String getModId() { return id; }
                    public final String getNamespace() { return id; }
                    public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return group; }
                }""");
        sources.put("net/minecraftforge/fml/ModLoadingContext.java", """
                package net.minecraftforge.fml;
                public class ModLoadingContext {
                    public static ModContainer active;
                    public static ModLoadingContext get() { return new ModLoadingContext(); }
                    public String getActiveNamespace() { return active == null ? null : active.getNamespace(); }
                    public ModContainer getContainer() { return active; }
                }""");
        sources.put("net/minecraftforge/fml/loading/FMLPaths.java", """
                package net.minecraftforge.fml.loading;
                public enum FMLPaths {
                    GAMEDIR;
                    public static java.nio.file.Path root;
                    public java.nio.file.Path get() { return root; }
                }""");
        sources.put("net/minecraftforge/fml/ModList.java", """
                package net.minecraftforge.fml;
                public final class ModList {
                    public static final java.util.List<ModContainer> MODS = new java.util.ArrayList<>();
                    public static void forEachModInOrder(java.util.function.Consumer<ModContainer> action) { MODS.forEach(action); }
                }""");
        return sources;
    }

    // Items: the render state, whose layer takes a list on 26.2 and a split on 26.3, and a cuboid item model.
    private static void itemSources(Map<String, String> sources, boolean split) {
        sources.put("it/unimi/dsi/fastutil/ints/IntList.java", """
                package it.unimi.dsi.fastutil.ints;
                public interface IntList extends java.util.List<Integer> { boolean add(int value); int getInt(int index); }""");
        sources.put("it/unimi/dsi/fastutil/ints/IntArrayList.java", """
                package it.unimi.dsi.fastutil.ints;
                public class IntArrayList extends java.util.ArrayList<Integer> implements IntList {
                    public boolean add(int value) { return super.add(value); }
                    public int getInt(int index) { return get(index); }
                }""");
        sources.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                public class Item {
                    private final String name;
                    public Item(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack {
                    private final Item item;
                    private final int count;
                    public ItemStack(Item item) { this(item, 1); }
                    public ItemStack(Item item, int count) { this.item = item; this.count = count; }
                    public Item getItem() { return item; }
                    public int getCount() { return count; }
                    public boolean hasFoil() { return false; }
                }""");
        sources.put("net/minecraft/world/item/ItemDisplayContext.java", """
                package net.minecraft.world.item;
                public enum ItemDisplayContext {
                    NONE, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, HEAD, GUI, GROUND, FIXED, ON_SHELF;
                    public boolean leftHand() { return this == THIRD_PERSON_LEFT_HAND || this == FIRST_PERSON_LEFT_HAND; }
                }""");
        sources.put("net/minecraft/world/entity/ItemOwner.java", """
                package net.minecraft.world.entity;
                public interface ItemOwner { default LivingEntity asLivingEntity() { return null; } }""");
        sources.put("net/minecraft/world/entity/LivingEntity.java", "package net.minecraft.world.entity; public class LivingEntity {}");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel {}");
        sources.put("com/mojang/blaze3d/vertex/PoseStack.java", """
                package com.mojang.blaze3d.vertex;
                public class PoseStack {
                    private final Pose pose = new Pose();
                    public Pose last() { return pose; }
                    public void translate(float x, float y, float z) { pose.matrix.translate(x, y, z); }
                    public static final class Pose {
                        private final org.joml.Matrix4f matrix = new org.joml.Matrix4f();
                        public org.joml.Matrix4f pose() { return matrix; }
                        public void mulPose(org.joml.Matrix4fc other) { matrix.mul(other); }
                    }
                }""");
        // As on the host: a display transform, then the centring, which is all NO_TRANSFORM does.
        sources.put("net/minecraft/client/resources/model/cuboid/ItemTransform.java", """
                package net.minecraft.client.resources.model.cuboid;
                public final class ItemTransform {
                    public static final ItemTransform NO_TRANSFORM = new ItemTransform(0f, 1f);
                    private final float lift;
                    private final float scale;
                    public ItemTransform(float lift, float scale) { this.lift = lift; this.scale = scale; }
                    public void apply(boolean leftHand, com.mojang.blaze3d.vertex.PoseStack.Pose pose) {
                        if (this != NO_TRANSFORM) pose.pose().translate(0f, lift, 0f).scale(scale);
                        pose.pose().translate(-0.5f, -0.5f, -0.5f);
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/ItemTransforms.java", """
                package net.minecraft.client.resources.model.cuboid;
                public final class ItemTransforms {
                    public static final ItemTransforms NO_TRANSFORMS = new ItemTransforms(ItemTransform.NO_TRANSFORM);
                    private final ItemTransform gui;
                    public ItemTransforms(ItemTransform gui) { this.gui = gui; }
                    public ItemTransform getTransform(net.minecraft.world.item.ItemDisplayContext context) {
                        return context == net.minecraft.world.item.ItemDisplayContext.GUI ? gui : ItemTransform.NO_TRANSFORM;
                    }
                }""");
        sources.put("net/minecraft/client/renderer/item/ModelRenderProperties.java", """
                package net.minecraft.client.renderer.item;
                public record ModelRenderProperties(boolean usesBlockLight, net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial,
                        net.minecraft.client.resources.model.cuboid.ItemTransforms transforms) {}""");
        sources.put("net/minecraft/client/renderer/item/ItemModelResolver.java",
                "package net.minecraft.client.renderer.item; public class ItemModelResolver {}");
        sources.put("net/minecraft/client/renderer/item/ItemModel.java", """
                package net.minecraft.client.renderer.item;
                public interface ItemModel {
                    void update(ItemStackRenderState renderState, net.minecraft.world.item.ItemStack stack, ItemModelResolver resolver,
                            net.minecraft.world.item.ItemDisplayContext displayContext, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.world.entity.ItemOwner owner, int seed);
                }""");
        String quadField = split ? "net.minecraft.client.resources.model.geometry.ItemQuads itemQuads"
                : "net.minecraft.client.resources.model.geometry.QuadCollection quads";
        String setQuads = split
                ? "public void setQuads(net.minecraft.client.resources.model.geometry.ItemQuads quads) { this.quads.addAll(quads.all()); }"
                : "public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> prepareQuadList() { return quads; }";
        sources.put("net/minecraft/client/renderer/item/ItemStackRenderState.java", """
                package net.minecraft.client.renderer.item;
                import java.util.*;
                public class ItemStackRenderState {
                    public final List<LayerRenderState> layers = new ArrayList<>();
                    public final List<Object> identity = new ArrayList<>();
                    public boolean animated;
                    public LayerRenderState newLayer() { LayerRenderState layer = new LayerRenderState(); layers.add(layer); return layer; }
                    public void appendModelIdentityElement(Object element) { identity.add(element); }
                    public void setAnimated() { animated = true; }
                    public enum FoilType { NONE, STANDARD, SPECIAL }
                    public class LayerRenderState {
                        public final List<net.minecraft.client.resources.model.geometry.BakedQuad> quads = new ArrayList<>();
                        public final it.unimi.dsi.fastutil.ints.IntArrayList tints = new it.unimi.dsi.fastutil.ints.IntArrayList();
                        public net.minecraft.client.resources.model.cuboid.ItemTransform itemTransform;
                        public org.joml.Matrix4f localTransform = new org.joml.Matrix4f();
                        public boolean usesBlockLight;
                        public void setUsesBlockLight(boolean usesBlockLight) { this.usesBlockLight = usesBlockLight; }
                        public void setExtents(java.util.function.Supplier<org.joml.Vector3fc[]> extents) {}
                        public void setParticleMaterial(net.minecraft.client.resources.model.sprite.Material.Baked material) {}
                        public void setItemTransform(net.minecraft.client.resources.model.cuboid.ItemTransform transform) { itemTransform = transform; }
                        public void setLocalTransform(org.joml.Matrix4fc transform) { localTransform.set(transform); }
                        public void setFoilType(FoilType foil) {}
                        public it.unimi.dsi.fastutil.ints.IntList tintLayers() { return tints; }
                        %s
                    }
                }""".formatted(setQuads));
        if (split) {
            sources.put("net/minecraft/client/resources/model/geometry/ItemQuads.java", """
                    package net.minecraft.client.resources.model.geometry;
                    import java.util.List;
                    public record ItemQuads(List<BakedQuad> all, List<BakedQuad> solid, List<BakedQuad> translucent) {
                        public static ItemQuads split(List<BakedQuad> quads) { return new ItemQuads(List.copyOf(quads), List.of(), List.of()); }
                    }""");
        }
        sources.put("net/minecraft/client/renderer/item/CuboidItemModelWrapper.java", """
                package net.minecraft.client.renderer.item;
                import java.util.List;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                public class CuboidItemModelWrapper implements ItemModel {
                    private final List<Object> tints = List.of();
                    private final %s;
                    private final ModelRenderProperties properties;
                    public CuboidItemModelWrapper(List<BakedQuad> quads, ModelRenderProperties properties) {
                        %s
                        this.properties = properties;
                    }
                    public static org.joml.Vector3fc[] computeExtents(List<BakedQuad> quads) { return new org.joml.Vector3fc[0]; }
                    public void update(ItemStackRenderState renderState, net.minecraft.world.item.ItemStack stack, ItemModelResolver resolver,
                            net.minecraft.world.item.ItemDisplayContext displayContext, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.world.entity.ItemOwner owner, int seed) {
                        testhost.Notes.note("host item model drawn");
                    }
                }""".formatted(quadField, split
                ? "this.itemQuads = net.minecraft.client.resources.model.geometry.ItemQuads.split(quads);"
                : "net.minecraft.client.resources.model.geometry.QuadCollection.Builder builder = new net.minecraft.client.resources.model.geometry.QuadCollection.Builder();"
                        + " quads.forEach(builder::addUnculledFace); this.quads = builder.build();"));
        sources.put("net/minecraft/client/renderer/Sheets.java", """
                package net.minecraft.client.renderer;
                import net.minecraft.client.renderer.rendertype.RenderType;
                public final class Sheets {
                    public static final RenderType CUTOUT_ITEM = new RenderType("cutout_item");
                    public static final RenderType TRANSLUCENT_ITEM = new RenderType("translucent_item");
                    public static final RenderType GLINT = new RenderType("glint");
                    public static final RenderType GLINT_SPECIAL = new RenderType("glint_special");
                    public static RenderType translucentItemSheet() { return TRANSLUCENT_ITEM; }
                }""");
    }

    // The client pack the extra models travel in, and the event that offers it; 26.3 renamed the pack supplier's methods.
    private static void packSources(Map<String, String> sources, boolean split) {
        sources.put("net/minecraftforge/event/AddPackFindersEvent.java", """
                package net.minecraftforge.event;
                import java.util.function.Consumer;
                import net.minecraft.server.packs.repository.RepositorySource;
                public record AddPackFindersEvent(net.minecraft.server.packs.PackType getPackType, Consumer<RepositorySource> sourceAdder)
                        implements net.minecraftforge.eventbus.api.event.RecordEvent {
                    public static final net.minecraftforge.eventbus.api.bus.EventBus<AddPackFindersEvent> BUS =
                            net.minecraftforge.eventbus.api.bus.EventBus.create(AddPackFindersEvent.class);
                    public void addRepositorySource(RepositorySource source) { sourceAdder.accept(source); }
                }""");
        sources.put("net/minecraft/server/packs/PackType.java",
                "package net.minecraft.server.packs; public enum PackType { CLIENT_RESOURCES, SERVER_DATA }");
        sources.put("net/minecraft/network/chat/Component.java", """
                package net.minecraft.network.chat;
                public interface Component {
                    static MutableComponent literal(String text) { return new MutableComponent(text); }
                }""");
        sources.put("net/minecraft/network/chat/MutableComponent.java", """
                package net.minecraft.network.chat;
                public class MutableComponent implements Component {
                    private final String text;
                    public MutableComponent(String text) { this.text = text; }
                    @Override public String toString() { return text; }
                }""");
        sources.put("net/minecraft/server/packs/repository/PackSource.java", """
                package net.minecraft.server.packs.repository;
                public interface PackSource { PackSource BUILT_IN = new PackSource() {}; }""");
        sources.put("net/minecraft/server/packs/PackLocationInfo.java", """
                package net.minecraft.server.packs;
                public record PackLocationInfo(String id, net.minecraft.network.chat.Component title,
                        net.minecraft.server.packs.repository.PackSource source, java.util.Optional<?> knownPackInfo) {}""");
        sources.put("net/minecraft/server/packs/PackSelectionConfig.java", """
                package net.minecraft.server.packs;
                public record PackSelectionConfig(boolean required, net.minecraft.server.packs.repository.Pack.Position defaultPosition,
                        boolean fixedPosition) {}""");
        sources.put("net/minecraft/server/packs/resources/IoSupplier.java", """
                package net.minecraft.server.packs.resources;
                public interface IoSupplier<T> {
                    T get() throws java.io.IOException;
                    static IoSupplier<java.io.InputStream> create(java.nio.file.Path path) { return () -> java.nio.file.Files.newInputStream(path); }
                }""");
        String packFiles = """
                    java.util.Set<String> getNamespaces(PackType type);
                    void listResources(PackType type, String namespace, String path, ResourceOutput output);
                    IoSupplier<InputStream> getResource(PackType type, net.minecraft.resources.Identifier id);
                    interface ResourceOutput extends java.util.function.BiConsumer<net.minecraft.resources.Identifier, IoSupplier<InputStream>> {}
                }""";
        String packMeta = """
                    PackLocationInfo location();
                    IoSupplier<InputStream> getRootResource(String... path);
                    void close();
                """;
        if (split) {
            sources.put("net/minecraft/server/packs/PackMetadataResources.java", """
                    package net.minecraft.server.packs;
                    import java.io.InputStream;
                    import net.minecraft.server.packs.resources.IoSupplier;
                    public interface PackMetadataResources extends AutoCloseable {
                    """ + packMeta + "}");
            sources.put("net/minecraft/server/packs/PackResources.java", """
                    package net.minecraft.server.packs;
                    import java.io.InputStream;
                    import net.minecraft.server.packs.resources.IoSupplier;
                    public interface PackResources extends PackMetadataResources {
                    """ + packFiles);
        } else {
            sources.put("net/minecraft/server/packs/PackResources.java", """
                    package net.minecraft.server.packs;
                    import java.io.InputStream;
                    import net.minecraft.server.packs.resources.IoSupplier;
                    public interface PackResources extends AutoCloseable {
                    """ + packMeta + packFiles);
        }
        // A folder pack that reads its files as the host's does.
        sources.put("net/minecraft/server/packs/PathPackResources.java", """
                package net.minecraft.server.packs;
                import java.io.InputStream;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import net.minecraft.resources.Identifier;
                import net.minecraft.server.packs.resources.IoSupplier;
                public class PathPackResources implements PackResources {
                    private final PackLocationInfo location;
                    public final Path root;
                    public PathPackResources(PackLocationInfo location, Path root) { this.location = location; this.root = root; }
                    public PackLocationInfo location() { return location; }
                    public IoSupplier<InputStream> getRootResource(String... path) {
                        Path file = root.resolve(String.join("/", path));
                        return Files.isRegularFile(file) ? IoSupplier.create(file) : null;
                    }
                    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
                        Path file = root.resolve("assets").resolve(id.getNamespace()).resolve(id.getPath());
                        return Files.isRegularFile(file) ? IoSupplier.create(file) : null;
                    }
                    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
                        Path base = root.resolve("assets").resolve(namespace);
                        if (!Files.isDirectory(base.resolve(path))) return;
                        try (var files = Files.walk(base.resolve(path))) {
                            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                                output.accept(Identifier.fromNamespaceAndPath(namespace, base.relativize(file).toString().replace(java.io.File.separatorChar, '/')),
                                        IoSupplier.create(file));
                            }
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    }
                    public java.util.Set<String> getNamespaces(PackType type) {
                        Path assets = root.resolve("assets");
                        if (!Files.isDirectory(assets)) return java.util.Set.of();
                        try (var folders = Files.list(assets)) {
                            return folders.map(folder -> folder.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    }
                    public void close() {}
                }""");
        sources.put("net/minecraft/server/packs/repository/RepositorySource.java", """
                package net.minecraft.server.packs.repository;
                public interface RepositorySource { void loadPacks(java.util.function.Consumer<Pack> sink); }""");
        // The pack list reads a pack's description when it lists the pack, and opens the pack again for each reload.
        sources.put("net/minecraft/server/packs/repository/Pack.java", """
                package net.minecraft.server.packs.repository;
                import net.minecraft.server.packs.*;
                public class Pack {
                    public final PackLocationInfo location;
                    public final ResourcesSupplier resources;
                    public final PackSelectionConfig selection;
                    private Pack(PackLocationInfo location, ResourcesSupplier resources, PackSelectionConfig selection) {
                        this.location = location;
                        this.resources = resources;
                        this.selection = selection;
                    }
                    public static Pack readMetaAndCreate(PackLocationInfo location, ResourcesSupplier resources, PackType type,
                            PackSelectionConfig selection) {
                        try (var meta = %s) {
                            if (meta.getRootResource("pack.mcmeta") == null) return null;
                        } catch (Exception unreadable) {
                            return null;
                        }
                        return new Pack(location, resources, selection);
                    }
                    public PackResources open() { return %s; }
                    public interface ResourcesSupplier {
                        %s
                    }
                    public record Metadata() {}
                    public enum Position { TOP, BOTTOM }
                }""".formatted(split ? "resources.openMetadata(location)" : "resources.openPrimary(location)",
                split ? "resources.openResources(location, new Metadata()).findFirst().orElseThrow()"
                        : "resources.openFull(location, new Metadata())",
                split ? "PackMetadataResources openMetadata(PackLocationInfo location);\n"
                        + "        java.util.stream.Stream<PackResources> openResources(PackLocationInfo location, Metadata metadata);"
                        : "PackResources openPrimary(PackLocationInfo location);\n"
                        + "        PackResources openFull(PackLocationInfo location, Metadata metadata);"));
        // What the reload's item loader reads from a pack, as text by id.
        sources.put("testhost/PackReads.java", """
                package testhost;
                import java.nio.charset.StandardCharsets;
                import net.minecraft.server.packs.PackResources;
                import net.minecraft.server.packs.PackType;
                public final class PackReads {
                    public static java.util.Map<String, String> items(PackResources pack, String namespace) {
                        java.util.Map<String, String> found = new java.util.TreeMap<>();
                        pack.listResources(PackType.CLIENT_RESOURCES, namespace, "items", (id, supplier) -> {
                            try (var in = supplier.get()) {
                                found.put(id.toString(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                            } catch (java.io.IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        });
                        return found;
                    }
                }""");
        sources.put("net/minecraft/SharedConstants.java", """
                package net.minecraft;
                public class SharedConstants {
                    public static WorldVersion getCurrentVersion() {
                        return type -> new net.minecraft.server.packs.metadata.pack.PackFormat(type == net.minecraft.server.packs.PackType.CLIENT_RESOURCES ? 84 : 101);
                    }
                }""");
        sources.put("net/minecraft/WorldVersion.java", """
                package net.minecraft;
                public interface WorldVersion {
                    net.minecraft.server.packs.metadata.pack.PackFormat packVersion(net.minecraft.server.packs.PackType type);
                }""");
        sources.put("net/minecraft/server/packs/metadata/pack/PackFormat.java", """
                package net.minecraft.server.packs.metadata.pack;
                public record PackFormat(int major) {}""");
    }

    // The blocks, items and vanilla models the tests bake: a lamp with a lit property, a dynamic block and a gem item.
    private static final String WORLD = """
            package testhost;
            import java.util.*;
            import com.mojang.blaze3d.platform.Transparency;
            import net.minecraft.client.renderer.texture.TextureAtlasSprite;
            import net.minecraft.client.resources.model.SimpleModelWrapper;
            import net.minecraft.client.resources.model.geometry.*;
            import net.minecraft.client.resources.model.sprite.Material;
            import net.minecraft.core.Direction;
            import net.minecraft.resources.Identifier;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            public final class World {
                public static final BoolProperty LIT = new BoolProperty("lit");
                public static final Block LAMP = new Block("lamp", LIT);
                public static final Block DYNAMIC = new Block("dynamic");
                public static final Identifier LAMP_ID = Identifier.fromNamespaceAndPath("moda", "lamp");
                public static final Identifier DYNAMIC_ID = Identifier.fromNamespaceAndPath("moda", "dynamic");
                public static final Identifier GEM_ID = Identifier.fromNamespaceAndPath("moda", "gem");
                public static final Identifier WAND_ID = Identifier.fromNamespaceAndPath("moda", "wand");
                public static final net.minecraft.world.item.Item WAND = new net.minecraft.world.item.Item("wand");
                public static final net.minecraft.world.item.Item GEM = new net.minecraft.world.item.Item("gem");
                public static final TextureAtlasSprite GLASS = new TextureAtlasSprite("glass", new Transparency(true, false));
                public static final TextureAtlasSprite STONE = new TextureAtlasSprite("stone", Transparency.NONE);
                static {
                    net.minecraft.core.registries.BuiltInRegistries.BLOCKS.put(LAMP_ID, LAMP);
                    net.minecraft.core.registries.BuiltInRegistries.BLOCKS.put(DYNAMIC_ID, DYNAMIC);
                }
                public static BlockState lampLit() { return state(LAMP, true); }
                public static BlockState lampOff() { return state(LAMP, false); }
                public static BlockState dynamic() { return DYNAMIC.defaultBlockState(); }
                private static BlockState state(Block block, boolean lit) {
                    for (BlockState state : block.getStateDefinition().getPossibleStates()) if (state.getValue(LIT) == lit) return state;
                    throw new IllegalStateException();
                }
                /** An upward face whose tint index tells the tests which call made it. */
                public static BakedQuad quad(Direction side, int tint, TextureAtlasSprite sprite) {
                    org.joml.Vector3f p = new org.joml.Vector3f(0, 1, 0);
                    return new BakedQuad(p, p, p, p, 0L, 0L, 0L, 0L, side == null ? Direction.UP : side, info(new Material.Baked(sprite, false),
                            sprite.transparency(), tint));
                }
                /** A vanilla model with one cutout quad on top, unculled. */
                public static HostBlockModel hostModel(int tint) {
                    QuadCollection quads = new QuadCollection.Builder().addUnculledFace(quad(null, tint, GLASS)).build();
                    return new HostBlockModel(new SimpleModelWrapper(quads, true, new Material.Baked(GLASS, false)));
                }
                private static BakedQuad.MaterialInfo info(%s
            }""";

    /** Forge 1.20.1's model API as old mods saw it, in Minecraft 26.x class names after Retromod's other renames. */
    static Map<String, String> oldApiSources() {
        Map<String, String> sources = new HashMap<>();
        // Forge 1.20.1's Identifier, which its model location extended.
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public class Identifier {
                    protected Identifier(String namespace, String path) {}
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return null; }
                    public String getNamespace() { return null; }
                    public String getPath() { return null; }
                    public Identifier withPrefix(String prefix) { return null; }
                    public String toLanguageKey() { return null; }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelResourceLocation.java", """
                package net.minecraft.client.resources.model;
                public class ModelResourceLocation extends net.minecraft.resources.Identifier {
                    public ModelResourceLocation(net.minecraft.resources.Identifier id, String variant) { super(null, null); }
                    public ModelResourceLocation(String namespace, String path, String variant) { super(null, null); }
                    public String getVariant() { return null; }
                }""");
        sources.put("net/minecraftforge/client/ChunkRenderTypeSet.java", """
                package net.minecraftforge.client;
                public final class ChunkRenderTypeSet {
                    public static ChunkRenderTypeSet of(net.minecraft.client.renderer.rendertype.RenderType... types) { return null; }
                }""");
        sources.put("net/minecraft/client/renderer/block/model/ItemOverrides.java", """
                package net.minecraft.client.renderer.block.model;
                public class ItemOverrides {
                    public static final ItemOverrides EMPTY = null;
                    public ItemOverrides() {}
                    public net.minecraft.client.resources.model.BakedModel resolve(net.minecraft.client.resources.model.BakedModel model,
                            net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.world.entity.LivingEntity entity, int seed) { return null; }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelBakery.java", """
                package net.minecraft.client.resources.model;
                public class ModelBakery {
                    public static final ModelResourceLocation MISSING_MODEL_LOCATION = null;
                }""");
        sources.put("net/minecraft/client/resources/model/BakedModel.java", """
                package net.minecraft.client.resources.model;
                import java.util.List;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.core.Direction;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraftforge.client.model.data.ModelData;
                public interface BakedModel {
                    List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random);
                    boolean useAmbientOcclusion();
                    boolean isGui3d();
                    boolean usesBlockLight();
                    boolean isCustomRenderer();
                    net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon();
                    net.minecraft.client.renderer.block.model.ItemOverrides getOverrides();
                    default List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) { return null; }
                    default net.minecraftforge.client.ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) { return null; }
                    default ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                            BlockState state, ModelData data) { return null; }
                    default BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context, com.mojang.blaze3d.vertex.PoseStack pose,
                            boolean leftHand) { return null; }
                    default List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack stack, boolean fabulous) { return null; }
                }""");
        sources.put("net/minecraftforge/client/model/BakedModelWrapper.java", """
                package net.minecraftforge.client.model;
                public abstract class BakedModelWrapper<T extends net.minecraft.client.resources.model.BakedModel>
                        implements net.minecraft.client.resources.model.BakedModel {
                    protected final T originalModel;
                    public BakedModelWrapper(T originalModel) { this.originalModel = originalModel; }
                    public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(
                            net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side, net.minecraft.util.RandomSource random) { return null; }
                    public boolean useAmbientOcclusion() { return true; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return null; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return null; }
                }""");
        sources.put("net/minecraftforge/client/model/IDynamicBakedModel.java", """
                package net.minecraftforge.client.model;
                public interface IDynamicBakedModel extends net.minecraft.client.resources.model.BakedModel {
                    default java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(
                            net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random) { return null; }
                    java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state,
                            net.minecraft.core.Direction side, net.minecraft.util.RandomSource random, net.minecraftforge.client.model.data.ModelData data,
                            net.minecraft.client.renderer.rendertype.RenderType type);
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
                }""");
        sources.put("net/minecraft/client/renderer/block/BlockModelShaper.java", """
                package net.minecraft.client.renderer.block;
                public class BlockModelShaper {
                    public static net.minecraft.client.resources.model.ModelResourceLocation stateToModelLocation(
                            net.minecraft.world.level.block.state.BlockState state) { return null; }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelManager.java", """
                package net.minecraft.client.resources.model;
                public class ModelManager {
                    public BakedModel getModel(ModelResourceLocation location) { return null; }
                    public BakedModel getModel(net.minecraft.resources.Identifier location) { return null; }
                }""");
        sources.put("net/minecraftforge/eventbus/api/SubscribeEvent.java", """
                package net.minecraftforge.eventbus.api;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface SubscribeEvent {}""");
        return sources;
    }

    /** Two old mods: moda wraps a lamp state and an item, adds a dynamic model and registers an extra model; modb wraps moda's lamp. */
    static Map<String, String> oldModSources() {
        Map<String, String> sources = new HashMap<>();
        sources.put("moda/GlowWrapper.java", WRAPPER.formatted("moda"));
        sources.put("modb/GlowWrapper.java", WRAPPER.formatted("modb"));
        sources.put("moda/DynamicModel.java", """
                package moda;
                import java.util.List;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.renderer.rendertype.RenderTypes;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.core.Direction;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraftforge.client.ChunkRenderTypeSet;
                import net.minecraftforge.client.model.data.ModelData;
                import testhost.World;
                public class DynamicModel implements net.minecraftforge.client.model.IDynamicBakedModel {
                    // Tint index: 1 cutout or 2 translucent, plus 10 with the block entity's data, plus 100 when unculled.
                    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
                        if (side != null && side != Direction.UP) return List.of();
                        int layer = type == RenderTypes.cutoutMovingBlock() ? 1 : type == RenderTypes.translucentMovingBlock() ? 2 : 0;
                        int tint = layer + (data.tag().equals("lit-data") ? 10 : 0) + (side == null ? 100 : 0);
                        return List.of(World.quad(side, tint, World.STONE));
                    }
                    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
                        return ChunkRenderTypeSet.of(RenderTypes.cutoutMovingBlock(), RenderTypes.translucentMovingBlock());
                    }
                    public ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                            BlockState state, ModelData data) { return ModelData.of("lit-data"); }
                    public boolean useAmbientOcclusion() { return false; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return World.STONE; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
                        return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                    }
                }""");
        sources.put("moda/LampModels.java", """
                package moda;
                import java.util.Map;
                import net.minecraft.client.renderer.block.BlockModelShaper;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.client.resources.model.ModelResourceLocation;
                import net.minecraft.resources.Identifier;
                import net.minecraftforge.client.event.ModelEvent;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import testhost.Notes;
                import testhost.World;
                public class LampModels {
                    public static void onModify(ModelEvent.ModifyBakingResult event) {
                        Map<Identifier, BakedModel> models = event.getModels();
                        Identifier lit = BlockModelShaper.stateToModelLocation(World.lampLit());
                        BakedModel original = models.get(lit);
                        Notes.note("A read " + lit + " " + (original != null));
                        models.put(lit, new GlowWrapper(original, "A"));
                        // Every state of the dynamic block, which has none but its default.
                        models.put(new ModelResourceLocation(World.DYNAMIC_ID, ""), new DynamicModel());
                        // A lambda written for Forge 1.20.1's Identifier keys.
                        models.computeIfPresent(new ModelResourceLocation(World.GEM_ID, "inventory"), (id, model) -> new GlowWrapper(model, "A item"));
                        int lamps = 0;
                        for (Identifier id : models.keySet()) if (id.getNamespace().equals("moda") && id.getPath().startsWith("lamp")) lamps++;
                        Notes.note("A lamp keys " + lamps);
                        models.put(new ModelResourceLocation("moda", "ghost", "lit=true"), original);
                        // PuzzlesLib requires the missing model under its old location.
                        Notes.note("A missing " + (models.get(net.minecraft.client.resources.model.ModelBakery.MISSING_MODEL_LOCATION) != null));
                        BakedModel gem = models.get(new ModelResourceLocation(World.GEM_ID, "inventory"));
                        if (gem != null) models.put(new ModelResourceLocation(World.WAND_ID, "inventory"), new SwitchingModel(gem));
                    }
                    public static void onCompleted(ModelEvent.BakingCompleted event) {
                        Map<Identifier, BakedModel> models = event.getModels();
                        BakedModel lamp = models.get(BlockModelShaper.stateToModelLocation(World.lampLit()));
                        Notes.note("A completed sees " + lamp.getClass().getSimpleName());
                        try {
                            models.put(BlockModelShaper.stateToModelLocation(World.lampOff()), lamp);
                            Notes.note("A completed put");
                        } catch (UnsupportedOperationException readOnly) {
                            Notes.note("A completed read-only");
                        }
                    }
                    @SubscribeEvent
                    public static void onRegister(ModelEvent.RegisterAdditional event) {
                        event.register(Identifier.fromNamespaceAndPath("moda", "block/extra"));
                        // Patchouli registers its books as inventory locations, read from the item model folder.
                        ModelResourceLocation book = new ModelResourceLocation(Identifier.fromNamespaceAndPath("moda", "book"), "inventory");
                        event.register(book);
                        // A mod's own item location, registered too, as some mods do for items they also draw by hand.
                        event.register(new ModelResourceLocation(World.GEM_ID, "inventory"));
                        // A block state's location reads its block state file, so it needs no extra model.
                        event.register(new ModelResourceLocation(World.LAMP_ID, "lit=true"));
                        Identifier plain = Identifier.fromNamespaceAndPath("moda", "block/extra");
                        Notes.note("A register " + book.getPath() + " " + book.withPrefix("item/") + " " + book.toLanguageKey() + " "
                                + (plain instanceof ModelResourceLocation) + " " + (((Object) book) instanceof ModelResourceLocation));
                    }
                }""");
        // An item model whose overrides pick another model for a stack of two or more; that model turns the pose itself.
        sources.put("moda/SwitchingModel.java", """
                package moda;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.client.renderer.block.model.ItemOverrides;
                public class SwitchingModel extends net.minecraftforge.client.model.BakedModelWrapper<BakedModel> {
                    private final BakedModel lifted;
                    public SwitchingModel(BakedModel original) {
                        super(original);
                        this.lifted = new LiftWrapper(original);
                    }
                    public ItemOverrides getOverrides() {
                        return new ItemOverrides() {
                            public BakedModel resolve(BakedModel model, net.minecraft.world.item.ItemStack stack,
                                    net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.entity.LivingEntity entity, int seed) {
                                return stack.getCount() > 1 ? lifted : originalModel;
                            }
                        };
                    }
                }""");
        sources.put("moda/LiftWrapper.java", """
                package moda;
                import java.util.List;
                import net.minecraft.client.resources.model.BakedModel;
                public class LiftWrapper extends net.minecraftforge.client.model.BakedModelWrapper<BakedModel> {
                    public LiftWrapper(BakedModel original) { super(original); }
                    public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context, com.mojang.blaze3d.vertex.PoseStack pose,
                            boolean leftHand) {
                        pose.translate(0f, 2f, 0f);
                        return this;
                    }
                    public List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack stack, boolean fabulous) { return List.of(this); }
                }""");
        sources.put("modb/SecondModels.java", """
                package modb;
                import java.util.Map;
                import net.minecraft.client.renderer.block.BlockModelShaper;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.resources.Identifier;
                import net.minecraftforge.client.event.ModelEvent;
                import testhost.World;
                public class SecondModels {
                    public static void onModify(ModelEvent.ModifyBakingResult event) {
                        Map<Identifier, BakedModel> models = event.getModels();
                        Identifier lit = BlockModelShaper.stateToModelLocation(World.lampLit());
                        models.put(lit, new GlowWrapper(models.get(lit), "B"));
                    }
                }""");
        return sources;
    }

    private static final String WRAPPER = """
            package %s;
            import java.util.List;
            import net.minecraft.client.renderer.rendertype.RenderType;
            import net.minecraft.client.resources.model.BakedModel;
            import net.minecraft.client.resources.model.geometry.BakedQuad;
            import net.minecraft.core.Direction;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.client.model.data.ModelData;
            import testhost.Notes;
            public class GlowWrapper extends net.minecraftforge.client.model.BakedModelWrapper<BakedModel> {
                private final String tag;
                public GlowWrapper(BakedModel original, String tag) {
                    super(original);
                    this.tag = tag;
                }
                public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
                    if (side == null) Notes.note(tag + " wrapper quads");
                    return originalModel.getQuads(state, side, random);
                }
                public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
                    if (side == null) Notes.note(tag + " wrapper asked " + type);
                    return originalModel.getQuads(state, side, random, data, type);
                }
                // Forge 1.20.1's wrapper drew the wrapped model's passes; an item wrapper that wants itself drawn says so twice.
                public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context, com.mojang.blaze3d.vertex.PoseStack pose,
                        boolean leftHand) {
                    originalModel.applyTransform(context, pose, leftHand);
                    return this;
                }
                public List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack stack, boolean fabulous) {
                    return List.of(this);
                }
            }""";
}
