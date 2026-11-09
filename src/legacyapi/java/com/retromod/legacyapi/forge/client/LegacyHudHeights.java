/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

/** How far each Forge 1.20.1 status bar moved ForgeGui's left and right heights up, for mods that read them. */
public final class LegacyHudHeights {

    /**
     * What the bars depend on, read from the camera player each frame: whether they show at all, the hearts' top
     * health and absorption, a vehicle that hides the food bar, a living vehicle's top health, and the air.
     */
    public record Status(boolean survival, float maxHealth, float absorption, boolean foodHidden, float mountMaxHealth,
            boolean underwater, int air, int maxAir) {

        /** A camera that is no player, or a game mode that cannot hurt it, draws none of the bars. */
        public static final Status NONE = new Status(false, 0, 0, false, 0, false, 0, 0);
    }

    private LegacyHudHeights() {}

    /** The {left, right} rows the old overlay {@code path} adds when it draws, or none. */
    public static int[] rise(String path, Status status) {
        // The status bars drew only in a game mode that can hurt the camera player.
        if (!status.survival()) return new int[]{0, 0};
        return switch (path) {
            case "player_health" -> new int[]{healthRise(status.maxHealth(), status.absorption()), 0};
            // The armor row took its place even with no armor.
            case "armor_level" -> new int[]{10, 0};
            case "food_level" -> new int[]{0, status.foodHidden() ? 0 : 10};
            case "air_level" -> new int[]{0, status.underwater() || status.air() < status.maxAir() ? 10 : 0};
            case "mount_health" -> new int[]{0, mountRise(status.mountMaxHealth())};
            default -> new int[]{0, 0};
        };
    }

    // Rows of ten hearts; with more than two rows they packed closer, down to 3 pixels, and the last row kept its 10.
    static int healthRise(float maxHealth, float absorption) {
        int absorb = (int) Math.ceil(absorption);
        int rows = (int) Math.ceil((maxHealth + absorb) / 2.0F / 10.0F);
        int rowHeight = Math.max(10 - (rows - 2), 3);
        int rise = rows * rowHeight;
        return rowHeight == 10 ? rise : rise + 10 - rowHeight;
    }

    // A living vehicle showed up to 30 hearts, ten to a row.
    static int mountRise(float mountMaxHealth) {
        int hearts = Math.min((int) (mountMaxHealth + 0.5F) / 2, 30);
        return (hearts + 9) / 10 * 10;
    }
}
