/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.IArmPoseTransformer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/** A custom arm pose built in a client setup class initializer, as Farmer's Delight builds its skillet flip. Client only. */
final class ArmPoseCase {

    private static final String CASE = "ArmPose.create with a transformer in client setup gives a pose that applies (client setup)";

    private ArmPoseCase() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(ArmPoseCase::onClientSetup);
        } catch (Throwable t) {
            log("fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        try {
            HumanoidModel.ArmPose pose = Poses.FLIP;
            // Forge adds the pose under its own name; NeoForge has no way to add one while the game runs.
            boolean added = pose != null && pose.name().equals("RETROMOD_TEST_FLIP");
            boolean itemOnNeoForge = pose == HumanoidModel.ArmPose.ITEM && LegacyEventBaseCase.onNeoForge();
            // The transformer's own body reads the frame time before it needs an entity.
            new FlipTransformer().applyTransform(null, null, HumanoidArm.RIGHT);
            log(added || itemOnNeoForge ? "success" + (added ? "" : " (item pose on NeoForge)") + appliedWithoutEntity(pose)
                    : "fail: got " + pose);
        } catch (Throwable t) {
            log("fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** On a host that poses from render states, the host's own call with no world entity leaves the arm alone. */
    private static String appliedWithoutEntity(HumanoidModel.ArmPose pose) throws Exception {
        Class<?> state;
        try {
            state = Class.forName("net.minecraft.client.renderer.entity.state.HumanoidRenderState");
        } catch (ClassNotFoundException olderHost) {
            return "";
        }
        for (Method method : pose.getClass().getMethods()) {
            if (method.getName().equals("applyTransform") && method.getParameterCount() == 3
                    && method.getParameterTypes()[1] == state) {
                method.invoke(pose, null, state.getConstructor().newInstance(), HumanoidArm.RIGHT);
                return ", the host's render-state call ran";
            }
        }
        return "";
    }

    private static void log(String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", CASE, result);
    }

    /** Built when client setup first reads it, as a 1.20.1 mod's client class does. */
    static final class Poses {
        static final HumanoidModel.ArmPose FLIP = HumanoidModel.ArmPose.create("RETROMOD_TEST_FLIP", false, new FlipTransformer());
    }

    static final class FlipTransformer implements IArmPoseTransformer {
        @Override
        public void applyTransform(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
            float partialTick = Minecraft.getInstance().getFrameTime();
            if (entity == null) return;
            (arm == HumanoidArm.LEFT ? model.leftArm : model.rightArm).xRot = -0.5f - partialTick * 0.1f;
        }
    }
}
