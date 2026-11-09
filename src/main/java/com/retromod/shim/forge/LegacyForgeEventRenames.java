/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Sends old Forge events that Forge 26.2 removed to the host event that took over their job. */
public final class LegacyForgeEventRenames {

    static final String ENTITY_INTERACT = "net/minecraftforge/event/entity/player/PlayerInteractEvent$EntityInteract";
    static final String ENTITY_INTERACT_SPECIFIC =
            "net/minecraftforge/event/entity/player/PlayerInteractEvent$EntityInteractSpecific";
    static final String SAPLING_GROW_TREE = "net/minecraftforge/event/level/SaplingGrowTreeEvent";
    static final String BLOCK_FEATURE_GROW = "net/minecraftforge/event/level/BlockFeatureGrowEvent";

    /** Old event and the host event that replaced it, checked with javap on Forge 65.0.0 and 66.1.2. */
    static final List<String[]> RENAMES = List.of(
            // 26.2 posts only EntityInteractSpecific. The server posts it once per click, but the client
            // posts it twice when nothing cancels it, from the game mode and again from Player.interactOn,
            // where 1.20.1 posted EntityInteract once. Native mods see that host behavior, so it stays.
            // getTarget, getEntity, getHand, getItemStack, getLevel and setCancellationResult keep
            // their descriptors.
            new String[] {ENTITY_INTERACT, ENTITY_INTERACT_SPECIFIC},
            // Same constructor, getters, setFeature overloads and ForgeEventFactory.blockGrowFeature,
            // posted by the same saplings, mushrooms and fungi.
            new String[] {SAPLING_GROW_TREE, BLOCK_FEATURE_GROW});

    private LegacyForgeEventRenames() {}

    /** Registers each rename where the Forge host has the new event and lacks the old one; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        for (String[] rename : RENAMES) {
            // Each event is probed on its own, so a host that still ships one keeps it.
            if (ForgeHostShape.matches(List.of(rename[1]), List.of(rename[0]),
                    ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
                t.registerClassRedirect(rename[0], rename[1]);
            }
        }
    }
}
