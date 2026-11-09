/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.GuiOverlayManager;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.LoggerFactory;

/** A Forge 1.20.1 HUD overlay and its render-event listeners, which Forge 26.2 removed with the overlay registry; client only. */
public final class LegacyHudOverlays {

    // The checks need frames drawn in a world, so they report after this many client ticks there.
    private static final int WAIT = 200;

    private static volatile boolean registerSawGui;
    private static volatile int probeFrames;
    private static volatile int guiPosts;
    private static volatile String crosshairPre = "fail: no Pre arrived for the crosshair";
    private static volatile boolean cancelledOnce;
    // Frames are counted by RenderGuiEvent.Post, which ends each frame.
    private static volatile int cancelledFrame = -1;
    private static volatile boolean postInCancelledFrame;
    private static volatile String healthHeight = "fail: no Post arrived for the player's health";
    private static int ticksInWorld;
    private static boolean reported;

    private LegacyHudOverlays() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(LegacyHudOverlays::onRegisterOverlays);
            MinecraftForge.EVENT_BUS.register(LegacyHudOverlays.class);
            MinecraftForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> guiPosts++);
        } catch (Throwable t) {
            log("Old HUD overlay listeners register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // Forge 1.20.1 posted this after key mappings, when Minecraft.gui already existed.
    private static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        registerSawGui = Minecraft.getInstance().gui != null;
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "probe", (gui, graphics, partialTick, width, height) -> probeFrames++);
    }

    @SubscribeEvent
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;
        crosshairPre = "success";
        // Cancelled once, so that frame must bring no Post for the crosshair.
        if (!cancelledOnce) {
            cancelledOnce = true;
            cancelledFrame = guiPosts;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() == VanillaGuiOverlay.CROSSHAIR.type() && guiPosts == cancelledFrame) {
            postInCancelledFrame = true;
        }
        if (event.getOverlay() != VanillaGuiOverlay.PLAYER_HEALTH.type()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || !minecraft.gameMode.canHurtPlayer()) {
            healthHeight = "success (not checked: the player cannot be hurt in this game mode)";
            return;
        }
        int left = ((ForgeGui) minecraft.gui).leftHeight;
        healthHeight = left >= 49 ? "success" : "fail: leftHeight was " + left + " after the hearts";
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (reported || event.phase != TickEvent.Phase.END || Minecraft.getInstance().level == null) return;
        if (++ticksInWorld < WAIT) return;
        reported = true;
        log("(a) An overlay registered with registerAbove(HOTBAR) draws, and Minecraft.gui exists while it registers",
                probeFrames > 0 && registerSawGui ? "success"
                        : "fail: drew " + probeFrames + " frames, Minecraft.gui " + (registerSawGui ? "existed" : "was null"));
        log("(b) RenderGuiOverlayEvent.Pre reports VanillaGuiOverlay.CROSSHAIR.type() for the crosshair", crosshairPre);
        log("(c) Cancelling the crosshair's Pre once skips that frame's Post", !cancelledOnce ? "fail: the Pre never arrived"
                : postInCancelledFrame ? "fail: the Post arrived in the cancelled frame" : "success");
        log("(d) ForgeGui.leftHeight counts the hearts in Post(PLAYER_HEALTH)", healthHeight);
        log("(e) RenderGuiEvent.Post fires every frame", guiPosts > 0 ? "success" : "fail: it never fired");
        ResourceLocation food = new ResourceLocation("minecraft", "food_level");
        log("(f) GuiOverlayManager.findOverlay(minecraft:food_level) is VanillaGuiOverlay.FOOD_LEVEL.type()",
                GuiOverlayManager.findOverlay(food) == VanillaGuiOverlay.FOOD_LEVEL.type() ? "success" : "fail: another object");
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {} (in world): {}", what, result);
    }
}
