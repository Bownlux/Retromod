/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.data.ModelData;

/** Forge 1.20.1's getModels() maps and ModelManager.getModel, over Minecraft 26.2's block state and item models. */
public final class LegacyModelMaps {

    // Each translated jar carries its own relocated classes, so the slots hold only JDK and host types.
    /** The slots' layout; a copy from a Retromod build with another layout keeps slots of its own. */
    public static final int PROTOCOL = 1;
    /** {@code Map<BlockState, BlockStateModel>}: the host model each old model replaced, for its layers. */
    public static final String ORIGINALS = "forge.models.originals";
    /** {@code Map<BlockStateModel, Integer>}: the chunk layer bits of a host model, weakly held. */
    public static final String LAYERS = "forge.models.layers";
    /** {@code Map<Identifier, Identifier>}: each location an old mod registered on its own, and the item model Retromod bakes for it. */
    public static final String ADDITIONAL = "forge.models.additional";
    /** {@code Map<Object, Object>}: each copy's adapter and the host model it replaced, weakly held, so any copy can see past it. */
    public static final String ADAPTERS = "forge.models.adapters";
    /** {@code AtomicReference<BakingResult>}: the latest bake, which BakingCompleted's view and getModel read first. */
    public static final String RESULT = "forge.models.result";
    /** The item model folder Retromod writes models registered on their own under. */
    public static final String EXTRA_FOLDER = "retromod_extra/";

    private LegacyModelMaps() {}

    /** Forge 1.20.1's {@code ModifyBakingResult.getModels()}: a live map whose puts replace the baked models. */
    public static Map<Identifier, BakedModel> getModels(ModelEvent.ModifyBakingResult event) {
        ModelBakery.BakingResult result = event.getResults();
        capture(result);
        return new LiveModels(result.blockStateModels(), result.itemStackModels(), result.missingModels().block(), true);
    }

    /** Forge 1.20.1's {@code BakingCompleted.getModels()}: the same map, read-only, as it was then. */
    public static Map<Identifier, BakedModel> getModels(ModelEvent.BakingCompleted event) {
        ModelBakery.BakingResult result = latest();
        if (result == null) {
            LegacyLog.warnOnce("legacy baking completed " + LegacyModelMaps.class.getName(), "Retromod saw no model"
                    + " bake before BakingCompleted, so an old mod read an empty model map.");
            return Collections.emptyMap();
        }
        return new LiveModels(result.blockStateModels(), result.itemStackModels(), result.missingModels().block(), false);
    }

    /** Forge 1.20.1's {@code ModelManager.getModel(location)}: a block state, an item or a model registered on its own. */
    public static BakedModel getModel(ModelManager manager, Identifier location) {
        ModelBakery.BakingResult result = latest();
        Key key = Key.of(location, result == null ? null : result.itemStackModels());
        BakedModel found = null;
        if (key != null && key.missing()) {
            return getMissingModel(manager);
        } else if (key != null && key.state() != null) {
            BlockStateModel model = result != null ? result.blockStateModels().get(key.state()) : null;
            if (model == null) model = manager.getBlockStateModelSet().get(key.state());
            found = blockView(model);
        } else if (key != null) {
            found = itemView(manager.getItemModel(key.itemId()));
        }
        return found != null ? found : getMissingModel(manager);
    }

    /** Forge 1.20.1's {@code ModelManager.getMissingModel()}: the purple and black cube. */
    public static BakedModel getMissingModel(ModelManager manager) {
        ModelBakery.BakingResult result = latest();
        BlockStateModel missing = result != null ? result.missingModels().block() : manager.getBlockStateModelSet().missingModel();
        return blockView(missing);
    }

    /** Notes the latest bake, for BakingCompleted and getModel, which run after it. */
    public static void capture(ModelBakery.BakingResult result) {
        if (result != null) latestSlot().set(result);
    }

    /** The chunk layer bits of the host model an old model replaced for this state, or 0 when none did. */
    static int originalLayers(BlockState state) {
        Object original = originals().get(state);
        return original instanceof BlockStateModel model ? layersOf(model) : 0;
    }

    /** The chunk layer bits of a host model's quads, as Forge 1.20.1's block layer table answered for it. */
    static int layersOf(BlockStateModel model) {
        Map<Object, Integer> cache = layerCache();
        Integer known = cache.get(model);
        if (known != null) return known;
        List<BlockStateModelPart> parts = new ArrayList<>();
        LegacyBlockStateModelView.forge(model).collectParts(RandomSource.create(42L), parts, ModelData.EMPTY);
        int bits = layersOf(parts);
        cache.put(model, bits);
        return bits;
    }

    static int layersOf(List<BlockStateModelPart> parts) {
        int bits = 0;
        for (BlockStateModelPart part : parts) {
            for (Direction side : Direction.values()) bits |= quadLayers(part.getQuads(side));
            bits |= quadLayers(part.getQuads(null));
        }
        return bits;
    }

    private static int quadLayers(List<BakedQuad> quads) {
        int bits = 0;
        for (BakedQuad quad : quads) {
            ChunkSectionLayer layer = quad.materialInfo() == null ? null : quad.materialInfo().layer();
            if (layer != null) bits |= ChunkRenderTypeSet.ofLayers(layer).bits();
        }
        return bits;
    }

    // ---- views and adapters ----

    /** An old model reading a host block model; this copy's own adapter hands back the old model it holds. */
    public static BakedModel blockView(BlockStateModel model) {
        if (model == null) return null;
        if (model instanceof LegacyBlockStateModel adapter) return adapter.legacy();
        return new LegacyBlockStateModelView(model);
    }

    /** An old model reading a host item model; this copy's own adapter hands back the old model it holds. */
    public static BakedModel itemView(ItemModel model) {
        if (model == null) return null;
        if (model instanceof LegacyItemModel adapter) return adapter.legacy();
        return new LegacyItemModelView(model);
    }

    /** The host block model that draws an old model in place of {@code previous}. */
    public static BlockStateModel blockModel(BakedModel model, BlockState state, BlockStateModel previous) {
        // A view put back unchanged is its host model again, so only a real old model becomes an adapter.
        if (model instanceof LegacyBlockStateModelView view) return view.host();
        // The model Minecraft baked, even when another mod's copy already replaced it.
        BlockStateModel original = adapters().get(previous) instanceof BlockStateModel baked ? baked : previous;
        if (original != null) originals().put(state, original);
        LegacyBlockStateModel adapter = new LegacyBlockStateModel(state, model, original);
        if (original != null) adapters().put(adapter, original);
        return adapter;
    }

    /** The host item model that draws an old model in place of {@code previous}, as {@link #blockModel} does for blocks. */
    public static ItemModel itemModel(BakedModel model, ItemModel previous) {
        if (model instanceof LegacyItemModelView view) return view.host();
        ItemModel fallback = adapters().get(previous) instanceof ItemModel baked ? baked : previous;
        LegacyItemModel adapter = new LegacyItemModel(model, fallback);
        if (fallback != null) adapters().put(adapter, fallback);
        return adapter;
    }

    // ---- the keys ----

    /** What a 1.20.1 key names: one block's states, an item's model, a model registered on its own, or the missing model. */
    private record Key(Block block, String variant, BlockState state, Identifier itemId, boolean missing) {

        static final Key MISSING = new Key(null, null, null, null, true);

        static Key of(Object key, Map<Identifier, ItemModel> items) {
            if (!(key instanceof Identifier location)) return null;
            // A location an old mod registered on its own, with or without a variant, was baked as an item model,
            // unless it is an item's own inventory location, which keeps naming that item's model.
            if (additional().get(location) instanceof Identifier itemId && !ownItem(location, items)) {
                return new Key(null, null, null, itemId, false);
            }
            if (LegacyModelLocations.isMissing(location)) return MISSING;
            if (!LegacyModelLocations.hasVariant(location)) return null;
            String variant = LegacyModelLocations.getVariant(location);
            Identifier id = LegacyModelLocations.id(location);
            if (variant.equals(LegacyModelLocations.INVENTORY)) return new Key(null, null, null, id, false);
            Block block = LegacyModelLocations.blockOf(id);
            BlockState state = block == null ? null : LegacyModelLocations.stateOf(block, variant);
            return state == null ? null : new Key(block, variant, state, null, false);
        }

        private static boolean ownItem(Identifier location, Map<Identifier, ItemModel> items) {
            return items != null && LegacyModelLocations.hasVariant(location)
                    && LegacyModelLocations.getVariant(location).equals(LegacyModelLocations.INVENTORY)
                    && items.containsKey(LegacyModelLocations.id(location));
        }

        List<BlockState> states() {
            // A full variant names one state; a shorter one, such as "", every state it matches.
            if (LegacyModelLocations.variantOf(state).equals(variant)) return List.of(state);
            List<BlockState> states = new ArrayList<>();
            for (BlockState candidate : block.getStateDefinition().getPossibleStates()) {
                if (LegacyModelLocations.matches(candidate, variant)) states.add(candidate);
            }
            return states;
        }
    }

    /** A model an old mod registered on its own: the model file Minecraft loads, and the item model id it is baked under. */
    public record Extra(Identifier model, Identifier item) {}

    /** Notes a location an old mod registered on its own; null for a block state's location, which needs no extra model. */
    public static Extra registerAdditional(Identifier location) {
        Identifier base = LegacyModelLocations.id(location);
        String variant = LegacyModelLocations.getVariant(location);
        Identifier model;
        if (variant.isEmpty() || variant.equals(LegacyModelLocations.STANDALONE)) {
            model = base;
        } else if (variant.equals(LegacyModelLocations.INVENTORY)) {
            // Forge 1.20.1 loaded an inventory location from the item model folder; other variants read a block state.
            model = Identifier.fromNamespaceAndPath(base.getNamespace(), "item/" + base.getPath());
        } else {
            return null;
        }
        // The id comes from the model file, so ns:foo and ns:foo#inventory never share an item model.
        Identifier item = Identifier.fromNamespaceAndPath(model.getNamespace(), EXTRA_FOLDER + model.getPath());
        additional().put(location, item);
        return new Extra(model, item);
    }

    // ---- the live map ----

    /** Forge 1.20.1's model map, read from and written to the bake's own maps; views are kept per map. */
    private static final class LiveModels extends AbstractMap<Identifier, BakedModel> {

        private final Map<BlockState, BlockStateModel> blocks;
        private final Map<Identifier, ItemModel> items;
        private final BlockStateModel missing;
        private final boolean writable;
        private final Map<Object, BakedModel> views = new IdentityHashMap<>();
        private Set<Entry<Identifier, BakedModel>> entries;

        LiveModels(Map<BlockState, BlockStateModel> blocks, Map<Identifier, ItemModel> items, BlockStateModel missing,
                boolean writable) {
            this.blocks = blocks;
            this.items = items;
            this.missing = missing;
            this.writable = writable;
        }

        @Override
        public BakedModel get(Object key) {
            Key named = Key.of(key, items);
            if (named == null) return null;
            if (named.missing()) return view(missing);
            if (named.state() != null) return view(blocks.get(named.state()));
            return view(items.get(named.itemId()));
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public BakedModel put(Identifier key, BakedModel model) {
            if (!writable) throw new UnsupportedOperationException("The model map of BakingCompleted is read-only");
            Key named = Key.of(key, items);
            if (named == null && key != null && !LegacyModelLocations.hasVariant(key)) {
                // A model put under a new plain id is drawn as a model registered on its own.
                registerAdditional(key);
                named = Key.of(key, items);
            }
            if (named == null || model == null || named.missing()) {
                LegacyLog.warnOnce("legacy model put " + key, "An old mod put a model under " + key + ", which names no"
                        + " block state, item or registered model, so Retromod skipped it.");
                return null;
            }
            BakedModel previous = get(key);
            try {
                if (named.state() != null) {
                    for (BlockState state : named.states()) blocks.put(state, blockModel(model, state, blocks.get(state)));
                } else {
                    items.put(named.itemId(), itemModel(model, items.get(named.itemId())));
                }
            } catch (UnsupportedOperationException readOnly) {
                // Minecraft hands a bake of zero or one models over as an unchangeable map.
                LegacyLog.warnOnce("legacy model put read-only " + LegacyModelMaps.class.getName(), "An old mod replaced"
                        + " the model " + key + ", but Minecraft's baked model map could not be changed, so Retromod"
                        + " skipped it.");
                return previous;
            }
            return previous;
        }

        @Override
        public BakedModel remove(Object key) {
            LegacyLog.warnOnce("legacy model remove " + LegacyModelMaps.class.getName(), "An old mod removed the model "
                    + key + "; Minecraft 26.2 needs a model for every block state and item, so Retromod kept it.");
            return null;
        }

        @Override
        public int size() {
            return blocks.size() + items.size() + (missing == null ? 0 : 1);
        }

        @Override
        public Set<Entry<Identifier, BakedModel>> entrySet() {
            if (entries == null) entries = new Entries();
            return entries;
        }

        // The same view for the same host model, so a mod that compares what it read twice sees one model.
        private BakedModel view(Object host) {
            if (host == null) return null;
            synchronized (views) {
                return views.computeIfAbsent(host, model -> model instanceof BlockStateModel block ? blockView(block)
                        : itemView((ItemModel) model));
            }
        }

        /** Every 1.20.1 key: each block state, each item, then each model registered on its own. */
        private List<Identifier> keys() {
            List<Identifier> keys = new ArrayList<>(size());
            for (BlockState state : new ArrayList<>(blocks.keySet())) keys.add(LegacyModelLocations.forState(state));
            Map<Object, Identifier> registered = new HashMap<>();
            additional().forEach((location, item) -> {
                if (location instanceof Identifier key) registered.putIfAbsent(item, key);
            });
            for (Identifier id : new ArrayList<>(items.keySet())) {
                Identifier location = registered.get(id);
                if (location != null) {
                    keys.add(location);
                } else if (!id.getPath().startsWith(EXTRA_FOLDER)) {
                    keys.add(LegacyModelLocations.inventory(id));
                }
            }
            if (missing != null) keys.add(LegacyModelLocations.missingModelLocation());
            return keys;
        }

        private final class Entries extends AbstractSet<Entry<Identifier, BakedModel>> {
            @Override
            public Iterator<Entry<Identifier, BakedModel>> iterator() {
                Iterator<Identifier> keys = keys().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return keys.hasNext();
                    }

                    @Override
                    public Entry<Identifier, BakedModel> next() {
                        Identifier key = keys.next();
                        return new LiveEntry(key);
                    }
                };
            }

            @Override
            public int size() {
                return LiveModels.this.size();
            }
        }

        // An entry reads its model when asked, so iterating keys alone builds no views.
        private final class LiveEntry implements Entry<Identifier, BakedModel> {
            private final Identifier key;

            LiveEntry(Identifier key) {
                this.key = key;
            }

            @Override
            public Identifier getKey() {
                return key;
            }

            @Override
            public BakedModel getValue() {
                return get(key);
            }

            @Override
            public BakedModel setValue(BakedModel value) {
                return put(key, value);
            }
        }
    }

    // ---- shared slots ----

    // Each slot is read on chunk workers and render threads, so it is looked up once per copy, not per call.
    private static volatile Map<Object, Object> originals;
    private static volatile Map<Object, Integer> layerCache;
    private static volatile Map<Object, Object> adapters;
    private static volatile Map<Object, Object> additional;
    private static volatile AtomicReference<Object> latest;

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> originals() {
        Map<Object, Object> slot = originals;
        if (slot == null) originals = slot = LegacySharedSlots.get(ORIGINALS, PROTOCOL, Map.class, ConcurrentHashMap::new);
        return slot;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Integer> layerCache() {
        Map<Object, Integer> slot = layerCache;
        if (slot == null) {
            layerCache = slot = LegacySharedSlots.get(LAYERS, PROTOCOL, Map.class, () -> Collections.synchronizedMap(new WeakHashMap<>()));
        }
        return slot;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> adapters() {
        Map<Object, Object> slot = adapters;
        if (slot == null) {
            adapters = slot = LegacySharedSlots.get(ADAPTERS, PROTOCOL, Map.class, () -> Collections.synchronizedMap(new WeakHashMap<>()));
        }
        return slot;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> additional() {
        Map<Object, Object> slot = additional;
        if (slot == null) additional = slot = LegacySharedSlots.get(ADDITIONAL, PROTOCOL, Map.class, ConcurrentHashMap::new);
        return slot;
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> latestSlot() {
        AtomicReference<Object> slot = latest;
        if (slot == null) latest = slot = LegacySharedSlots.get(RESULT, PROTOCOL, AtomicReference.class, AtomicReference::new);
        return slot;
    }

    private static ModelBakery.BakingResult latest() {
        return latestSlot().get() instanceof ModelBakery.BakingResult result ? result : null;
    }
}
