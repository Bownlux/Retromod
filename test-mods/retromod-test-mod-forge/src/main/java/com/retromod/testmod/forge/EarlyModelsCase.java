/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.LoggerFactory;

/** An item renderer built from initializeClient while items register, as Farmer's Delight's skillet does. Client only. */
final class EarlyModelsCase {

    private static final String BUILT = "An old item renderer built in initializeClient while items register gets Minecraft's"
            + " entity models (item registration)";
    private static final String BAKED = "The entity models that renderer kept bake a vanilla layer once the game has loaded"
            + " (first client tick in a world)";

    private static EarlyRenderer renderer;
    private static Throwable buildFailure;
    private static boolean built;
    private static boolean bakeReported;

    private EarlyModelsCase() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(EarlyModelsCase::onClientTick);
        } catch (Throwable t) {
            log("Early item renderer checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Called from the item's initializeClient, which Forge runs inside the item's constructor while items register. */
    static void buildDuringRegistration() {
        if (built) return;
        built = true;
        // A failure here is kept for the log, so the item still registers and the other cases run.
        try {
            renderer = new EarlyRenderer();
        } catch (Throwable t) {
            buildFailure = t;
        }
        log(BUILT, buildFailure == null ? "success"
                : "fail: " + buildFailure.getClass().getSimpleName() + ": " + buildFailure.getMessage());
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || bakeReported || Minecraft.getInstance().level == null) return;
        bakeReported = true;
        if (renderer == null) {
            log(BAKED, "fail: no renderer was built during item registration");
            return;
        }
        // Minecraft 1.20.1 answered null that early, so there the kept models are only checked on a translated host.
        if (renderer.models == null) {
            log(BAKED, "skipped: this host had no entity models while items registered");
            return;
        }
        try {
            boolean head = renderer.models.bakeLayer(ModelLayers.PLAYER).hasChild("head");
            log(BAKED, head ? "success" : "fail: the player layer has no head");
        } catch (Throwable t) {
            log(BAKED, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /** Asks for the game's renderers and models in its constructor and keeps the models for later, as old renderers did. */
    static final class EarlyRenderer extends BlockEntityWithoutLevelRenderer {
        final EntityModelSet models;

        EarlyRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
            models = Minecraft.getInstance().getEntityModels();
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
                int packedLight, int packedOverlay) {
        }
    }
}
