/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft 1.21.1's BakedModel members, called on what a Fabric or NeoForge mod's old BakedModel became: 26.x's block model. */
public final class LegacyBakedModelCalls {

    private static final Own QUADS = new Own("getQuads", MethodType.methodType(List.class, BlockState.class, Direction.class, RandomSource.class));
    private static final Own AMBIENT = new Own("useAmbientOcclusion", MethodType.methodType(boolean.class));
    private static final Own GUI_3D = new Own("isGui3d", MethodType.methodType(boolean.class));
    private static final Own BLOCK_LIGHT = new Own("usesBlockLight", MethodType.methodType(boolean.class));
    private static final Own CUSTOM = new Own("isCustomRenderer", MethodType.methodType(boolean.class));
    private static final Own PARTICLE = new Own("getParticleIcon", MethodType.methodType(TextureAtlasSprite.class));
    private static final Own TRANSFORMS = new Own("getTransforms", MethodType.methodType(ItemTransforms.class));
    private static final Own OVERRIDES = new Own("getOverrides", MethodType.methodType(LegacyItemOverrides.class));
    private static final Own RENDER_TYPES = new Own("getRenderTypes", MethodType.methodType(List.class, ItemStack.class, boolean.class));
    private static final Own PASSES = new Own("getRenderPasses", MethodType.methodType(List.class, ItemStack.class, boolean.class));
    private static final Own APPLY = new Own("applyTransform", MethodType.methodType(BlockStateModel.class, ItemDisplayContext.class,
            PoseStack.class, boolean.class));
    // Another jar's copy of the item model Retromod hands out, which this copy knows only by its draw.
    private static final Own DRAW = new Own("draw", MethodType.methodType(void.class, ItemStack.class, ItemDisplayContext.class,
            PoseStack.class, Object.class, int.class, int.class));

    private LegacyBakedModelCalls() {}

    // Each member answers from the item model Retromod handed out, else from the mod's own model class when it declares
    // the member, else from Minecraft's block model, as 1.21.1 answered for a block. An old mod's model has no body for
    // Minecraft's members, so one whose own member cannot be called gets a neutral answer instead.

    public static List<BakedQuad> getQuads(BlockStateModel model, BlockState state, Direction side, RandomSource random) {
        if (model instanceof LegacyItemModelHandle item) return item.getQuads(state, side, random);
        MethodHandle own = QUADS.of(model);
        if (own != null) return quads(own, model, state, side, random);
        if (!HOST_PARTS.get(model.getClass())) return List.of();
        return random == null ? LegacyQuadDraws.blockQuads(model, side) : partsQuads(model, side, random);
    }

    public static boolean useAmbientOcclusion(BlockStateModel model) {
        if (model instanceof LegacyItemModelHandle item) return item.useAmbientOcclusion();
        MethodHandle own = AMBIENT.of(model);
        if (own != null) return flag(own, model);
        if (!HOST_PARTS.get(model.getClass())) return true;
        List<BlockStateModelPart> parts = new java.util.ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        return parts.isEmpty() || parts.get(0).useAmbientOcclusion();
    }

    public static boolean isGui3d(BlockStateModel model) {
        if (model instanceof LegacyItemModelHandle item) return item.isGui3d();
        MethodHandle own = GUI_3D.of(model);
        return own == null || flag(own, model);
    }

    public static boolean usesBlockLight(BlockStateModel model) {
        if (model instanceof LegacyItemModelHandle item) return item.usesBlockLight();
        MethodHandle own = BLOCK_LIGHT.of(model);
        return own == null || flag(own, model);
    }

    public static boolean isCustomRenderer(BlockStateModel model) {
        if (model instanceof LegacyItemModelHandle item) return item.isCustomRenderer();
        MethodHandle own = CUSTOM.of(model);
        return own != null && flag(own, model);
    }

    public static TextureAtlasSprite getParticleIcon(BlockStateModel model) {
        if (model instanceof LegacyItemModelHandle item) return item.getParticleIcon();
        MethodHandle own = PARTICLE.of(model);
        if (own != null) return (TextureAtlasSprite) answer(own, model);
        if (!HOST_PARTS.get(model.getClass())) return null;
        Material.Baked particle = model.particleMaterial();
        return particle == null ? null : particle.sprite();
    }

    public static ItemTransforms getTransforms(BlockStateModel model) {
        if (model instanceof LegacyItemModelHandle item) return item.getTransforms();
        MethodHandle own = TRANSFORMS.of(model);
        return own != null ? (ItemTransforms) answer(own, model) : ItemTransforms.NO_TRANSFORMS;
    }

    public static LegacyItemOverrides getOverrides(BlockStateModel model) {
        MethodHandle own = model instanceof LegacyItemModelHandle ? null : OVERRIDES.of(model);
        return own != null ? (LegacyItemOverrides) answer(own, model) : LegacyItemOverrides.EMPTY;
    }

    /** Forge's and NeoForge's applyTransform: the model's display transform for {@code context}, applied to the pose. */
    public static BlockStateModel applyTransform(BlockStateModel model, ItemDisplayContext context, PoseStack pose, boolean leftHand) {
        if (model instanceof LegacyItemModelHandle item) return item.applyTransform(context, pose, leftHand);
        MethodHandle own = APPLY.of(model);
        if (own != null) return apply(own, model, context, pose, leftHand);
        return superApplyTransform(model, context, pose, leftHand);
    }

    /** The default an old model's own applyTransform reaches through {@code super}, which never runs that override again. */
    public static BlockStateModel superApplyTransform(BlockStateModel model, ItemDisplayContext context, PoseStack pose, boolean leftHand) {
        getTransforms(model).getTransform(context).apply(leftHand, pose.last());
        // 26.x's transform also centres the model, which the old renderer did only after this call.
        pose.translate(0.5f, 0.5f, 0.5f);
        return model;
    }

    public static List<BlockStateModel> getRenderPasses(BlockStateModel model, ItemStack stack, boolean fabulous) {
        MethodHandle own = model instanceof LegacyItemModelHandle ? null : PASSES.of(model);
        return own != null ? forStack(own, model, stack, fabulous) : superGetRenderPasses(model, stack, fabulous);
    }

    /** The default getRenderPasses reached through {@code super}: the model itself. */
    public static List<BlockStateModel> superGetRenderPasses(BlockStateModel model, ItemStack stack, boolean fabulous) {
        return List.of(model);
    }

    public static List<RenderType> getRenderTypes(BlockStateModel model, ItemStack stack, boolean fabulous) {
        if (model instanceof LegacyItemModelHandle item) return item.getRenderTypes(stack, fabulous);
        MethodHandle own = RENDER_TYPES.of(model);
        return own != null ? forStack(own, model, stack, fabulous) : superGetRenderTypes(model, stack, fabulous);
    }

    /** The default getRenderTypes reached through {@code super}: the item render types of the model's quads. */
    public static List<RenderType> superGetRenderTypes(BlockStateModel model, ItemStack stack, boolean fabulous) {
        return LegacyQuadDraws.itemRenderTypes(allQuads(model));
    }

    /** Every quad the model draws, each side and the unculled ones, as old item and block draws collected them. */
    public static List<BakedQuad> allQuads(BlockStateModel model) {
        if (model instanceof LegacyItemModelHandle item) return item.getQuads(null, null, null);
        if (QUADS.of(model) == null) return HOST_PARTS.get(model.getClass()) ? LegacyQuadDraws.allBlockQuads(model) : List.of();
        List<BakedQuad> quads = new java.util.ArrayList<>();
        RandomSource random = RandomSource.create(42L);
        for (Direction side : Direction.values()) {
            random.setSeed(42L);
            addAll(quads, getQuads(model, null, side, random));
        }
        random.setSeed(42L);
        addAll(quads, getQuads(model, null, null, random));
        return quads;
    }

    /** Minecraft 1.21.1's ModelBlockRenderer.renderModel: every quad, the tinted ones in the given color. */
    public static void renderModel(ModelBlockRenderer renderer, PoseStack.Pose pose, VertexConsumer out, BlockState state, BlockStateModel model,
            float red, float green, float blue, int light, int overlay) {
        int tint = 0xFF000000 | channel(red) << 16 | channel(green) << 8 | channel(blue);
        for (BakedQuad quad : allQuads(model)) {
            BakedQuad.MaterialInfo info = quad.materialInfo();
            LegacyQuadDraws.put(pose, out, quad, info != null && info.isTinted() ? tint : -1, light, overlay);
        }
    }

    /** Minecraft 1.21.1's ModelBlockRenderer cache switches, which 26.x's renderer has no use for. */
    public static void enableCaching(ModelBlockRenderer renderer) {
    }

    public static void clearCache(ModelBlockRenderer renderer) {
    }

    private static List<BakedQuad> partsQuads(BlockStateModel model, Direction side, RandomSource random) {
        List<BlockStateModelPart> parts = new java.util.ArrayList<>();
        model.collectParts(random, parts);
        List<BakedQuad> quads = new java.util.ArrayList<>();
        for (BlockStateModelPart part : parts) quads.addAll(part.getQuads(side));
        return quads;
    }

    private static void addAll(List<BakedQuad> into, List<BakedQuad> quads) {
        if (quads != null) into.addAll(quads);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, (int) (value * 255f)));
    }

    /** Whether {@code model} is an item model some jar's copy of Retromod handed out; if so it draws {@code stack} and answers true. */
    public static boolean drawItem(Object model, ItemStack stack, ItemDisplayContext context, PoseStack pose, Object buffers, int light,
            int overlay) {
        if (model instanceof LegacyItemModelHandle item) {
            item.draw(stack, context, pose, buffers, light, overlay);
            return true;
        }
        MethodHandle own = DRAW.of(model);
        if (own == null) return false;
        try {
            own.invokeExact(model, stack, context, pose, buffers, light, overlay);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
        return true;
    }

    // Each call shape is invoked exactly, so a model's own member costs no boxing per frame.

    @SuppressWarnings("unchecked")
    private static List<BakedQuad> quads(MethodHandle own, Object model, BlockState state, Direction side, RandomSource random) {
        try {
            return (List<BakedQuad>) own.invokeExact(model, state, side, random);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> forStack(MethodHandle own, Object model, ItemStack stack, boolean fabulous) {
        try {
            return (List<T>) own.invokeExact(model, stack, fabulous);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static BlockStateModel apply(MethodHandle own, Object model, ItemDisplayContext context, PoseStack pose, boolean leftHand) {
        try {
            return (BlockStateModel) own.invokeExact(model, context, pose, leftHand);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    // A no-argument member whose result is an object; the handle was typed to return Object.
    private static Object answer(MethodHandle own, Object model) {
        try {
            return own.invokeExact(model);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static boolean flag(MethodHandle own, Object model) {
        try {
            return (boolean) own.invokeExact(model);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    /** One old member, found once per model class on the mod's own model, typed to take the model as an Object. */
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
            // Minecraft's own models never declared the old members.
            if (model.getName().startsWith("net.minecraft.")) return Optional.empty();
            MethodHandle found = find(model);
            return Optional.ofNullable(found == null ? null : found.asType(erased()));
        }

        // Takes the model as an Object; a list or object result as the exact type each caller casts from.
        private MethodType erased() {
            Class<?> result = type.returnType();
            boolean exact = result.isPrimitive() || result == List.class || result == BlockStateModel.class;
            return type.changeReturnType(exact ? result : Object.class).insertParameterTypes(0, Object.class);
        }

        // Resolves only this one name and descriptor, so a member whose other methods name a class the host removed,
        // as Fusion's wrapped model does, is still found. A failure is kept as no member, with one warning per class.
        private MethodHandle find(Class<?> model) {
            try {
                return lookupFor(model).findVirtual(model, name, type);
            } catch (NoSuchMethodException absent) {
                return null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError closed) {
                LegacyLog.warnOnce("legacy baked model member " + model.getName(), "Retromod could not call the old model members of "
                        + model.getName() + ", so it answers them with neutral values: " + closed);
                return null;
            }
        }
    }

    // Whether a model class has a body for Minecraft's collectParts, as Minecraft's and current mods' models do; an old
    // mod's model only inherits the abstract one, so Minecraft's members are never called on it.
    private static final ClassValue<Boolean> HOST_PARTS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> model) {
            if (model.getName().startsWith("net.minecraft.")) return true;
            try {
                MethodHandles.Lookup lookup = lookupFor(model);
                MethodHandle parts = lookup.findVirtual(model, "collectParts", MethodType.methodType(void.class, RandomSource.class, List.class));
                return !Modifier.isAbstract(lookup.revealDirect(parts).getModifiers());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
                return false;
            }
        }
    };

    // The mod's own jar copy reaches its non-public classes; any other class is reached through its public members.
    private static MethodHandles.Lookup lookupFor(Class<?> model) {
        try {
            return MethodHandles.privateLookupIn(model, MethodHandles.lookup());
        } catch (IllegalAccessException | RuntimeException elsewhere) {
            return MethodHandles.publicLookup();
        }
    }
}
