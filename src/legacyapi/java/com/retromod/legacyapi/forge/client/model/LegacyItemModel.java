/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.color.LegacyItemColorTable;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntBiFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/** Minecraft 26.2's item model for one item, drawn by an old mod's BakedModel as Forge 1.20.1's item renderer drew it. */
public final class LegacyItemModel implements ItemModel {

    private static final Direction[] SIDES = Direction.values();
    // Forge 1.20.1's item renderer seeded every side's random with 42.
    private static final long ITEM_SEED = 42L;
    private static final int FLAG_ANIMATED = 2;

    // The old item colors every copy shares, by item; read for every tinted item drawn, so looked up once.
    private static volatile Map<Object, ToIntBiFunction<Object, Integer>> itemColors;

    private final BakedModel model;
    // The host model this one replaced, drawn when the old model has nothing Retromod can draw.
    private final ItemModel fallback;

    public LegacyItemModel(BakedModel model, ItemModel fallback) {
        this.model = model;
        this.fallback = fallback;
    }

    /** The old mod's model. */
    public BakedModel legacy() {
        return model;
    }

    /** The host model this one replaced, or null. */
    public ItemModel fallback() {
        return fallback;
    }

    @Override
    public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
        List<Pass> passes;
        try {
            passes = passes(stack, displayContext, level, owner, seed);
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("legacy item model " + model.getClass().getName(), "An old item model of "
                    + model.getClass().getName() + " threw while Minecraft drew an item, so Retromod drew the item's own"
                    + " model in its place: " + failure);
            passes = List.of();
        }
        // An old model that only wraps an item model Retromod cannot read, such as a select model, draws that one.
        if (passes.isEmpty()) {
            if (fallback != null) fallback.update(renderState, stack, resolver, displayContext, level, owner, seed);
            return;
        }
        renderState.appendModelIdentityElement(this);
        for (Pass pass : passes) draw(renderState, stack, pass);
    }

    /** {@code T(0.5) * pose * T(-0.5)}: the host's own leading {@code T(-0.5)} then leaves Forge 1.20.1's {@code pose * T(-0.5)}. */
    static Matrix4f localTransform(PoseStack pose) {
        return new Matrix4f().translation(0.5f, 0.5f, 0.5f).mul(pose.last().pose()).translate(-0.5f, -0.5f, -0.5f);
    }

    // The model the overrides pick, its display transform, and each render pass's quads.
    private List<Pass> passes(ItemStack stack, ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed) {
        LivingEntity entity = owner == null ? null : owner.asLivingEntity();
        BakedModel resolved = model.getOverrides().resolve(model, stack, level, entity, seed);
        if (resolved == null) resolved = model;
        PoseStack pose = new PoseStack();
        BakedModel drawn = resolved.applyTransform(context, pose, context.leftHand());
        if (drawn == null) drawn = resolved;
        // A model drawn by an item renderer has no quads of its own; the item's own model stands in for it.
        if (drawn.isCustomRenderer()) return List.of();
        List<Pass> passes = new ArrayList<>();
        for (BakedModel pass : drawn.getRenderPasses(stack, true)) {
            List<BakedQuad> quads = quads(pass);
            if (!quads.isEmpty()) passes.add(new Pass(pass, quads, pose));
        }
        return passes;
    }

    private static List<BakedQuad> quads(BakedModel pass) {
        RandomSource random = RandomSource.create(ITEM_SEED);
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction side : SIDES) {
            random.setSeed(ITEM_SEED);
            add(quads, pass.getQuads(null, side, random));
        }
        random.setSeed(ITEM_SEED);
        add(quads, pass.getQuads(null, null, random));
        return quads;
    }

    private static void add(List<BakedQuad> into, List<BakedQuad> quads) {
        if (quads != null) into.addAll(LegacyQuadVertices.sync(quads));
    }

    private void draw(ItemStackRenderState renderState, ItemStack stack, Pass pass) {
        ItemStackRenderState.LayerRenderState layer = renderState.newLayer();
        // The GUI caches each item's picture by these elements, so each pass, foil and tint value is one of them.
        renderState.appendModelIdentityElement(pass.model);
        if (stack.hasFoil()) {
            layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
            renderState.setAnimated();
            renderState.appendModelIdentityElement(ItemStackRenderState.FoilType.STANDARD);
        }
        layer.setUsesBlockLight(pass.model.usesBlockLight());
        TextureAtlasSprite particle = pass.model.getParticleIcon();
        if (particle != null) layer.setParticleMaterial(new Material.Baked(particle, false));
        // The old applyTransform already turned the pose, so the host applies only its own centring.
        layer.setItemTransform(ItemTransform.NO_TRANSFORM);
        layer.setLocalTransform(localTransform(pass.pose));
        List<BakedQuad> quads = pass.quads;
        layer.setExtents(() -> CuboidItemModelWrapper.computeExtents(quads));
        addTints(renderState, layer, stack, quads);
        if (animated(quads)) renderState.setAnimated();
        LegacyItemQuads.give(layer, quads);
    }

    // Forge 1.20.1 colored each tinted quad with the item's old color for that tint index.
    private static void addTints(ItemStackRenderState renderState, ItemStackRenderState.LayerRenderState layer, ItemStack stack,
            List<BakedQuad> quads) {
        int highest = -1;
        for (BakedQuad quad : quads) highest = Math.max(highest, LegacyQuadVertices.getTintIndex(quad));
        if (highest < 0) return;
        ToIntBiFunction<Object, Integer> color = itemColors().get(stack.getItem());
        for (int index = 0; index <= highest; index++) {
            int value = tint(color, stack, index);
            layer.tintLayers().add(value);
            renderState.appendModelIdentityElement(value);
        }
    }

    // A quad with an animated texture keeps the GUI from caching the item's picture.
    private static boolean animated(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            if (quad.materialInfo() != null && (quad.materialInfo().flags() & FLAG_ANIMATED) != 0) return true;
        }
        return false;
    }

    private static int tint(ToIntBiFunction<Object, Integer> color, ItemStack stack, int index) {
        if (color == null) return -1;
        try {
            // The shared table answers the color drawn, made opaque for a mod built before 1.21.
            return color.applyAsInt(stack, index);
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("item color " + color, "The old item color handler " + color + " threw " + failure
                    + ", so Retromod draws its tint untinted");
            return -1;
        }
    }

    private static Map<Object, ToIntBiFunction<Object, Integer>> itemColors() {
        Map<Object, ToIntBiFunction<Object, Integer>> table = itemColors;
        if (table == null) {
            table = LegacyItemColorTable.table();
            itemColors = table;
        }
        return table;
    }

    @Override
    public String toString() {
        return "LegacyItemModel[" + model + "]";
    }

    private record Pass(BakedModel model, List<BakedQuad> quads, PoseStack pose) {}
}
