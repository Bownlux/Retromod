/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.LoggerFactory;

/** An old BlockEntityWithoutLevelRenderer, which Minecraft 1.21.4 removed, logging whether the game finds and draws it. Client only. */
final class ItemRendererCases {

    private static final String GETTER = "IClientItemExtensions.getCustomRenderer() answers the item's old renderer (first client tick)";
    private static final String GUI = "An old BlockEntityWithoutLevelRenderer draws its item with ItemDisplayContext.GUI"
            + " (first GUI draw in a world)";
    private static final int WAIT = 200;

    private static volatile int guiDraws;
    private static CountingRenderer renderer;
    private static boolean getterReported;
    private static boolean guiReported;
    private static int ticksInWorld;

    private ItemRendererCases() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(ItemRendererCases::onClientTick);
        } catch (Throwable t) {
            log("Old item renderer checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** The item's render properties, as an old mod handed them to Forge from initializeClient. */
    static IClientItemExtensions properties() {
        return new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer();
            }
        };
    }

    private static synchronized CountingRenderer renderer() {
        if (renderer == null) renderer = new CountingRenderer();
        return renderer;
    }

    // The hotbar draws with the GUI context, and the test mod gives a joining player the item.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!getterReported) {
            getterReported = true;
            try {
                Object found = IClientItemExtensions.of(new ItemStack(LegacyRendererItem.INSTANCE)).getCustomRenderer();
                log(GETTER, found == renderer() ? "success" : "fail: answered " + found);
            } catch (Throwable t) {
                log(GETTER, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
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

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
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
