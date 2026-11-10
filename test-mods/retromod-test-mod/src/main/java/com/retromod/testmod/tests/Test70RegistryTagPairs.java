/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.mojang.datafixers.util.Pair;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.util.Optional;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;

/** Every tag of a registry read as (tag key, named set) pairs, as REI and Inventory Profiles Next read them; 1.21.2 streams the sets alone. */
public final class Test70RegistryTagPairs implements Test {

    @Override
    public String description() {
        return "Registry.streamTagsAndEntries() streams (tag key, named set) pairs once the server's tags are bound";
    }

    @Override
    public TestResult run() {
        Optional<Pair<TagKey<Item>, RegistryEntryList.Named<Item>>> first = Registries.ITEM.streamTagsAndEntries().findFirst();
        if (first.isEmpty()) return TestResult.fail("the item registry listed no tags once the server started");
        Object key = first.get().getFirst();
        Object set = first.get().getSecond();
        if (!(key instanceof TagKey<?>) || !(set instanceof RegistryEntryList.Named<?>)) {
            return TestResult.fail("the pair held " + key + " and " + set);
        }
        return ((RegistryEntryList.Named<?>) set).getTag().equals(key) ? TestResult.success()
                : TestResult.fail("the key " + key + " is not its set's");
    }
}
