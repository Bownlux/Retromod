/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import com.retromod.legacyapi.forge.event.LegacyCancel;
import com.retromod.legacyapi.forge.event.LegacyEventStore;
import java.util.Set;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.HasResult;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.LogicalSide;

/** Forge 1.20.1's result on every event, kept beside the Forge 26.2 events that no longer have one. */
public final class EventResults {

    // The events Forge 1.20.1 marked @Event.HasResult, under their Forge 26.2 names; a subclass inherited the mark.
    private static final Set<String> HAD_RESULT = Set.of(
            "net.minecraftforge.client.event.RenderNameTagEvent",
            "net.minecraftforge.client.event.ScreenEvent$MouseButtonPressed$Post",
            "net.minecraftforge.client.event.ScreenEvent$MouseButtonReleased$Post",
            "net.minecraftforge.event.entity.EntityMobGriefingEvent",
            "net.minecraftforge.event.entity.living.LivingPackSizeEvent",
            "net.minecraftforge.event.entity.living.MobEffectEvent$Applicable",
            "net.minecraftforge.event.entity.living.MobSpawnEvent$AllowDespawn",
            "net.minecraftforge.event.entity.living.MobSpawnEvent$PositionCheck",
            "net.minecraftforge.event.entity.living.MobSpawnEvent$SpawnPlacementCheck",
            "net.minecraftforge.event.entity.living.ZombieEvent$SummonAidEvent",
            "net.minecraftforge.event.entity.player.BonemealEvent",
            "net.minecraftforge.event.entity.player.CriticalHitEvent",
            "net.minecraftforge.event.entity.player.EntityItemPickupEvent",
            "net.minecraftforge.event.entity.player.FillBucketEvent",
            "net.minecraftforge.event.entity.player.PlayerSpawnPhantomsEvent",
            "net.minecraftforge.event.entity.player.SleepingLocationCheckEvent",
            "net.minecraftforge.event.entity.player.SleepingTimeCheckEvent",
            "net.minecraftforge.event.level.BlockEvent$CreateFluidSourceEvent",
            "net.minecraftforge.event.level.BlockEvent$CropGrowEvent$Pre",
            "net.minecraftforge.event.level.BlockFeatureGrowEvent");

    // Forge gave BreakEvent a result its break check reads in 1.21.6, by Forge's source history (no jar checked).
    // Forge 1.20.1 ignored the one old code sets, so that one stays beside the event.
    private static final String BREAK = "net.minecraftforge.event.level.BlockEvent$BreakEvent";
    // A client class, matched by name so a dedicated server never loads it or NameTagResults.
    private static final String NAME_TAG = "net.minecraftforge.client.event.RenderNameTagEvent";

    private EventResults() {}

    /** {@code event.getResult()}: the event's own result, or the one kept beside an event that has none, DEFAULT at first. */
    public static Result getResult(Object event) {
        if (answersItself(event)) return ((HasResult) event).getResult();
        return kept(event);
    }

    /** {@code event.setResult(result)}: stored as given, as Forge 1.20.1 did, and passed on where the host still reads it. */
    public static void setResult(Object event, Result result) {
        if (answersItself(event)) {
            ((HasResult) event).setResult(result);
            return;
        }
        Result previous = kept(event);
        LegacyEventStore.put(event, LegacyEventStore.RESULT, result);
        // Forge 1.20.1 read the result of these two, and Forge 26.2 answers them another way.
        if (event.getClass().getName().equals(NAME_TAG)) {
            NameTagResults.apply(event, previous, result);
        } else if (event instanceof PlayerInteractEvent.LeftClickBlock click) {
            denyBreak(click, previous, result);
        }
    }

    /** {@code event.hasResult()}: whether Forge 1.20.1 gave the event a result, so BreakEvent says no and the name tag says yes. */
    public static boolean hasResult(Object event) {
        for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
            if (HAD_RESULT.contains(type.getName())) return true;
        }
        return false;
    }

    // A rebuilt event and a host event that kept its result hold it themselves.
    private static boolean answersItself(Object event) {
        return event instanceof HasResult && !event.getClass().getName().equals(BREAK);
    }

    private static Result kept(Object event) {
        return LegacyEventStore.get(event, LegacyEventStore.RESULT) instanceof Result result ? result : Result.DEFAULT;
    }

    // Forge 1.20.1's server ended a left click that a listener denied for a player outside creative. Forge 26.2 ends
    // it when the event is cancelled, so a denial cancels and a later change of mind takes the cancel back.
    private static void denyBreak(PlayerInteractEvent.LeftClickBlock click, Result previous, Result result) {
        boolean denied = result == Result.DENY;
        if (denied == (previous == Result.DENY) || click.getSide() != LogicalSide.SERVER) return;
        Player player = click.getEntity();
        if (player == null || player.isCreative()) return;
        LegacyCancel.setCanceled(click, denied);
    }
}
