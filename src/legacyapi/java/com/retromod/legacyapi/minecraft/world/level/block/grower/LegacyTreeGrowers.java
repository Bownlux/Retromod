/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block.grower;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.grower.TreeGrower;

import java.util.Optional;

/** The tree grower constructors of 1.20.3 to 26.2, which 26.3 replaced with weighted lists of tree features. */
public final class LegacyTreeGrowers {

    // A secondary tree replaced the main one with the given chance; whole weights out of this total keep that chance.
    private static final int TOTAL_WEIGHT = 1000;
    private static final String NO_TREE_NAMESPACE = "retromod";
    private static final String NO_TREE_PATH = "legacy_tree_grower_no_tree";

    private LegacyTreeGrowers() {}

    /** {@code new TreeGrower(name, megaTree, tree, flowers)}. */
    public static TreeGrower create(String name, Optional<ResourceKey<?>> megaTree, Optional<ResourceKey<?>> tree,
            Optional<ResourceKey<?>> flowers) {
        return create(name, 0F, megaTree, Optional.empty(), tree, Optional.empty(), flowers, Optional.empty());
    }

    /** {@code new TreeGrower(name, secondaryChance, megaTree, secondaryMegaTree, tree, secondaryTree, flowers, secondaryFlowers)}. */
    public static TreeGrower create(String name, float secondaryChance, Optional<ResourceKey<?>> megaTree,
            Optional<ResourceKey<?>> secondaryMegaTree, Optional<ResourceKey<?>> tree, Optional<ResourceKey<?>> secondaryTree,
            Optional<ResourceKey<?>> flowers, Optional<ResourceKey<?>> secondaryFlowers) {
        int secondary = Math.round(Math.max(0F, Math.min(1F, secondaryChance)) * TOTAL_WEIGHT);
        // The tree checked for room before growing is the one the sapling grows most, as the old grower's was.
        ResourceKey<?> shortest = tree.orElse(flowers.orElse(megaTree.orElse(null)));
        return new TreeGrower(name, list(tree, secondaryTree, secondary), list(megaTree, secondaryMegaTree, secondary),
                list(flowers, secondaryFlowers, secondary), shortest);
    }

    /**
     * The main key weighted against the secondary one; a zero-weight key is left out. When only the secondary is
     * set, the old grower grew nothing on the rolls the secondary lost, so those rolls name a feature that does not
     * exist, which 26.3 grows as nothing.
     */
    private static WeightedList<ResourceKey<?>> list(Optional<ResourceKey<?>> main, Optional<ResourceKey<?>> secondary,
            int secondaryWeight) {
        WeightedList.Builder<ResourceKey<?>> builder = WeightedList.builder();
        int mainWeight = secondary.isPresent() ? TOTAL_WEIGHT - secondaryWeight : TOTAL_WEIGHT;
        Optional<ResourceKey<?>> mainOrNothing = main.isPresent() ? main : secondary.map(LegacyTreeGrowers::noTree);
        if (mainOrNothing.isPresent() && mainWeight > 0) builder.add(mainOrNothing.get(), mainWeight);
        if (secondary.isPresent() && secondaryWeight > 0) builder.add(secondary.get(), secondaryWeight);
        return builder.build();
    }

    /** A key in the same registry as {@code sibling} that no feature uses. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResourceKey<?> noTree(ResourceKey<?> sibling) {
        return ResourceKey.create((ResourceKey) sibling.registryKey(), Identifier.fromNamespaceAndPath(NO_TREE_NAMESPACE, NO_TREE_PATH));
    }
}
