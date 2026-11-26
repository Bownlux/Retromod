/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.retromod.legacyapi.minecraft.LegacyOldOverrides;
import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import java.lang.reflect.Method;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/** Forge 1.20.1's entity-taking armor model hooks on Forge 26.2 and newer, which take render states, answered by the old overrides. */
public final class LegacyForgeArmorHooks {

    private static final String HUMANOID = "getHumanoidArmorModel", GENERIC = "getGenericArmorModel";
    private static final Class<?>[] OLD_MODEL = {LivingEntity.class, ItemStack.class, EquipmentSlot.class, HumanoidModel.class};

    private LegacyForgeArmorHooks() {}

    // ---- calls an old mod makes ----

    /** {@code ext.getHumanoidArmorModel(entity, stack, slot, original)}: the old override, else the model it was given, the old default. */
    public static HumanoidModel<?> getHumanoidArmorModel(IClientItemExtensions ext, LivingEntity entity, ItemStack stack, EquipmentSlot slot,
            HumanoidModel<?> original) {
        Method own = ext == null ? null : LegacyOldOverrides.find(ext.getClass(), HUMANOID, OLD_MODEL);
        Object answer = own == null ? null : LegacyOldOverrides.call(ext, own, entity, stack, slot, original);
        return answer instanceof HumanoidModel<?> model ? model : original;
    }

    /** The same call through {@code super}, from the override itself. */
    public static HumanoidModel<?> getHumanoidArmorModelSuper(IClientItemExtensions ext, LivingEntity entity, ItemStack stack,
            EquipmentSlot slot, HumanoidModel<?> original) {
        return original;
    }

    /** {@code ext.getGenericArmorModel(entity, stack, slot, original)}: the old override, else the old default. */
    public static Model getGenericArmorModel(IClientItemExtensions ext, LivingEntity entity, ItemStack stack, EquipmentSlot slot,
            HumanoidModel<?> original) {
        Method own = ext == null ? null : LegacyOldOverrides.find(ext.getClass(), GENERIC, OLD_MODEL);
        if (own == null) return getGenericArmorModelSuper(ext, entity, stack, slot, original);
        Object answer = LegacyOldOverrides.call(ext, own, entity, stack, slot, original);
        return answer instanceof Model model ? model : original;
    }

    /** The old default of the generic hook, which asked the humanoid hook. */
    public static Model getGenericArmorModelSuper(IClientItemExtensions ext, LivingEntity entity, ItemStack stack, EquipmentSlot slot,
            HumanoidModel<?> original) {
        return getHumanoidArmorModel(ext, entity, stack, slot, original);
    }

    /** Forge 1.20.1's {@code ForgeHooksClient.getArmorModel(entity, stack, slot, original)}: the item extension's generic hook. */
    public static Model getArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel<?> original) {
        return getGenericArmorModel(stack == null ? null : IClientItemExtensions.of(stack), entity, stack, slot, original);
    }

    // ---- the host's hooks, for an old extension; null tells the added hook to ask the inherited one ----

    /** Forge 26.2's {@code getHumanoidArmorModel(state, stack, slot, original)}: the old override, with the entity the state was made from. */
    public static HumanoidModel<?> humanoidModel(IClientItemExtensions ext, LivingEntityRenderState state, ItemStack stack, EquipmentSlot slot,
            HumanoidModel<?> original) {
        return replacement(ext, HUMANOID, state, stack, slot, original);
    }

    /** Forge 26.2's {@code getGenericArmorModel(state, stack, slot, original)}: the old override's humanoid model. */
    public static Model genericModel(IClientItemExtensions ext, HumanoidRenderState state, ItemStack stack, EquipmentSlot slot,
            HumanoidModel<?> original) {
        return replacement(ext, GENERIC, state, stack, slot, original);
    }

    // The old override's model for the entity behind the state, posed like the model it replaces, or null.
    private static HumanoidModel<?> replacement(IClientItemExtensions ext, String hook, LivingEntityRenderState state, ItemStack stack,
            EquipmentSlot slot, HumanoidModel<?> original) {
        Method own = ext == null ? null : LegacyOldOverrides.find(ext.getClass(), hook, OLD_MODEL);
        if (own == null) return null;
        // A state no single entity matches, such as two mobs in one block or a menu preview, skips the old hook this frame.
        LivingEntity entity = RenderStateEntities.livingEntity(state);
        if (entity == null) return null;
        Object answer = LegacyOldOverrides.call(ext, own, entity, stack, slot, original);
        // An old plain model drew itself through a method 26.x renamed, so only a humanoid model draws here.
        if (!(answer instanceof HumanoidModel<?> model) || answer == original) return null;
        if (original != null) copyPose(original, model);
        return model;
    }

    /** Gives the replacement each body part's pose and visibility, as Forge 1.20.1 did before it drew a replacement. */
    private static void copyPose(HumanoidModel<?> from, HumanoidModel<?> to) {
        copyPart(from.head, to.head);
        copyPart(from.hat, to.hat);
        copyPart(from.body, to.body);
        copyPart(from.rightArm, to.rightArm);
        copyPart(from.leftArm, to.leftArm);
        copyPart(from.rightLeg, to.rightLeg);
        copyPart(from.leftLeg, to.leftLeg);
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        if (from == null || to == null) return;
        to.loadPose(from.storePose());
        to.visible = from.visible;
    }
}
