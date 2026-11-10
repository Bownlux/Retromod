/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.gui.overlay;

import net.minecraft.resources.Identifier;

/** Forge 1.20.1's vanilla HUD overlays, with their old ids and in the order Forge 1.20.1 drew them. */
public enum VanillaGuiOverlay {
    VIGNETTE("vignette"),
    SPYGLASS("spyglass"),
    HELMET("helmet"),
    FROSTBITE("frostbite"),
    PORTAL("portal"),
    HOTBAR("hotbar"),
    CROSSHAIR("crosshair"),
    BOSS_EVENT_PROGRESS("boss_event_progress"),
    PLAYER_HEALTH("player_health"),
    ARMOR_LEVEL("armor_level"),
    FOOD_LEVEL("food_level"),
    AIR_LEVEL("air_level"),
    MOUNT_HEALTH("mount_health"),
    JUMP_BAR("jump_bar"),
    EXPERIENCE_BAR("experience_bar"),
    ITEM_NAME("item_name"),
    SLEEP_FADE("sleep_fade"),
    POTION_ICONS("potion_icons"),
    DEBUG_TEXT("debug_text"),
    FPS_GRAPH("fps_graph"),
    RECORD_OVERLAY("record_overlay"),
    TITLE_TEXT("title_text"),
    SUBTITLES("subtitles"),
    SCOREBOARD("scoreboard"),
    CHAT_PANEL("chat_panel"),
    PLAYER_LIST("player_list");

    private final Identifier id;

    // Mods read these ids from their own static initializers while they construct, so this touches only Identifier.
    VanillaGuiOverlay(String path) {
        this.id = Identifier.withDefaultNamespace(path);
    }

    public Identifier id() {
        return id;
    }

    /** This mod's one {@link NamedGuiOverlay} for the id, the object a render event's {@code getOverlay()} returns for it. */
    public NamedGuiOverlay type() {
        return NamedGuiOverlay.of(id);
    }
}
