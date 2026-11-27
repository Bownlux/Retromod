/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** The old UnbakedModel, ModelBaker and ModelBakery calls, on 26.x's models and on an old mod's own unbaked models. */
public final class LegacyUnbakedModels {

    // An old model's own members, by the descriptors 1.20.1 and 1.21.1 baked with.
    private static final Own BAKE = new Own("bake", MethodType.methodType(BlockStateModel.class, ModelBaker.class, Function.class,
            ModelState.class));
    private static final Own BAKE_AT = new Own("bake", MethodType.methodType(BlockStateModel.class, ModelBaker.class, Function.class,
            ModelState.class, Identifier.class));
    private static final Own DEPENDENCIES = new Own("getDependencies", MethodType.methodType(Collection.class));
    private static final Own RESOLVE_PARENTS = new Own("resolveParents", MethodType.methodType(void.class, Function.class));

    private LegacyUnbakedModels() {}

    // ---- Old calls on an unbaked model -----------------------------------------------------------------------

    /** {@code model.getDependencies()}: the model's own list, or the id a model file stands for. */
    @SuppressWarnings("unchecked")
    public static Collection<Identifier> getDependencies(UnbakedModel model) {
        if (model instanceof Ref ref) return List.of(ref.id);
        MethodHandle own = DEPENDENCIES.of(model);
        if (own == null) return List.of();
        Object result = call(own, model);
        return result instanceof Collection<?> ids ? (Collection<Identifier>) ids : List.of();
    }

    /** {@code model.resolveParents(resolver)}: the model's own, which 26.x does for model files itself. */
    public static void resolveParents(UnbakedModel model, Function<Identifier, UnbakedModel> resolver) {
        MethodHandle own = RESOLVE_PARENTS.of(model);
        if (own != null) call(own, model, resolver);
    }

    /** 1.21.1's {@code model.bake(baker, sprites, state)}. */
    public static BlockStateModel bake(UnbakedModel model, ModelBaker baker, Function<SpriteId, TextureAtlasSprite> sprites,
            ModelState state) {
        return bakeAny(model, baker, sprites, state, null);
    }

    /** 1.20.1's {@code model.bake(baker, sprites, state, location)}. */
    public static BlockStateModel bake(UnbakedModel model, ModelBaker baker, Function<SpriteId, TextureAtlasSprite> sprites,
            ModelState state, Identifier location) {
        return bakeAny(model, baker, sprites, state, location);
    }

    // ---- Old calls on the baker and the bakery ---------------------------------------------------------------

    /** {@code baker.bake(id, state)}: a claimed old model, else the model file. */
    public static BlockStateModel bakeById(ModelBaker baker, Identifier id, ModelState state) {
        LegacyClaimingBaker claiming = claiming(baker);
        UnbakedModel claimed = claiming == null ? null : claiming.claims.claim(id);
        ModelBaker using = claiming != null ? claiming : baker;
        if (claimed != null) return bakeOld(claimed, using, sprites(using, id), state, id);
        return new SingleVariant(SimpleModelWrapper.bake(using, id, state == null ? BlockModelRotation.IDENTITY : state));
    }

    /** {@code baker.getModel(id)}, which answered an unbaked model: a claimed old model, else a stand-in for the file. */
    public static UnbakedModel getModel(ModelBaker baker, Identifier id) {
        LegacyClaimingBaker claiming = claiming(baker);
        return load(claiming == null ? LegacyModelLoading.latestClaims() : claiming.claims, id);
    }

    /** {@code bakery.getModel(id)}; the context hands out no bakery, so the receiver is usually null. */
    public static UnbakedModel getModel(ModelBakery bakery, Identifier id) {
        return load(LegacyModelLoading.latestClaims(), id);
    }

    // ---- Shared pieces ----------------------------------------------------------------------------------------

    /** The model an old loader gets for {@code id}: what an old supplier claimed, else a stand-in that bakes the file. */
    static UnbakedModel load(LegacyModelClaims claims, Identifier id) {
        UnbakedModel claimed = claims == null ? null : claims.claim(id);
        return claimed != null ? claimed : new Ref(id);
    }

    /** Bakes an old mod's model; failures are logged and answered with null, so the caller can fall back. */
    static BlockStateModel bakeOld(UnbakedModel model, ModelBaker baker, Function<SpriteId, TextureAtlasSprite> sprites,
            ModelState state, Identifier id) {
        ModelState settings = state == null ? BlockModelRotation.IDENTITY : state;
        try {
            MethodHandle at = BAKE_AT.of(model);
            if (at != null) return (BlockStateModel) at.invokeExact((Object) model, baker, (Function) sprites, settings, id);
            MethodHandle plain = BAKE.of(model);
            if (plain != null) return (BlockStateModel) plain.invokeExact((Object) model, baker, (Function) sprites, settings);
            return bakeAny(model, baker, sprites, settings, id);
        } catch (Throwable failure) {
            // An error that is not a missing link, such as running out of memory, is not the model's to answer.
            if (failure instanceof Error error && !(failure instanceof LinkageError)) throw error;
            LegacyLog.warnOnce("legacy model bake " + model.getClass().getName(), "The old model " + model.getClass().getName()
                    + (id == null ? "" : " for " + id) + " failed to bake (" + failure + "), so Retromod uses the model file"
                    + " or the missing model there.");
            return null;
        }
    }

    /** Marks what an old model depends on, following claimed models instead of asking 26.x for them. */
    static void markDependencies(UnbakedModel model, ResolvableModel.Resolver resolver, LegacyModelClaims claims, Set<Identifier> seen) {
        Collection<Identifier> dependencies;
        try {
            resolveParents(model, id -> load(claims, id));
            dependencies = getDependencies(model);
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("legacy model dependencies " + model.getClass().getName(), "The old model "
                    + model.getClass().getName() + " could not list its dependencies: " + failure);
            return;
        }
        for (Identifier dependency : dependencies) {
            if (dependency == null || !seen.add(dependency)) continue;
            UnbakedModel claimed = claims == null ? null : claims.claim(dependency);
            if (claimed != null) markDependencies(claimed, resolver, claims, seen);
            else resolver.markDependency(dependency);
        }
    }

    /** The old sprite getter: a texture's sprite from the baker's materials. */
    static Function<SpriteId, TextureAtlasSprite> sprites(ModelBaker baker, Identifier name) {
        String debugName = name == null ? "retromod legacy model" : name.toString();
        ModelDebugName debug = () -> debugName;
        return sprite -> {
            if (sprite == null) return null;
            Object materials = baker.materials();
            // The material baker is an interface on 26.1 and a class from 26.2, so it is called through its runtime class.
            try {
                Material.Baked baked = (Material.Baked) MATERIAL_GET.get(materials.getClass()).invoke(materials, new Material(sprite.texture()), debug);
                return baked == null ? null : baked.sprite();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LegacyLog.warnOnce("legacy model sprite", "Retromod could not read a sprite for an old model: " + failure);
                return null;
            }
        };
    }

    private static final ClassValue<java.lang.reflect.Method> MATERIAL_GET = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Method computeValue(Class<?> type) {
            try {
                java.lang.reflect.Method get = type.getMethod("get", Material.class, ModelDebugName.class);
                // The 26.1 implementation class is not public, so its method is opened for the call.
                get.setAccessible(true);
                return get;
            } catch (NoSuchMethodException | RuntimeException absent) {
                throw new IllegalStateException(type.getName() + " has no get(Material, ModelDebugName)", absent);
            }
        }
    };

    private static BlockStateModel bakeAny(UnbakedModel model, ModelBaker baker, Function<SpriteId, TextureAtlasSprite> sprites,
            ModelState state, Identifier id) {
        LegacyClaimingBaker claiming = claiming(baker);
        ModelBaker using = claiming != null ? claiming : baker;
        if (model instanceof Ref ref) return bakeById(using, ref.id, state);
        if (model instanceof RootModel root) return root.root.bake(root.state, using);
        if (BAKE.of(model) != null || BAKE_AT.of(model) != null) {
            BlockStateModel baked = bakeOld(model, using, sprites != null ? sprites : sprites(using, id), state, id);
            if (baked != null) return baked;
        }
        // A model file 26.x loaded, which old code reached without its id, has nothing to bake from here.
        return new SingleVariant(using.missingBlockModelPart());
    }

    // The baker old code was handed is Retromod's claim-aware one, else one is made from the latest load's claims.
    private static LegacyClaimingBaker claiming(ModelBaker baker) {
        if (baker instanceof LegacyClaimingBaker claiming) return claiming;
        LegacyModelClaims claims = LegacyModelLoading.latestClaims();
        return claims == null || baker == null ? null : new LegacyClaimingBaker(baker, claims);
    }

    private static Object call(MethodHandle own, Object model, Object... arguments) {
        Object[] all = new Object[arguments.length + 1];
        all[0] = model;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        try {
            return own.invokeWithArguments(all);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    /** A model file by id, as old code saw it: 26.x resolves it as the file's own child. */
    static final class Ref implements UnbakedModel {
        final Identifier id;

        Ref(Identifier id) {
            this.id = id;
        }

        @Override
        public Identifier parent() {
            return id;
        }
    }

    /** A block state's top-level model as old modifiers saw it; baking it bakes the 26.x root for that state. */
    static final class RootModel implements UnbakedModel {
        final BlockStateModel.UnbakedRoot root;
        final BlockState state;

        RootModel(BlockStateModel.UnbakedRoot root, BlockState state) {
            this.root = root;
            this.state = state;
        }
    }

    /** One old member, found once per model class on the model's own class. */
    private static final class Own extends ClassValue<Optional<MethodHandle>> {
        private final String name;
        private final MethodType type;

        Own(String name, MethodType type) {
            this.name = name;
            this.type = type;
        }

        MethodHandle of(Object model) {
            return model == null ? null : get(model.getClass()).orElse(null);
        }

        @Override
        protected Optional<MethodHandle> computeValue(Class<?> model) {
            if (model.getName().startsWith("net.minecraft.")) return Optional.empty();
            try {
                MethodHandles.Lookup lookup;
                try {
                    lookup = MethodHandles.privateLookupIn(model, MethodHandles.lookup());
                } catch (IllegalAccessException | RuntimeException elsewhere) {
                    lookup = MethodHandles.publicLookup();
                }
                return Optional.of(lookup.findVirtual(model, name, type).asType(type.insertParameterTypes(0, Object.class)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
                return Optional.empty();
            }
        }
    }
}
