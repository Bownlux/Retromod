/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.Codec;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A trigger that implements CriterionTrigger itself and keeps its own listeners, as Lootr's does.
 * 26.2 removed the listener. Its advancement has one criterion, so firing once completes it and leaves
 * no listener. The result needs a player, so it is in the log rather than the summary.
 */
final class ListenerTriggerCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");
    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, "retromod_test_mod_neoforge");
    static final ListenerTrigger TRIGGER = new ListenerTrigger();

    private ListenerTriggerCase() {}

    static void register(IEventBus modBus) {
        TRIGGERS.register("legacy_listener", () -> TRIGGER);
        TRIGGERS.register(modBus);
        NeoForge.EVENT_BUS.addListener(ListenerTriggerCase::fireOnLogin);
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
        LOG.info("[Retromod-Test-NeoForge] An old trigger with its own listeners awards its criterion (player logged in): {}",
                result);
    }

    record Instance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {}

    static final class ListenerTrigger implements CriterionTrigger<Instance> {
        private final Map<PlayerAdvancements, Set<Listener<Instance>>> listeners = new HashMap<>();

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
        public Codec<Instance> codec() {
            return Codec.unit(new Instance(Optional.empty()));
        }

        /** What Lootr's trigger does: run each of the player's listeners whose instance matches. */
        int fire(ServerPlayer player) {
            PlayerAdvancements advancements = player.getAdvancements();
            Set<Listener<Instance>> set = listeners.get(advancements);
            if (set == null) return 0;
            List<Listener<Instance>> matched = new ArrayList<>();
            for (Listener<Instance> listener : set) {
                if (listener.trigger() != null) matched.add(listener);
            }
            for (Listener<Instance> listener : matched) listener.run(advancements);
            return matched.size();
        }

        int listening(ServerPlayer player) {
            Set<Listener<Instance>> set = listeners.get(player.getAdvancements());
            return set == null ? 0 : set.size();
        }
    }
}
