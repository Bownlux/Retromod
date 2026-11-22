/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model;

import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The entity model of Minecraft 1.21.1, which posed itself from an entity, rebuilt on the render-state model. The host
 * still draws the model; this base runs the old posing from the entity the state was made from.
 */
public abstract class LegacyOldEntityModel<E extends Entity> extends EntityModel<EntityRenderState> {

    // The attack swing, under its 1.21.11 to 26.2 name and its 26.3 one.
    private static final MethodHandle ATTACK_ANIM = attackAnim();
    // Classes that drew themselves in an override the host can no longer call, each reported once.
    private static final ClassValue<Boolean> OWN_DRAW = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declaresOwnDraw(type);
        }
    };

    public float attackTime;
    public boolean riding;
    public boolean young = true;

    protected LegacyOldEntityModel() {
        this(new LegacyModelChildren());
    }

    protected LegacyOldEntityModel(Function<Identifier, RenderType> renderType) {
        this(new LegacyModelChildren(), renderType);
    }

    private LegacyOldEntityModel(LegacyModelChildren children) {
        super(new ModelPart(List.of(), children));
        children.owner = this;
    }

    private LegacyOldEntityModel(LegacyModelChildren children, Function<Identifier, RenderType> renderType) {
        super(new ModelPart(List.of(), children), renderType);
        children.owner = this;
    }

    /** The parts the host draws under the stand-in root, in drawing order. */
    abstract void collectParts(Map<String, ModelPart> parts);

    /** The old posing call, which every old model declares. */
    public abstract void setupAnim(E entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
            float headPitch);

    /** The old hook run before posing, which did nothing unless a model overrode it. */
    public void prepareMobModel(E entity, float limbSwing, float limbSwingAmount, float partialTick) {
    }

    /** {@code model.copyPropertiesTo(other)}: the swing, riding and age flags, for an old model that hands them on. */
    public void copyPropertiesTo(EntityModel<?> other) {
        if (!(other instanceof LegacyOldEntityModel<?> old)) return;
        old.attackTime = attackTime;
        old.riding = riding;
        old.young = young;
    }

    /** Poses the model from the entity the host's render state was made from, as the old renderer did before drawing. */
    @Override
    @SuppressWarnings("unchecked")
    public void setupAnim(EntityRenderState state) {
        Entity entity = RenderStateEntities.entity(state);
        // Old posing reads the entity; a state no single entity matches leaves the model as it was last posed.
        if (entity == null) return;
        warnOwnDraw();
        float partialTick = Math.max(0.0F, state.ageInTicks - entity.tickCount);
        float limbSwing = 0.0F;
        float limbSwingAmount = 0.0F;
        float netHeadYaw = 0.0F;
        float headPitch = 0.0F;
        if (state instanceof LivingEntityRenderState living) {
            limbSwing = living.walkAnimationPos;
            limbSwingAmount = living.walkAnimationSpeed;
            netHeadYaw = living.yRot;
            headPitch = living.xRot;
        }
        attackTime = entity instanceof LivingEntity living ? attackAnim(living, partialTick) : 0.0F;
        riding = entity.isPassenger();
        young = entity instanceof LivingEntity living && living.isBaby();
        beforePose();
        E typed = (E) entity;
        prepareMobModel(typed, limbSwing, limbSwingAmount, partialTick);
        setupAnim(typed, limbSwing, limbSwingAmount, state.ageInTicks, netHeadYaw, headPitch);
        afterPose();
    }

    /**
     * The host lists a model's parts once, in its constructor, before an old model has built them; the list is filled
     * here so resetting and walking the model's parts reach them.
     */
    void partsReady() {
        try {
            java.lang.reflect.Field allParts = net.minecraft.client.model.Model.class.getDeclaredField("allParts");
            allParts.setAccessible(true);
            allParts.set(this, root().getAllParts());
        } catch (ReflectiveOperationException | RuntimeException unreachable) {
            LegacyLog.warnOnce("legacy model parts", "Retromod could not list the parts of old entity models for the game ("
                    + unreachable + "), so code that walks a model's parts list skips them. They still draw and animate.");
        }
    }

    /** Run before the old posing; the ageable base puts back what it changed for the last draw. */
    void beforePose() {
    }

    /** Run after the old posing; the ageable base applies its baby scaling there. */
    void afterPose() {
    }

    private void warnOwnDraw() {
        if (!OWN_DRAW.get(getClass())) return;
        LegacyLog.warnOnce("legacy model draw " + getClass().getName(), "The old model " + getClass().getName()
                + " drew itself in its own renderToBuffer. Minecraft draws models itself now, so it is drawn from its"
                + " parts and anything extra that method did is skipped.");
    }

    private static boolean declaresOwnDraw(Class<?> type) {
        for (Class<?> c = type; c != null && c != LegacyOldEntityModel.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
                if (method.getName().equals("legacyRenderToBuffer")) return true;
            }
        }
        return false;
    }

    private static float attackAnim(LivingEntity entity, float partialTick) {
        if (ATTACK_ANIM == null) return 0.0F;
        try {
            return (float) ATTACK_ANIM.invoke(entity, partialTick);
        } catch (Throwable failure) {
            return 0.0F;
        }
    }

    private static MethodHandle attackAnim() {
        MethodType type = MethodType.methodType(float.class, float.class);
        for (String name : new String[]{"getSwingAnimation", "getAttackAnim"}) {
            try {
                return MethodHandles.publicLookup().findVirtual(LivingEntity.class, name, type);
            } catch (ReflectiveOperationException absent) {
                // Try the other name.
            }
        }
        return null;
    }
}
