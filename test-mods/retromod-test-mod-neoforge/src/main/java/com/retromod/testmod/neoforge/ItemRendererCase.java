/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.LoggerFactory;

/** An old BlockEntityWithoutLevelRenderer, which Minecraft 1.21.4 removed, logging whether the game finds and draws it. */
final class ItemRendererCase {

    private static final String GETTER = "IClientItemExtensions.getCustomRenderer() answers the item's old renderer (first client tick)";
    private static final String GUI = "An old BlockEntityWithoutLevelRenderer draws its item with ItemDisplayContext.GUI"
            + " (first GUI draw in a world)";
    private static final String KEPT = "The entity models kept while client extensions register bake a vanilla layer once the game"
            + " has loaded (first client tick in a world)";
    private static final int WAIT = 200;

    private ItemRendererCase() {}

    /** The item on both sides; its renderer and checks on a client only. */
    static void register(IEventBus modBus) {
        try {
            modBus.addListener(ItemRendererCase::onRegister);
            NeoForge.EVENT_BUS.addListener(ItemRendererCase::giveOnLogin);
            if (FMLEnvironment.dist.isClient()) Client.register(modBus);
        } catch (Throwable t) {
            log("The old item renderer case registers (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onRegister(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> helper.register(
                ResourceLocation.fromNamespaceAndPath("retromod_test_mod_neoforge", "legacy_renderer_item"), LegacyRendererItem.INSTANCE));
    }

    // A test world's player gets one, once, so the hotbar draws it.
    private static void giveOnLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).is(LegacyRendererItem.INSTANCE)) return;
            }
            inventory.add(new ItemStack(LegacyRendererItem.INSTANCE));
        } catch (Throwable t) {
            log("The old item renderer case's item is given at login", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info("[Retromod-Test-NeoForge] {}: {}", what, result);
    }

    /** The client half, loaded only on a client. */
    private static final class Client {
        private static volatile int guiDraws;
        private static CountingRenderer renderer;
        // What the game answered while client extensions registered, before its first resource reload.
        private static EntityModelSet keptModels;
        private static boolean keptReported;
        private static boolean getterReported;
        private static boolean guiReported;
        private static int ticksInWorld;

        static void register(IEventBus modBus) {
            modBus.addListener(Client::onClientExtensions);
            NeoForge.EVENT_BUS.addListener(Client::onClientTick);
        }

        // NeoForge 1.21.1 takes an item's render properties from this event.
        private static void onClientExtensions(RegisterClientExtensionsEvent event) {
            keptModels = Minecraft.getInstance().getEntityModels();
            event.registerItem(new IClientItemExtensions() {
                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    return renderer();
                }
            }, LegacyRendererItem.INSTANCE);
        }

        private static synchronized CountingRenderer renderer() {
            if (renderer == null) renderer = new CountingRenderer();
            return renderer;
        }

        // The hotbar draws with the GUI context, and the test mod gives a joining player the item.
        private static void onClientTick(ClientTickEvent.Post event) {
            if (!getterReported) {
                getterReported = true;
                try {
                    Object found = IClientItemExtensions.of(new ItemStack(LegacyRendererItem.INSTANCE)).getCustomRenderer();
                    log(GETTER, found == renderer() ? "success" : "fail: answered " + found);
                } catch (Throwable t) {
                    log(GETTER, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            if (!keptReported && Minecraft.getInstance().level != null) {
                keptReported = true;
                try {
                    log(KEPT, keptModels.bakeLayer(ModelLayers.PLAYER).hasChild("head") ? "success" : "fail: the player layer has no head");
                } catch (Throwable t) {
                    log(KEPT, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            if (guiReported) return;
            if (guiDraws > 0) {
                guiReported = true;
                log(GUI, "success");
                return;
            }
            if (Minecraft.getInstance().level == null || ++ticksInWorld != WAIT) return;
            guiReported = true;
            log(GUI, "fail: not drawn in the GUI in " + WAIT + " client ticks in a world; keep the given item in the hotbar");
        }

        /** Counts the draws Minecraft asks for in the GUI context. */
        static final class CountingRenderer extends BlockEntityWithoutLevelRenderer {
            CountingRenderer() {
                super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
            }

            @Override
            public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
                    int packedLight, int packedOverlay) {
                if (context == ItemDisplayContext.GUI) guiDraws++;
            }
        }
    }
}
