/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/** Minecraft 1.21.1's AgeableListModel: head parts and body parts, each list scaled and moved for a baby. */
public abstract class LegacyAgeableListModel<E extends Entity> extends LegacyOldEntityModel<E> {

    private final boolean scaleHead;
    private final float babyYHeadOffset;
    private final float babyZHeadOffset;
    private final float babyHeadScale;
    private final float babyBodyScale;
    private final float bodyYOffset;
    // The poses the baby scaling replaced, put back before the next posing.
    private final List<ModelPart> changed = new ArrayList<>();
    private final List<PartPose> saved = new ArrayList<>();

    protected LegacyAgeableListModel(boolean scaleHead, float babyYHeadOffset, float babyZHeadOffset) {
        this(scaleHead, babyYHeadOffset, babyZHeadOffset, 2.0F, 2.0F, 24.0F);
    }

    protected LegacyAgeableListModel(boolean scaleHead, float babyYHeadOffset, float babyZHeadOffset, float babyHeadScale,
            float babyBodyScale, float bodyYOffset) {
        this.scaleHead = scaleHead;
        this.babyYHeadOffset = babyYHeadOffset;
        this.babyZHeadOffset = babyZHeadOffset;
        this.babyHeadScale = babyHeadScale;
        this.babyBodyScale = babyBodyScale;
        this.bodyYOffset = bodyYOffset;
    }

    protected LegacyAgeableListModel(Function<Identifier, RenderType> renderType, boolean scaleHead, float babyYHeadOffset,
            float babyZHeadOffset, float babyHeadScale, float babyBodyScale, float bodyYOffset) {
        super(renderType);
        this.scaleHead = scaleHead;
        this.babyYHeadOffset = babyYHeadOffset;
        this.babyZHeadOffset = babyZHeadOffset;
        this.babyHeadScale = babyHeadScale;
        this.babyBodyScale = babyBodyScale;
        this.bodyYOffset = bodyYOffset;
    }

    protected LegacyAgeableListModel() {
        this(false, 5.0F, 2.0F);
    }

    /** The parts drawn with the head scaling. */
    protected abstract Iterable<ModelPart> headParts();

    /** The parts drawn with the body scaling. */
    protected abstract Iterable<ModelPart> bodyParts();

    @Override
    void collectParts(Map<String, ModelPart> parts) {
        int index = 0;
        for (ModelPart part : headParts()) parts.put("retromod_head_" + index++, part);
        index = 0;
        for (ModelPart part : bodyParts()) parts.put("retromod_body_" + index++, part);
    }

    @Override
    void beforePose() {
        for (int i = 0; i < changed.size(); i++) changed.get(i).loadPose(saved.get(i));
        changed.clear();
        saved.clear();
    }

    @Override
    void afterPose() {
        if (!young) return;
        // The old draw scaled the whole head list, then moved it; the same transform is folded into each part's pose.
        float headScale = scaleHead ? 1.5F / babyHeadScale : 1.0F;
        for (ModelPart part : headParts()) fold(part, headScale, babyYHeadOffset, babyZHeadOffset);
        float bodyScale = 1.0F / babyBodyScale;
        for (ModelPart part : bodyParts()) fold(part, bodyScale, bodyYOffset, 0.0F);
    }

    // Scaling by s after moving by (0, dy, dz) sixteenths moves the part's pivot to s * (pivot + offset) and scales it by s.
    private void fold(ModelPart part, float scale, float dy, float dz) {
        changed.add(part);
        saved.add(part.storePose());
        part.x = scale * part.x;
        part.y = scale * (part.y + dy);
        part.z = scale * (part.z + dz);
        part.xScale *= scale;
        part.yScale *= scale;
        part.zScale *= scale;
    }
}
