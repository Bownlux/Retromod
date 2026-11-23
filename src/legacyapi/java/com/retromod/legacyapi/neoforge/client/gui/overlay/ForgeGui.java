/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.gui.overlay;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.player.Player;

/** Forge 1.20.1's ForgeGui on NeoForge: the left and right heights NeoForge keeps on the HUD, and the old helpers. */
public final class ForgeGui {

    // Each mod casts Minecraft.gui to its own copy, which reads and writes NeoForge's own heights around each call.
    private static final ForgeGui INSTANCE = new ForgeGui();
    // Matched by the end of its name, since another mod's copy is relocated under its own package.
    private static final String COPY = "com.retromod.legacyapi.neoforge.client.gui.overlay.ForgeGui";
    private static final String HUD = "net.minecraft.client.gui.Hud";

    /** Rows the left and right status bars take up from the bottom. */
    public int leftHeight = 39;
    public int rightHeight = 39;

    private ForgeGui() {}

    /** {@code (ForgeGui) value}: this mod's object for the host's Gui or Hud or another mod's copy, as the old cast passed them. */
    public static ForgeGui cast(Object value) {
        if (value == null) return null;
        if (!isInstance(value)) {
            throw new ClassCastException(value.getClass().getName() + " cannot be cast to " + ForgeGui.class.getName());
        }
        pullHeights();
        return INSTANCE;
    }

    /** {@code value instanceof ForgeGui}: true for the host's Gui and Hud, which stand where the old ForgeGui was. */
    public static boolean isInstance(Object value) {
        if (value == null) return false;
        String name = value.getClass().getName();
        return value instanceof Gui || name.equals(HUD) || name.equals(COPY) || name.endsWith("." + COPY);
    }

    public Minecraft getMinecraft() {
        return Minecraft.getInstance();
    }

    /** Whether the hearts, armor, food and air draw: the player can be hurt and the camera is a player, as before. */
    public boolean shouldDrawSurvivalElements() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gameMode != null && minecraft.gameMode.canHurtPlayer() && minecraft.getCameraEntity() instanceof Player;
    }

    /** Minecraft 26.x extracts render state instead of drawing, so there is no blend or depth state to set. */
    public void setupOverlayRenderState(boolean blend, boolean depthTest) {}

    public Font getFont() {
        return Minecraft.getInstance().font;
    }

    /** The HUD's tick counter, read where the host keeps it. */
    public int getGuiTicks() {
        try {
            Object owner = heightsOwner();
            Method ticks = owner.getClass().getMethod("getGuiTicks");
            return (Integer) ticks.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException none) {
            return 0;
        }
    }

    /** Copies NeoForge's heights in before this mod's code runs during a frame. */
    static void pullHeights() {
        try {
            Object owner = heightsOwner();
            INSTANCE.leftHeight = owner.getClass().getField("leftHeight").getInt(owner);
            INSTANCE.rightHeight = owner.getClass().getField("rightHeight").getInt(owner);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            noteHeights(unreadable);
        }
    }

    /** Copies this mod's heights back out after its code ran, for the next overlay to read. */
    static void pushHeights() {
        try {
            Object owner = heightsOwner();
            owner.getClass().getField("leftHeight").setInt(owner, INSTANCE.leftHeight);
            owner.getClass().getField("rightHeight").setInt(owner, INSTANCE.rightHeight);
        } catch (ReflectiveOperationException | RuntimeException unwritable) {
            noteHeights(unwritable);
        }
    }

    // NeoForge keeps the heights on Gui up to 26.1 and on its Hud from 26.2.
    private static Object heightsOwner() throws ReflectiveOperationException {
        Gui gui = Minecraft.getInstance().gui;
        for (Field field : gui.getClass().getFields()) {
            if (field.getName().equals("leftHeight")) return gui;
        }
        return gui.getClass().getField("hud").get(gui);
    }

    private static void noteHeights(Exception failure) {
        LegacyLog.warnOnce("neoforge hud heights " + COPY, "Retromod could not reach NeoForge's HUD heights for an old overlay,"
                + " so it may draw over the status bars: " + failure);
    }
}
