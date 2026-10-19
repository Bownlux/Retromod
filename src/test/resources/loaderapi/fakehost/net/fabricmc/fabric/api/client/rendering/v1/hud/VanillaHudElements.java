package net.fabricmc.fabric.api.client.rendering.v1.hud;

import net.minecraft.resources.Identifier;

/** The 26.3 vanilla HUD elements, in the order the host draws them. */
public final class VanillaHudElements {
    public static final Identifier MISC_OVERLAYS = id("misc_overlays");
    public static final Identifier CROSSHAIR = id("crosshair");
    public static final Identifier SPECTATOR_MENU = id("spectator_menu");
    public static final Identifier HOTBAR = id("hotbar");
    public static final Identifier ARMOR_BAR = id("armor_bar");
    public static final Identifier HEALTH_BAR = id("health_bar");
    public static final Identifier FOOD_BAR = id("food_bar");
    public static final Identifier AIR_BAR = id("air_bar");
    public static final Identifier MOUNT_HEALTH = id("mount_health");
    public static final Identifier INFO_BAR = id("info_bar");
    public static final Identifier EXPERIENCE_LEVEL = id("experience_level");
    public static final Identifier HELD_ITEM_TOOLTIP = id("held_item_tooltip");
    public static final Identifier SPECTATOR_TOOLTIP = id("spectator_tooltip");
    public static final Identifier MOB_EFFECTS = id("mob_effects");
    public static final Identifier BOSS_BAR = id("boss_bar");
    public static final Identifier SLEEP = id("sleep");
    public static final Identifier DEMO_TIMER = id("demo_timer");
    public static final Identifier SCOREBOARD = id("scoreboard");
    public static final Identifier OVERLAY_MESSAGE = id("overlay_message");
    public static final Identifier TITLE_AND_SUBTITLE = id("title_and_subtitle");
    public static final Identifier CHAT = id("chat");
    public static final Identifier PLAYER_LIST = id("player_list");
    public static final Identifier SUBTITLES = id("subtitles");

    private VanillaHudElements() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("minecraft", path);
    }
}
