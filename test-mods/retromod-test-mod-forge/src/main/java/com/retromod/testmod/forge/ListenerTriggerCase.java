/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonObject;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lootr's GenericTrigger: a trigger that implements CriterionTrigger itself and keeps its own
 * listeners, which 26.2 removed. Its advancement has one criterion, so firing once completes it and
 * leaves no listener. The result needs a player, so it is in the log rather than the summary.
 */
final class ListenerTriggerCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    static final ResourceLocation ID = new ResourceLocation("retromod_test_mod_forge", "legacy_listener");
    static final ListenerTrigger TRIGGER = new ListenerTrigger();

    private ListenerTriggerCase() {}

    static void register() {
        CriteriaTriggers.register(TRIGGER);
        MinecraftForge.EVENT_BUS.addListener(ListenerTriggerCase::fireOnLogin);
    }

    private static void fireOnLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String result;
        try {
            int fired = TRIGGER.fire(player);
            int left = TRIGGER.listening(player);
            result = fired == 1 && left == 0 ? "success"
                    : "fail: " + fired + " listener(s) fired and " + left + " left after the criterion was awarded";
        } catch (Throwable t) {
            result = "fail: " + t;
        }
        LOG.info("[Retromod-Test-Forge] An old trigger with its own listeners awards its criterion (player logged in): {}",
                result);
    }

    static final class ListenerTrigger implements CriterionTrigger<ListenerTrigger.Instance> {
        private final Map<PlayerAdvancements, Set<Listener<Instance>>> listeners = new HashMap<>();

        @Override
        public ResourceLocation getId() {
            return ID;
        }

        @Override
        public void addPlayerListener(PlayerAdvancements advancements, Listener<Instance> listener) {
            listeners.computeIfAbsent(advancements, key -> new HashSet<>()).add(listener);
        }

        @Override
        public void removePlayerListener(PlayerAdvancements advancements, Listener<Instance> listener) {
            Set<Listener<Instance>> set = listeners.get(advancements);
            if (set == null) return;
            set.remove(listener);
            if (set.isEmpty()) listeners.remove(advancements);
        }

        @Override
        public void removePlayerListeners(PlayerAdvancements advancements) {
            listeners.remove(advancements);
        }

        @Override
        public Instance createInstance(JsonObject json, DeserializationContext context) {
            return new Instance();
        }

        /** What Lootr's trigger does: run each of the player's listeners whose instance matches. */
        int fire(ServerPlayer player) {
            PlayerAdvancements advancements = player.getAdvancements();
            Set<Listener<Instance>> set = listeners.get(advancements);
            if (set == null) return 0;
            List<Listener<Instance>> matched = new ArrayList<>();
            for (Listener<Instance> listener : set) {
                if (listener.getTriggerInstance() != null) matched.add(listener);
            }
            for (Listener<Instance> listener : matched) listener.run(advancements);
            return matched.size();
        }

        int listening(ServerPlayer player) {
            Set<Listener<Instance>> set = listeners.get(player.getAdvancements());
            return set == null ? 0 : set.size();
        }

        static final class Instance extends AbstractCriterionTriggerInstance {
            Instance() {
                super(ID, ContextAwarePredicate.ANY);
            }
        }
    }
}
