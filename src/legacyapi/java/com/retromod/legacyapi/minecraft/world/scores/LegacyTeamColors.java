/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.scores;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

/** A team's color as the chat formatting it was before 26.2 gave teams their own color type. */
public final class LegacyTeamColors {

    private LegacyTeamColors() {}

    // The team colors and the chat colors share their constant names, from BLACK to WHITE.

    /** {@code team.getColor()}: the formatting of the same name, or reset when the team has no color, as before. */
    public static ChatFormatting getColor(PlayerTeam team) {
        Optional<TeamColor> color = team.getColor();
        return color.isPresent() ? ChatFormatting.valueOf(color.get().name()) : ChatFormatting.RESET;
    }

    /** {@code team.setColor(formatting)}: reset, or a formatting that is not a color, clears the team's color. */
    public static void setColor(PlayerTeam team, ChatFormatting formatting) {
        TeamColor color = null;
        for (TeamColor candidate : TeamColor.values()) {
            if (formatting != null && candidate.name().equals(formatting.name())) color = candidate;
        }
        team.setColor(Optional.ofNullable(color));
    }
}
