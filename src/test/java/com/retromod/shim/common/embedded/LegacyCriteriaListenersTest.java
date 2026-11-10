/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt trigger listeners never keep a departed player, its advancements, or a stopped server reachable. */
class LegacyCriteriaListenersTest {

    @AfterEach
    void reset() {
        LegacyCriteria.resetListenersForTests();
    }

    @Test
    @DisplayName("Bookkeeping alone keeps no player: advancements whose player points back at them are still collected")
    void bookkeepingHoldsNoPlayer() throws InterruptedException {
        List<WeakReference<Object>> collected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Player player = new Player();
            LegacyCriteria.rememberForTests(player.advancements, List.of());
            collected.add(new WeakReference<>(player.advancements));
        }

        assertEquals(20, cleared(collected), "every entry is collected once nothing else holds its player");
        assertEquals(0, LegacyCriteria.trackedForTests());
    }

    @Test
    @DisplayName("A player who logs out is removed from the mod's trigger, so the trigger lets go of them")
    void loggingOutReleasesThePlayer() throws InterruptedException {
        Trigger trigger = new Trigger();
        Player player = new Player();
        trigger.addPlayerListener(player.advancements, "criterion");
        LegacyCriteria.rememberForTests(player.advancements, List.of(trigger));
        WeakReference<Object> advancements = new WeakReference<>(player.advancements);

        LegacyCriteria.forget(player.advancements);
        player = null;

        assertTrue(trigger.listeners.isEmpty(), "removePlayerListeners ran, as leaving used to run it");
        assertEquals(1, cleared(List.of(advancements)));
    }

    @Test
    @DisplayName("A stopped server's players are all removed from the trigger and collected")
    void stoppingTheServerReleasesEveryone() throws InterruptedException {
        Trigger trigger = new Trigger();
        List<WeakReference<Object>> collected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Player player = new Player();
            trigger.addPlayerListener(player.advancements, "criterion");
            LegacyCriteria.rememberForTests(player.advancements, List.of(trigger));
            collected.add(new WeakReference<>(player.advancements));
        }

        LegacyCriteria.forgetAll();

        assertTrue(trigger.listeners.isEmpty());
        assertEquals(20, cleared(collected), "a stopped integrated server stays unreachable after Save and Quit");
    }

    /** How many of the references the collector clears within a few rounds. */
    private static int cleared(List<WeakReference<Object>> references) throws InterruptedException {
        for (int round = 0; round < 10 && references.stream().anyMatch(r -> r.get() != null); round++) {
            System.gc();
            Thread.sleep(50);
        }
        return (int) references.stream().filter(r -> r.get() == null).count();
    }

    /** A player and its advancements, each holding the other, as ServerPlayer and PlayerAdvancements do. */
    static final class Player {
        final Advancements advancements = new Advancements(this);
        final byte[] weight = new byte[1 << 16];
    }

    static final class Advancements {
        final Player player;

        Advancements(Player player) {
            this.player = player;
        }
    }

    /** Lootr's trigger shape: listeners kept per player's advancements, strongly. */
    public static final class Trigger {
        final Map<Object, Set<Object>> listeners = new HashMap<>();

        public void addPlayerListener(Object advancements, Object listener) {
            listeners.computeIfAbsent(advancements, key -> new HashSet<>()).add(listener);
        }

        public void removePlayerListeners(Object advancements) {
            listeners.remove(advancements);
        }
    }
}
