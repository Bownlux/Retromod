/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CrossModDependencyResolverTest {

    @Test
    void forgeDependencyOwnerIsComparedAsText() {
        String metadata = """
                modLoader="javafml"
                [[mods]]
                modId="example.*"

                [[dependencies.example_other]]
                modId="wrong"

                [[dependencies."example.*"]]
                modId="right"
                modId="minecraft"

                [[dependencies.example_tail]]
                modId="also_wrong"
                """;

        assertEquals(List.of("right"),
                CrossModDependencyResolver.extractForgeDependencies(metadata, "example.*"));
    }

    @Test
    void forgeCoreDependenciesStayExcluded() {
        String metadata = """
                [[dependencies.sample]]
                modId="minecraft"
                [[dependencies.sample]]
                modId="forge"
                [[dependencies.sample]]
                modId="neoforge"
                [[dependencies.sample]]
                modId="library"
                """;

        assertEquals(List.of("library"),
                CrossModDependencyResolver.extractForgeDependencies(metadata, "sample"));
    }

    @Test
    void forgeDependencyParsingStopsAtAnyFollowingTable() {
        String metadata = """
                [[dependencies.sample]]
                modId="library"

                [[mods]]
                modId="not_a_dependency"

                [properties]
                modId="also_not_a_dependency"
                """;

        assertEquals(List.of("library"),
                CrossModDependencyResolver.extractForgeDependencies(metadata, "sample"));
    }

    @Test
    void forgeDependenciesAreDeduplicatedInDeclarationOrder() {
        String metadata = """
                [[dependencies.sample]]
                modId="first"
                [[dependencies.sample]]
                modId="first"
                [[dependencies.sample]]
                modId="second"
                """;

        assertEquals(List.of("first", "second"),
                CrossModDependencyResolver.extractForgeDependencies(metadata, "sample"));
    }
}
