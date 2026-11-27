/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.forge.client.model.LegacyBlockModelLookups;
import com.retromod.legacyapi.forge.client.model.LegacyModelLocations;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.model.loading.v1.BlockStateResolver;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric API's old model loading on 26.x's: old plugin context calls, old events, and the v0 registry. */
public final class LegacyModelLoading {

    // ExtraModelKeys by model id, shared by every mod's copy, so getModel finds a model any mod added.
    private static final String KEYS = "fabric.model.extraKeys";
    private static final int PROTOCOL = 1;

    // The old events and claims of each 26.x plugin context this copy saw, one per model load.
    private static final Map<ModelLoadingPlugin.Context, Load> LOADS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile LegacyModelClaims latest;

    // v0 registrations, which last for the whole game and join every model load.
    static final List<LegacyModelProviders.ExtraModelProvider> EXTRA_PROVIDERS = new ArrayList<>();
    static final List<Function<ResourceManager, LegacyModelProviders.ModelResourceProvider>> RESOURCE_PROVIDERS = new ArrayList<>();
    static final List<Function<ResourceManager, LegacyModelProviders.ModelVariantProvider>> VARIANT_PROVIDERS = new ArrayList<>();
    private static boolean v0PluginRegistered;

    private LegacyModelLoading() {}

    // ---- Old plugin context calls --------------------------------------------------------------------------------

    /** {@code context.addModels(ids...)}: each id becomes a 26.x extra model under a key {@link #getModel} finds. */
    public static void addModels(ModelLoadingPlugin.Context context, Identifier... ids) {
        if (ids != null) addModels(context, java.util.Arrays.asList(ids));
    }

    /** {@code context.addModels(ids)}. */
    public static void addModels(ModelLoadingPlugin.Context context, Collection<? extends Identifier> ids) {
        if (ids == null) return;
        Load load = load(context);
        for (Identifier id : ids) {
            if (id == null) continue;
            Identifier model = LegacyModelLocations.hasVariant(id) ? LegacyModelLocations.id(id) : id;
            context.addModel(key(id), new LegacyExtraModel(model, load.claims));
        }
    }

    /** {@code context.resolveModel()}. */
    public static Event<LegacyModelResolver> resolveModel(ModelLoadingPlugin.Context context) {
        Load load = load(context);
        load.claims.resolverRegistered();
        return load.claims.resolvers;
    }

    /** {@code context.modifyModelOnLoad()} for mods built before 1.21.4, whose listeners also saw block state models. */
    public static Event<ModelModifier.OnLoad> modifyModelOnLoad(ModelLoadingPlugin.Context context) {
        Load load = load(context);
        load.onLoadAsked = true;
        return load.onLoad;
    }

    /** {@code context.modifyModelBeforeBake()}. */
    public static Event<LegacyBeforeBake> modifyModelBeforeBake(ModelLoadingPlugin.Context context) {
        Load load = load(context);
        load.beforeBakeAsked = true;
        return load.beforeBake;
    }

    /** {@code context.modifyModelAfterBake()}. */
    public static Event<LegacyAfterBake> modifyModelAfterBake(ModelLoadingPlugin.Context context) {
        Load load = load(context);
        load.afterBakeAsked = true;
        return load.afterBake;
    }

    // ---- Old modifier and resolver context calls -------------------------------------------------------------

    /** {@code context.resourceId()}: the model file id, null for a block state's top-level model. */
    public static Identifier resourceId(ModelModifier.OnLoad.Context context) {
        return context instanceof LegacyModelContext legacy ? legacy.resourceId() : context.id();
    }

    /** {@code context.topLevelId()}: a block state's location, null for a model file. */
    public static Identifier topLevelId(ModelModifier.OnLoad.Context context) {
        return context instanceof LegacyModelContext legacy ? legacy.topLevelId() : null;
    }

    /** {@code context.getOrLoadModel(id)}. */
    public static UnbakedModel getOrLoadModel(ModelModifier.OnLoad.Context context, Identifier id) {
        return LegacyUnbakedModels.load(context instanceof LegacyModelContext legacy ? legacy.claims : latest, id);
    }

    /** {@code context.loader()}: no bakery is handed out; old calls on it are answered without one. */
    public static ModelBakery loader(ModelModifier.OnLoad.Context context) {
        return null;
    }

    /** {@code context.setModel(state, model)} with an old unbaked model. */
    public static void setModel(BlockStateResolver.Context context, BlockState state, UnbakedModel model) {
        if (model == null) return;
        context.setModel(state, new LegacyModelRoot(model, LegacyModelLocations.forState(state), claims(), null));
    }

    /** {@code context.getOrLoadModel(id)} in a block state resolver. */
    public static UnbakedModel getOrLoadModel(BlockStateResolver.Context context, Identifier id) {
        return LegacyUnbakedModels.load(claims(), id);
    }

    /** {@code context.loader()} in a block state resolver. */
    public static ModelBakery loader(BlockStateResolver.Context context) {
        return null;
    }

    // ---- Models by id -----------------------------------------------------------------------------------------------

    /** {@code manager.getModel(id)} through FabricBakedModelManager or BakedModelManagerHelper: an added model, else a block state's. */
    public static BlockStateModel getModel(ModelManager manager, Identifier id) {
        Object key = id == null ? null : keys().get(id.toString());
        if (key instanceof ExtraModelKey<?> extra && manager != null) {
            try {
                Object model = ((FabricModelManager) (Object) manager).getModel(extra);
                if (model instanceof BlockStateModel found) return found;
            } catch (RuntimeException | LinkageError notLoaded) {
                // Before the first model load the key has no model yet.
            }
        }
        return LegacyBlockModelLookups.getModel(manager, id);
    }

    /** {@code ((FabricBakedModelManager) manager).getModel(id)}. */
    public static BlockStateModel getModel(FabricModelManager manager, Identifier id) {
        return getModel((ModelManager) (Object) manager, id);
    }

    // ---- The v0 registry --------------------------------------------------------------------------------------------

    static synchronized void registerV0(Runnable registration) {
        registration.run();
        if (v0PluginRegistered) return;
        v0PluginRegistered = true;
        ModelLoadingPlugin.register(LegacyModelLoading::applyV0);
    }

    // Each model load asks the v0 providers again, as the old registry did on every resource reload.
    private static void applyV0(ModelLoadingPlugin.Context context) {
        Load load = load(context);
        ResourceManager manager = LegacyModelClaims.resourceManager();
        List<Identifier> extra = new ArrayList<>();
        synchronized (LegacyModelLoading.class) {
            for (LegacyModelProviders.ExtraModelProvider provider : EXTRA_PROVIDERS) {
                try {
                    provider.provideExtraModels(manager, extra::add);
                } catch (RuntimeException | LinkageError failure) {
                    LegacyModelClaims.warnOnce("An old extra model provider", null, failure);
                }
            }
            load.claims.addProviders(RESOURCE_PROVIDERS, load.claims.resourceProviders);
            load.claims.addProviders(VARIANT_PROVIDERS, load.claims.variantProviders);
        }
        addModels(context, extra);
    }

    // ---- One model load -------------------------------------------------------------------------------------------

    static LegacyModelClaims latestClaims() {
        return latest;
    }

    private static LegacyModelClaims claims() {
        LegacyModelClaims claims = latest;
        return claims != null ? claims : new LegacyModelClaims();
    }

    private static Load load(ModelLoadingPlugin.Context context) {
        synchronized (LOADS) {
            Load load = LOADS.get(context);
            if (load == null) {
                load = new Load();
                LOADS.put(context, load);
                latest = load.claims;
                load.install(context);
            }
            return load;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> keys() {
        return LegacySharedSlots.get(KEYS, PROTOCOL, Map.class, ConcurrentHashMap::new);
    }

    private static ExtraModelKey<BlockStateModel> key(Identifier id) {
        @SuppressWarnings("unchecked")
        ExtraModelKey<BlockStateModel> key = (ExtraModelKey<BlockStateModel>) keys().computeIfAbsent(id.toString(),
                name -> ExtraModelKey.create(() -> name));
        return key;
    }

    /** The old events of one model load, dispatched from the 26.x events of its plugin context. */
    private static final class Load {
        final LegacyModelClaims claims = new LegacyModelClaims();
        final Event<ModelModifier.OnLoad> onLoad = EventFactory.createWithPhases(ModelModifier.OnLoad.class, listeners -> (model, context) -> {
            for (ModelModifier.OnLoad listener : listeners) model = listener.modifyModelOnLoad(model, context);
            return model;
        }, ModelModifier.OVERRIDE_PHASE, ModelModifier.DEFAULT_PHASE, ModelModifier.WRAP_PHASE, ModelModifier.WRAP_LAST_PHASE);
        final Event<LegacyBeforeBake> beforeBake = EventFactory.createWithPhases(LegacyBeforeBake.class, listeners -> (model, context) -> {
            for (LegacyBeforeBake listener : listeners) model = listener.modifyModelBeforeBake(model, context);
            return model;
        }, ModelModifier.OVERRIDE_PHASE, ModelModifier.DEFAULT_PHASE, ModelModifier.WRAP_PHASE, ModelModifier.WRAP_LAST_PHASE);
        final Event<LegacyAfterBake> afterBake = EventFactory.createWithPhases(LegacyAfterBake.class, listeners -> (model, context) -> {
            for (LegacyAfterBake listener : listeners) model = listener.modifyModelAfterBake(model, context);
            return model;
        }, ModelModifier.OVERRIDE_PHASE, ModelModifier.DEFAULT_PHASE, ModelModifier.WRAP_PHASE, ModelModifier.WRAP_LAST_PHASE);
        volatile boolean onLoadAsked;
        volatile boolean beforeBakeAsked;
        volatile boolean afterBakeAsked;

        // The 26.x events this load's old events and claims are served from.
        void install(ModelLoadingPlugin.Context context) {
            context.modifyModelOnLoad().register((model, host) -> onLoadModel(model, host.id()));
            context.modifyBlockModelOnLoad().register((root, host) -> onLoadBlock(root, host.state()));
            context.modifyBlockModelBeforeBake().register((root, host) -> beforeBakeBlock(root, host));
            context.modifyBlockModelAfterBake().register((model, host) -> afterBakeBlock(model, host));
            context.modifyItemModelBeforeBake().register((model, host) -> beforeBakeItem(model, host.itemId()));
        }

        // A model file: old OnLoad listeners see it by resource id, and a claimed id bakes as the old model's quads.
        private UnbakedModel onLoadModel(UnbakedModel model, Identifier id) {
            if (onLoadAsked) {
                UnbakedModel changed = guard(() -> onLoad.invoker().modifyModelOnLoad(model, new LegacyModelContext(claims, id, null)),
                        model, "An old model loader", id);
                if (changed != null && changed != model) {
                    claims.claimAs(id, changed);
                    return LegacyClaimedModel.unbaked(changed, id, claims);
                }
            }
            UnbakedModel claimed = claims.claimsAnything() ? claims.claim(id) : null;
            return claimed != null ? LegacyClaimedModel.unbaked(claimed, id, claims) : model;
        }

        // A block state: v0 variant providers and old OnLoad listeners see its top-level location.
        private BlockStateModel.UnbakedRoot onLoadBlock(BlockStateModel.UnbakedRoot root, BlockState state) {
            BlockStateModel.UnbakedRoot result = root;
            if (!claims.variantProviders.isEmpty() || onLoadAsked) {
                Identifier location = LegacyModelLocations.forState(state);
                UnbakedModel variant = claims.variantProviders.isEmpty() ? null : claims.variant(location);
                if (variant != null) result = new LegacyModelRoot(variant, location, claims, root);
                if (onLoadAsked) {
                    UnbakedModel placeholder = new LegacyUnbakedModels.RootModel(result, state);
                    UnbakedModel changed = guard(() -> onLoad.invoker().modifyModelOnLoad(placeholder,
                            new LegacyModelContext(claims, null, location)), placeholder, "An old model loader", location);
                    if (changed != null && changed != placeholder) result = new LegacyModelRoot(changed, location, claims, result);
                }
            }
            // Only a root that depends on a claimed id needs the claim-aware wrapper, which bakes without the host's cache.
            return claims.claimsAnything() && claims.claimsAny(result) ? new LegacyResolvingRoot(result, claims) : result;
        }

        private BlockStateModel.UnbakedRoot beforeBakeBlock(BlockStateModel.UnbakedRoot root, ModelModifier.BeforeBakeBlock.Context host) {
            if (!beforeBakeAsked) return root;
            Identifier location = LegacyModelLocations.forState(host.state());
            UnbakedModel placeholder = new LegacyUnbakedModels.RootModel(root, host.state());
            UnbakedModel changed = guard(() -> beforeBake.invoker().modifyModelBeforeBake(placeholder,
                    new LegacyModelContext(claims, null, location, null, host.baker())), placeholder, "An old before bake modifier", location);
            return changed != null && changed != placeholder ? new LegacyModelRoot(changed, location, claims, root) : root;
        }

        private BlockStateModel afterBakeBlock(BlockStateModel model, ModelModifier.AfterBakeBlock.Context host) {
            if (!afterBakeAsked) return model;
            Identifier location = LegacyModelLocations.forState(host.state());
            LegacyModelContext context = new LegacyModelContext(claims, null, location,
                    new LegacyUnbakedModels.RootModel(host.sourceModel(), host.state()), host.baker());
            BlockStateModel changed = guard(() -> afterBake.invoker().modifyModelAfterBake(model, context), model,
                    "An old after bake modifier", location);
            return changed != null ? changed : model;
        }

        // An item whose model id an old supplier claimed draws the old model through Retromod's item model.
        private ItemModel.Unbaked beforeBakeItem(ItemModel.Unbaked model, Identifier itemId) {
            if (!(model instanceof CuboidItemModelWrapper.Unbaked cuboid)) return model;
            UnbakedModel old = claims.variantProviders.isEmpty() ? null : claims.variant(LegacyModelLocations.inventory(itemId));
            Identifier id = cuboid.model();
            if (old == null && claims.claimsAnything()) old = claims.claim(id);
            return old == null ? model : new LegacyItemModels.Unbaked(old, id, claims, model);
        }
    }

    // Old listener code that throws leaves the model as it was, with one log line per kind of failure, not per model.
    private static <T> T guard(java.util.function.Supplier<T> call, T fallback, String kind, Object model) {
        try {
            return call.get();
        } catch (RuntimeException | LinkageError failure) {
            LegacyModelClaims.warnOnce(kind, model, failure);
            return fallback;
        }
    }
}
