/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.util.AbstractSet;
import java.util.Iterator;

/** An old item set field read as the item tag that replaced it; an item added to the set is added to the tag. */
public final class LegacyTagItemSet extends AbstractSet<Object> {

    private final String tag;

    LegacyTagItemSet(String tag) {
        this.tag = tag;
    }

    @Override
    public boolean contains(Object item) {
        return LegacyAnimalFoodTags.inTag(tag, item);
    }

    // A tag pack can only add, so removing through the iterator is unsupported.
    @Override
    public Iterator<Object> iterator() {
        return LegacyAnimalFoodTags.members(tag).iterator();
    }

    @Override
    public int size() {
        return LegacyAnimalFoodTags.members(tag).size();
    }

    @Override
    public boolean add(Object item) {
        return LegacyAnimalFoodTags.addItem(tag, item);
    }
}
