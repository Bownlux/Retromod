/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.google.common.collect.ImmutableMap;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Property;

import java.util.Map;

/**
 * Regression test for #347: copying a block's properties onto another state.
 *
 * <p>{@code State.getEntries()} returned an {@code ImmutableMap} up to 1.20.4, a {@code Map} from
 * 1.20.5, and a stream of property values from 26.1, so the call below failed with
 * {@code NoSuchMethodError} on every newer host. MCreator mods do this whenever a block turns into
 * another one, such as a cake losing a slice. Every property of the source is moved off its
 * default first, so a copy that silently does nothing still fails.
 */
public class Test25StateEntriesCopy implements Test {
    @Override
    public String description() {
        return "#347 BlockState.getEntries() copies a block's properties";
    }

    @Override
    public TestResult run() {
        BlockState source = Blocks.OAK_STAIRS.getDefaultState();
        for (Property<?> property : source.getProperties()) {
            source = source.cycle(property);
        }
        BlockState copy = Blocks.OAK_STAIRS.getDefaultState();
        ImmutableMap<Property<?>, Comparable<?>> entries = source.getEntries();
        for (Map.Entry<Property<?>, Comparable<?>> entry : entries.entrySet()) {
            copy = with(copy, entry.getKey(), entry.getValue());
        }
        if (copy == Blocks.OAK_STAIRS.getDefaultState()) {
            return TestResult.fail("no property was copied from " + source);
        }
        return copy == source ? TestResult.success() : TestResult.fail("copied " + copy + ", expected " + source);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, Property property, Comparable value) {
        return state.with(property, value);
    }
}
