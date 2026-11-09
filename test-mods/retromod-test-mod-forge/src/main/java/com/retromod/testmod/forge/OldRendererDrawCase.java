/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.LoggerFactory;

/** An old block entity renderer drawing an item, as Farmer's Delight's and Waystones' do, driven as 26.x drives one. Client only. */
final class OldRendererDrawCase {

    private static final String BLOCK = "An old block entity renderer runs from a 26.x submit and its renderStatic item reaches the frame"
            + " (first client tick in a world)";
    private static final String ENTITY = "An old entity renderer gets a 26.x render state and submit (first client tick in a world)";

    private static boolean reported;

    private OldRendererDrawCase() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(OldRendererDrawCase::onClientTick);
        } catch (Throwable t) {
            log("Old renderer draw checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || reported || Minecraft.getInstance().level == null) return;
        reported = true;
        log(BLOCK, drawBlock());
        log(ENTITY, declares(OldEntityRenderer.class, "createRenderState", 0) && declares(OldEntityRenderer.class, "submit", 4)
                ? "success" : "fail: no render state or submit of its own");
    }

    // The steps 26.x takes for a block entity each frame: a state, the extract, then the submit into the frame's collector.
    private static String drawBlock() {
        try {
            OldPlate plate = new OldPlate();
            SignBlockEntity sign = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
            Object state = method(OldPlate.class, "createRenderState", 0).invoke(plate);
            method(OldPlate.class, "extractRenderState", 5).invoke(plate, sign, state, 0.5f, null, null);
            Class<?> collectorType = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector");
            List<String> calls = new ArrayList<>();
            Object collector = Proxy.newProxyInstance(collectorType.getClassLoader(), new Class<?>[]{collectorType}, (proxy, called, args) -> {
                calls.add(called.getName());
                return called.getReturnType() == boolean.class ? false : null;
            });
            method(OldPlate.class, "submit", 4).invoke(plate, state, new PoseStack(), collector, null);
            if (plate.drawn != 1) return "fail: the old render ran " + plate.drawn + " times";
            return calls.contains("submitItem") ? "success" : "fail: the collector was handed " + calls;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "fail: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    private static Method method(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        return null;
    }

    // Declared on the class itself; the host base's own abstract versions are inherited either way.
    private static boolean declares(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return true;
        }
        return false;
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /** Draws an apple on its block entity, with the item renderer Minecraft 1.20.1 had. */
    static final class OldPlate implements BlockEntityRenderer<SignBlockEntity> {
        int drawn;

        @Override
        public void render(SignBlockEntity sign, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
            drawn++;
            Minecraft.getInstance().getItemRenderer().renderStatic(new ItemStack(Items.APPLE), ItemDisplayContext.FIXED, light, overlay,
                    poseStack, buffers, sign.getLevel(), 0);
        }
    }

    /** An entity renderer directly under EntityRenderer, with the old render. */
    static final class OldEntityRenderer extends EntityRenderer<Entity> {
        OldEntityRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public void render(Entity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
            super.render(entity, yaw, partialTick, poseStack, buffers, light);
        }

        @Override
        public ResourceLocation getTextureLocation(Entity entity) {
            return null;
        }
    }
}
