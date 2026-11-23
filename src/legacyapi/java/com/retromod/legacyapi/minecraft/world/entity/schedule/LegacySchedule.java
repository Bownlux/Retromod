/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.schedule;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.entity.schedule.Activity;

/** The villager day plan 26.1 replaced with environment timelines: which activity starts at which time of day. */
public class LegacySchedule {

    // The game's own plans are environment timelines now and no brain reads these, so each is an empty plan.
    public static final LegacySchedule EMPTY = new LegacySchedule();
    public static final LegacySchedule SIMPLE = new LegacySchedule();
    public static final LegacySchedule VILLAGER_BABY = new LegacySchedule();
    public static final LegacySchedule VILLAGER_DEFAULT = new LegacySchedule();

    // Start time in the day, from 0 to 23999, to the activity that begins then.
    private final TreeMap<Integer, Activity> starts = new TreeMap<>();

    public LegacySchedule() {
    }

    void start(int time, Activity activity) {
        starts.put(time, activity);
    }

    /** The activity running at this time of day; before the first start of the day the last one of the previous day still runs. */
    public Activity getActivityAt(int timeOfDay) {
        if (starts.isEmpty()) return Activity.IDLE;
        Map.Entry<Integer, Activity> running = starts.floorEntry(Math.floorMod(timeOfDay, 24000));
        return running != null ? running.getValue() : starts.lastEntry().getValue();
    }

    /** Brain.setSchedule took one of these; 26.x brains follow the world's timelines, so the old plan is not applied. */
    public static void applyTo(Object brain, LegacySchedule schedule) {
    }
}
