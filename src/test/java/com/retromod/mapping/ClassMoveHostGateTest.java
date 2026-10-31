/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** NeoForge and Forge apply the 26.1 class moves only where the host proves a move is needed. */
class ClassMoveHostGateTest {

    private static final String OLD = "net/minecraft/advancements/critereon/SimpleCriterionTrigger";
    private static final String MOVED_IN_26_1 = "net/minecraft/advancements/criterion/SimpleCriterionTrigger";

    private static Predicate<String> host(String... classes) {
        return Set.of(classes)::contains;
    }

    @Test
    @DisplayName("A host with the old name keeps it, the #9 hazard")
    void oldNameOnHostIsKept() {
        assertFalse(IntermediaryToMojangMapper.appliesOnHost(OLD, MOVED_IN_26_1, host(OLD), false));
        assertFalse(IntermediaryToMojangMapper.appliesOnHost(OLD, MOVED_IN_26_1, host(OLD), true));
    }

    @Test
    @DisplayName("26.1 has the moved name, so the move applies")
    void newNameOnHostApplies() {
        assertTrue(IntermediaryToMojangMapper.appliesOnHost(OLD, MOVED_IN_26_1, host(MOVED_IN_26_1), true));
    }

    @Test
    @DisplayName("26.2 moved the class again, so the first hop still applies for the 26.2 move to finish")
    void movedAgainOnNewerHostStillApplies() {
        String movedIn26_2 = "net/minecraft/advancements/triggers/SimpleCriterionTrigger";
        assertTrue(IntermediaryToMojangMapper.appliesOnHost(OLD, MOVED_IN_26_1, host(movedIn26_2), true));
    }

    @Test
    @DisplayName("A pre-26.1 host without either name is left alone")
    void olderHostWithoutEitherNameIsLeftAlone() {
        assertFalse(IntermediaryToMojangMapper.appliesOnHost(OLD, MOVED_IN_26_1, host(), false));
    }
}
