/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.gamerules;

import com.retromod.shim.common.embedded.LegacyLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

/** Carries an old mod rule's value, saved under its old name by a world from before 26.x, over to the rule's new id. */
final class LegacyGameRuleCarryOver {

    private LegacyGameRuleCarryOver() {}

    /**
     * Minecraft's own upgrade renames only the vanilla rules and leaves a mod rule's entry under its old
     * name, which the new rule map cannot read. Such an entry stays on disk until the map is saved
     * again, so the saved value is read from there at server start and set on the new rule. Setting
     * it marks the map changed, and the next save writes the new id and drops the old entry.
     */
    static void apply(MinecraftServer server, Map<GameRule<?>, String> oldNames) {
        if (oldNames.isEmpty()) return;
        try {
            List<CompoundTag> saved = savedRules(server);
            if (saved.isEmpty()) return;
            GameRules rules = server.overworld().getGameRules();
            synchronized (oldNames) {
                for (Map.Entry<GameRule<?>, String> entry : oldNames.entrySet()) {
                    carry(rules, entry.getKey(), entry.getValue(), saved);
                }
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            // This runs while the server starts, where a throw would stop it; the rules keep their defaults.
            LegacyLog.warn("Retromod could not read old game rule values from this world, so old mod rules start "
                    + "at their defaults: " + failure);
        }
    }

    private static <T> void carry(GameRules rules, GameRule<T> rule, String oldName, List<CompoundTag> saved) {
        for (CompoundTag tag : saved) {
            Optional<String> text = tag.getString(oldName);
            if (text.isEmpty()) continue;
            // A value that already differs from the default was set since the upgrade, so it stays.
            if (!rule.defaultValue().equals(rules.get(rule))) return;
            Optional<T> value = rule.deserialize(text.get()).result();
            if (value.isEmpty()) {
                LegacyLog.warn("The old game rule '" + oldName + "' has the saved value '" + text.get()
                        + "', which its new rule cannot read, so it starts at its default.");
                return;
            }
            // No server is passed: nothing has changed for players, and the old callbacks did not run on load.
            rules.set(rule, value.get(), null);
            LegacyLog.info("Carried the old game rule '" + oldName + "' = " + text.get() + " over to its new id.");
            return;
        }
    }

    /** The saved rule maps an old world may still hold: the 26.x saved data file, then the 1.21.11 level file. */
    private static List<CompoundTag> savedRules(MinecraftServer server) throws IOException {
        List<CompoundTag> found = new ArrayList<>();
        // The world folder comes from the level file, which every host names; 1.21.11 has no data folder constant.
        Path level = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        Path data = level.resolveSibling("data").resolve("minecraft").resolve("game_rules.dat");
        if (Files.isRegularFile(data)) {
            NbtIo.readCompressed(data, NbtAccounter.unlimitedHeap()).getCompound("data").ifPresent(found::add);
        }
        if (Files.isRegularFile(level)) {
            Optional<CompoundTag> levelData = NbtIo.readCompressed(level, NbtAccounter.unlimitedHeap()).getCompound("Data");
            // 1.21.11 renames GameRules to game_rules only in memory, so a world it has not saved yet still has the old key.
            for (String key : new String[] {"game_rules", "GameRules"}) {
                levelData.flatMap(fields -> fields.getCompound(key)).ifPresent(found::add);
            }
        }
        return found;
    }
}
