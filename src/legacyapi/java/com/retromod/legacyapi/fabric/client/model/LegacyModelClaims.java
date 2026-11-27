/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** One model load's old model suppliers, and the models they claimed by id, which 26.x baking asks about. */
final class LegacyModelClaims {

    /** Old {@code ModelResolver}s registered for this load. */
    final Event<LegacyModelResolver> resolvers = EventFactory.createArrayBacked(LegacyModelResolver.class, listeners -> context -> {
        for (LegacyModelResolver listener : listeners) {
            UnbakedModel model = listener.resolveModel(context);
            if (model != null) return model;
        }
        return null;
    });

    /** v0 resource providers, each already made from the resource manager. */
    final List<LegacyModelProviders.ModelResourceProvider> resourceProviders = new ArrayList<>();
    /** v0 variant providers, each already made from the resource manager. */
    final List<LegacyModelProviders.ModelVariantProvider> variantProviders = new ArrayList<>();

    // Claims by model id; an empty value is an id nothing claimed.
    private final Map<Identifier, Optional<UnbakedModel>> claims = new HashMap<>();
    // The static quads Retromod baked for a claimed model, and the old model they came from.
    private final Map<QuadCollection, BlockStateModel> bakedClaims = Collections.synchronizedMap(new IdentityHashMap<>());

    private boolean hasResolvers;

    /** Whether anything here can claim an id, so block models need the claim-aware wrapper. */
    synchronized boolean claimsAnything() {
        return hasResolvers || !resourceProviders.isEmpty() || claims.values().stream().anyMatch(Optional::isPresent);
    }

    /** Records that a resolver was registered, so the next check knows block models need the claim-aware wrapper. */
    synchronized void resolverRegistered() {
        hasResolvers = true;
    }

    /** The old model claimed for {@code id}, asking each supplier once, or null. */
    synchronized UnbakedModel claim(Identifier id) {
        if (id == null) return null;
        Optional<UnbakedModel> known = claims.get(id);
        if (known != null) return known.orElse(null);
        // Recorded empty first, so a supplier that loads the same id while answering gets the model file.
        claims.put(id, Optional.empty());
        UnbakedModel found = ask(id);
        claims.put(id, Optional.ofNullable(found));
        return found;
    }

    /** Records a model an old modifier put in place of a model file. */
    synchronized void claimAs(Identifier id, UnbakedModel model) {
        claims.put(id, Optional.ofNullable(model));
    }

    // One resolved view per claimed id, so each id's old model bakes once per model state.
    private final Map<Identifier, LegacyClaimedModel> resolved = new HashMap<>();

    /** The 26.x view of the model claimed for {@code id}, made once per load. */
    synchronized LegacyClaimedModel resolved(Identifier id, UnbakedModel claimed) {
        return resolved.computeIfAbsent(id, unused -> new LegacyClaimedModel(claimed, id, this));
    }

    /** Whether any model id {@code root} depends on is claimed, which only a claim-aware wrapper can bake. */
    boolean claimsAny(net.minecraft.client.renderer.block.dispatch.BlockStateModel.UnbakedRoot root) {
        boolean[] found = new boolean[1];
        root.resolveDependencies(id -> found[0] |= claim(id) != null);
        return found[0];
    }

    /** The old model whose static quads 26.x baked as {@code quads}, or null. */
    BlockStateModel bakedFrom(QuadCollection quads) {
        return quads == null ? null : bakedClaims.get(quads);
    }

    void recordBake(QuadCollection quads, BlockStateModel model) {
        bakedClaims.put(quads, model);
    }

    /** The first non-null answer of a v0 variant provider for a block state or inventory location. */
    synchronized UnbakedModel variant(Identifier location) {
        LegacyModelContext context = new LegacyModelContext(this, null, location);
        for (LegacyModelProviders.ModelVariantProvider provider : variantProviders) {
            try {
                UnbakedModel model = provider.loadModelVariant(location, context);
                if (model != null) return model;
            } catch (Exception | LinkageError failure) {
                warnOnce("An old model variant provider", location, failure);
            }
        }
        return null;
    }

    /** The resource manager v0 providers are made from. */
    static ResourceManager resourceManager() {
        try {
            return net.minecraft.client.Minecraft.getInstance().getResourceManager();
        } catch (RuntimeException | LinkageError none) {
            return null;
        }
    }

    /** Makes each v0 provider factory's provider for this load. */
    <T> void addProviders(List<Function<ResourceManager, T>> factories, List<T> into) {
        ResourceManager manager = resourceManager();
        for (Function<ResourceManager, T> factory : factories) {
            try {
                T provider = factory.apply(manager);
                if (provider != null) into.add(provider);
            } catch (RuntimeException | LinkageError failure) {
                warnOnce("An old model provider factory", null, failure);
            }
        }
    }

    private UnbakedModel ask(Identifier id) {
        LegacyModelContext context = new LegacyModelContext(this, id, null);
        try {
            UnbakedModel model = resolvers.invoker().resolveModel(context);
            if (model != null) return model;
        } catch (RuntimeException | LinkageError failure) {
            warnOnce("An old model resolver", id, failure);
        }
        for (LegacyModelProviders.ModelResourceProvider provider : resourceProviders) {
            try {
                UnbakedModel model = provider.loadModelResource(id, context);
                if (model != null) return model;
            } catch (Exception | LinkageError failure) {
                warnOnce("An old model resource provider", id, failure);
            }
        }
        return null;
    }

    /** One log line per kind of old code, failure and the class it failed in, however many models it fails for. */
    static void warnOnce(String kind, Object model, Throwable failure) {
        StackTraceElement[] trace = failure.getStackTrace();
        String origin = trace.length == 0 ? "" : trace[0].getClassName();
        LegacyLog.warnOnce("legacy model loading " + kind + " " + failure.getClass().getName() + " " + origin, kind + " failed"
                + (model == null ? "" : " for " + model + " and possibly others") + ", so Retromod kept the model as it was: "
                + failure);
    }
}
