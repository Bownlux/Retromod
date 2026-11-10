/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The model ItemRenderer.getModel handed out for one stack; Minecraft 26.x resolves that stack when the model is drawn. */
public final class LegacyItemModelHandle implements BlockStateModel {

    private final ItemStack stack;
    private final Level level;
    private final LivingEntity entity;
    private final int seed;
    // The context an old applyTransform picked, which later answers use; null is the GUI, as getModel's inventory model was.
    private final ItemDisplayContext context;
    private LegacyItemStates.Answers answers;

    public LegacyItemModelHandle(ItemStack stack, Level level, LivingEntity entity, int seed) {
        this(stack, level, entity, seed, null);
    }

    /** The model for one stack, typed as the block model old code holds it as. */
    public static BlockStateModel of(ItemStack stack, Level level, LivingEntity entity, int seed) {
        return new LegacyItemModelHandle(stack, level, entity, seed);
    }

    private LegacyItemModelHandle(ItemStack stack, Level level, LivingEntity entity, int seed, ItemDisplayContext context) {
        this.stack = stack;
        this.level = level;
        this.entity = entity;
        this.seed = seed;
        this.context = context;
    }

    /** The stack this model was asked for. */
    public ItemStack stack() {
        return stack;
    }

    /** Draws {@code drawn} in {@code shown} inside the old draw that handed out {@code buffers}, with this model's owner and seed. */
    public void draw(ItemStack drawn, ItemDisplayContext shown, PoseStack pose, Object buffers, int light, int overlay) {
        LegacyItemStackRenderer.draw(entity, drawn, shown, light, overlay, pose, buffers, level, seed);
    }

    // ---- Minecraft 26.x's block model, whose type a Fabric or NeoForge mod's old BakedModel now names ----

    /** An item has no block parts; Minecraft never asks this of a model only old mods hold. */
    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
    }

    @Override
    public Material.Baked particleMaterial() {
        return answers().particle();
    }

    @Override
    public int materialFlags() {
        return 0;
    }

    // ---- Minecraft 1.21.1's BakedModel ----

    /** The stack's quads as one list on the unculled side, which is how old item code adds up every side. */
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        if (side != null) return List.of();
        return LegacyItemStates.quads(stack, level, entity, seed, context);
    }

    public boolean useAmbientOcclusion() {
        return true;
    }

    /** Whether the item draws as a block in the GUI; 26.x keeps that only as its block light, which went together in 1.21.1. */
    public boolean isGui3d() {
        return usesBlockLight();
    }

    public boolean usesBlockLight() {
        return answers().blockLight();
    }

    /** False: Minecraft 26.x draws special items itself, through render(), so old code takes its ordinary path. */
    public boolean isCustomRenderer() {
        return false;
    }

    public TextureAtlasSprite getParticleIcon() {
        Material.Baked particle = answers().particle();
        return particle == null ? null : particle.sprite();
    }

    public ItemTransforms getTransforms() {
        return LegacyItemStates.transforms(stack, level, entity, seed);
    }

    /** Applies the stack's display transform for {@code shown}, as Forge's and NeoForge's applyTransform did, and answers for that context. */
    public LegacyItemModelHandle applyTransform(ItemDisplayContext shown, PoseStack pose, boolean leftHand) {
        ItemTransform transform = LegacyItemStates.transform(stack, level, entity, seed, shown);
        if (transform != null) {
            transform.apply(leftHand, pose.last());
            // 26.x's transform also moves the item to its centre, which the old renderer did only after this call.
            pose.translate(0.5f, 0.5f, 0.5f);
        }
        return shown == context ? this : new LegacyItemModelHandle(stack, level, entity, seed, shown);
    }

    public List<LegacyItemModelHandle> getRenderPasses(ItemStack drawn, boolean fabulous) {
        return List.of(this);
    }

    /** The render types the stack's quads draw with, as the old model listed them for renderModelLists. */
    public List<RenderType> getRenderTypes(ItemStack drawn, boolean fabulous) {
        return LegacyQuadDraws.itemRenderTypes(getQuads(null, null, null));
    }

    /** The tint each tint index takes for the stack, as the old item colors answered. */
    int[] tints() {
        return LegacyItemStates.tints(stack, level, entity, seed, context);
    }

    /** Applies the display transform {@code stack}'s item model has in {@code shown}, centring included, as 26.x applies it. */
    public static void applyStackTransform(ItemStack stack, ItemDisplayContext shown, PoseStack pose, boolean leftHand) {
        ItemTransform transform = LegacyItemStates.transform(stack, null, null, 0, shown);
        (transform != null ? transform : ItemTransform.NO_TRANSFORM).apply(leftHand, pose.last());
    }

    /** The tint each tint index takes for {@code stack} in the GUI; none for an empty stack. */
    public static int[] tintsOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new int[0];
        return LegacyItemStates.tints(stack, null, null, 0, null);
    }

    private LegacyItemStates.Answers answers() {
        LegacyItemStates.Answers read = answers;
        if (read == null) {
            read = LegacyItemStates.answers(stack, level, entity, seed, context);
            answers = read;
        }
        return read;
    }

    @Override
    public String toString() {
        return "LegacyItemModelHandle[" + stack + (context == null ? "" : " " + context) + "]";
    }
}
