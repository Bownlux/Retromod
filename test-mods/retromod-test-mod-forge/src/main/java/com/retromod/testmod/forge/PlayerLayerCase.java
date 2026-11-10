/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.LoggerFactory;

/** Layers added to the player renderers as Sophisticated Backpacks and Curios add theirs, which 26.x keys by model type. Client only. */
final class PlayerLayerCase {

    private static final String SKIN_MAP = "getSkinMap() hands out both player renderers for a layer (client reload listener)";
    private static final String ADD_LAYERS = "AddLayers getSkin, getSkins and getRenderer answer by name (add layers event)";
    private static final String DRAWN = "An old layer draws with the entity it decorates (first draw)";
    private static final String ITEM_RENDERER = "An old layer keeps getItemRenderer() from its constructor (client reload listener)";
    private static final String ITEM_DRAWN = "renderStatic draws an item inside an old layer's draw (first draw)";

    private static boolean drawn;

    private PlayerLayerCase() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(PlayerLayerCase::onReloadListeners);
            modBus.addListener(PlayerLayerCase::onAddLayers);
        } catch (Throwable t) {
            log("Old player layer checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // Sophisticated Backpacks reads the skin map in a reload listener, after the game built its renderers.
    private static void onReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            try {
                Map<String, EntityRenderer<? extends Player>> skins = Minecraft.getInstance().getEntityRenderDispatcher().getSkinMap();
                int added = 0;
                for (EntityRenderer<? extends Player> renderer : skins.values()) {
                    if (renderer instanceof LivingEntityRenderer<?, ?> living) {
                        addMarker(living);
                        added++;
                    }
                }
                boolean named = skins.containsKey("default") && skins.containsKey("slim");
                log(SKIN_MAP, named && added == 2 ? "success" : "fail: " + skins.keySet() + ", " + added + " layers added");
            } catch (Throwable t) {
                log(SKIN_MAP, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    // Curios and Supplementaries look the renderers up by skin name and entity type while the event runs.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        try {
            LivingEntityRenderer slim = event.getSkin("slim");
            Set<String> skins = event.getSkins();
            LivingEntityRenderer zombie = event.getRenderer((EntityType) EntityType.ZOMBIE);
            if (zombie != null) addMarker(zombie);
            boolean ok = slim != null && event.getSkin("giant") == null && skins.contains("default") && skins.contains("slim") && zombie != null;
            log(ADD_LAYERS, ok ? "success" : "fail: slim " + slim + ", skins " + skins + ", zombie " + zombie);
        } catch (Throwable t) {
            log(ADD_LAYERS, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addMarker(LivingEntityRenderer living) {
        living.addLayer(new Marker(living));
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /** Draws one apple on its first call, and reports whether it was handed the entity, as an old layer expects. */
    static final class Marker extends RenderLayer<LivingEntity, EntityModel<LivingEntity>> {
        private static boolean keptRenderer;
        // As Sophisticated Backpacks' layer does: the item renderer is kept from the constructor, inside a reload listener.
        private final ItemRenderer items;

        Marker(RenderLayerParent<LivingEntity, EntityModel<LivingEntity>> parent) {
            super(parent);
            ItemRenderer kept = null;
            try {
                kept = Minecraft.getInstance().getItemRenderer();
                if (!keptRenderer) log(ITEM_RENDERER, kept != null ? "success" : "fail: no renderer");
            } catch (Throwable t) {
                if (!keptRenderer) log(ITEM_RENDERER, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            keptRenderer = true;
            items = kept;
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int light, LivingEntity entity, float limbSwing,
                float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (drawn) return;
            drawn = true;
            log(DRAWN, entity != null ? "success (" + entity.getClass().getSimpleName() + ")" : "fail: no entity");
            if (items == null || entity == null) return;
            try {
                // One small apple at the entity's feet, for a single frame.
                poseStack.pushPose();
                poseStack.scale(0.25f, 0.25f, 0.25f);
                items.renderStatic(new ItemStack(Items.APPLE), ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY, poseStack, buffers,
                        entity.level(), 0);
                poseStack.popPose();
                log(ITEM_DRAWN, "success");
            } catch (Throwable t) {
                log(ITEM_DRAWN, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }
}
