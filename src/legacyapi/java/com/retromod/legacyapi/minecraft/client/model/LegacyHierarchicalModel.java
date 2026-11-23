/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;

/** Minecraft 1.21.1's HierarchicalModel: one root part, posed by keyframe animations, drawn whole. */
public abstract class LegacyHierarchicalModel<E extends Entity> extends LegacyOldEntityModel<E> {

    // Each animation baked once against this model's root, since baking walks the part tree.
    private final Map<AnimationDefinition, KeyframeAnimation> baked = new IdentityHashMap<>();

    protected LegacyHierarchicalModel() {
    }

    protected LegacyHierarchicalModel(Function<Identifier, RenderType> renderType) {
        super(renderType);
    }

    /** The old {@code root()}, which Retromod renames in the mod, because the host's root() is final. */
    public abstract ModelPart legacyRoot();

    @Override
    void collectParts(Map<String, ModelPart> parts) {
        ModelPart root = legacyRoot();
        if (root != null) parts.put("retromod_legacy_root", root);
    }

    /** {@code getAnyDescendantWithName(name)}: the first part under the root that has a child of that name. */
    public Optional<ModelPart> getAnyDescendantWithName(String name) {
        if (name.equals("root")) return Optional.of(legacyRoot());
        return legacyRoot().getAllParts().stream().filter(part -> part.hasChild(name)).findFirst()
                .map(part -> part.getChild(name));
    }

    /** {@code animate(state, animation, ageInTicks)}. */
    protected void animate(AnimationState state, AnimationDefinition animation, float ageInTicks) {
        animate(state, animation, ageInTicks, 1.0F);
    }

    /** {@code animate(state, animation, ageInTicks, speed)}: plays the animation while the state runs. */
    protected void animate(AnimationState state, AnimationDefinition animation, float ageInTicks, float speed) {
        baked(animation).apply(state, ageInTicks, speed);
    }

    /** {@code animateWalk(animation, walkSpeed, walkScale, maxSpeed, scaleFactor)}: the walk cycle scaled by speed. */
    protected void animateWalk(AnimationDefinition animation, float walkSpeed, float walkScale, float maxSpeed, float scaleFactor) {
        baked(animation).applyWalk(walkSpeed, walkScale, maxSpeed, scaleFactor);
    }

    /** {@code applyStatic(animation)}: the animation's first frame, held. */
    protected void applyStatic(AnimationDefinition animation) {
        baked(animation).applyStatic();
    }

    private KeyframeAnimation baked(AnimationDefinition animation) {
        return baked.computeIfAbsent(animation, definition -> definition.bake(legacyRoot()));
    }
}
