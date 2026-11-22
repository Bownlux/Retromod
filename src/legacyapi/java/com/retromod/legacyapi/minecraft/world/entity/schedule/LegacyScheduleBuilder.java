/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.schedule;

import net.minecraft.world.entity.schedule.Activity;

/** Fills a schedule one activity start at a time, as the old ScheduleBuilder did. */
public class LegacyScheduleBuilder {

    private final LegacySchedule schedule;

    public LegacyScheduleBuilder(LegacySchedule schedule) {
        this.schedule = schedule;
    }

    public LegacyScheduleBuilder changeActivityAt(int time, Activity activity) {
        schedule.start(time, activity);
        return this;
    }

    public LegacySchedule build() {
        return schedule;
    }

    /** One start of an activity, which the old builder kept until build(). */
    public static class ActivityTransition {
        private final int time;
        private final Activity activity;

        public ActivityTransition(int time, Activity activity) {
            this.time = time;
            this.activity = activity;
        }

        public int getTime() {
            return time;
        }

        public Activity getActivity() {
            return activity;
        }
    }
}
