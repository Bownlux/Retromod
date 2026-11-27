/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.event;

import net.minecraft.world.item.CreativeModeTab.TabVisibility;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.MutableHashedLinkedMap;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Forge 1.20.1's creative tab entry map, as a NeoForge map that writes through to the creative tab event, which places entries itself. */
public final class LegacyTabEntries extends MutableHashedLinkedMap<ItemStack, TabVisibility> {

    private final BuildCreativeModeTabContentsEvent event;

    private LegacyTabEntries(BuildCreativeModeTabContentsEvent event) {
        this.event = event;
    }

    /** {@code BuildCreativeModeTabContentsEvent.getEntries()}: a view that adds, moves and removes through the event. */
    public static MutableHashedLinkedMap<ItemStack, TabVisibility> of(BuildCreativeModeTabContentsEvent event) {
        return new LegacyTabEntries(event);
    }

    /** Adds the stack at the end; a stack already listed keeps its place. */
    @Override
    public TabVisibility put(ItemStack stack, TabVisibility visibility) {
        if (!contains(stack)) event.accept(stack, visibility(visibility));
        return null;
    }

    /** Moves or adds the stack to the front. */
    @Override
    public TabVisibility putFirst(ItemStack stack, TabVisibility visibility) {
        removeIfListed(stack);
        event.insertFirst(stack, visibility(visibility));
        return null;
    }

    /** Moves or adds the stack after another, or to the end when that one is not listed. */
    @Override
    public TabVisibility putAfter(ItemStack after, ItemStack stack, TabVisibility visibility) {
        removeIfListed(stack);
        try {
            event.insertAfter(after, stack, visibility(visibility));
        } catch (IllegalArgumentException notListed) {
            // NeoForge refuses a target missing from a tab the stack goes to, where Forge appended the stack. The refusal
            // can come after the stack joined the other tab, so it is taken out first.
            removeIfListed(stack);
            event.accept(stack, visibility(visibility));
        }
        return null;
    }

    /** Moves or adds the stack before another, or to the end when that one is not listed. */
    @Override
    public TabVisibility putBefore(ItemStack before, ItemStack stack, TabVisibility visibility) {
        removeIfListed(stack);
        try {
            event.insertBefore(before, stack, visibility(visibility));
        } catch (IllegalArgumentException notListed) {
            removeIfListed(stack);
            event.accept(stack, visibility(visibility));
        }
        return null;
    }

    /** Takes the stack out of the tab and the search tab. */
    @Override
    public TabVisibility remove(ItemStack stack) {
        TabVisibility listed = get(stack);
        if (listed != null) event.remove(stack, TabVisibility.PARENT_AND_SEARCH_TABS);
        return listed;
    }

    /** Where the stack shows, or null when neither tab lists it. */
    @Override
    public TabVisibility get(ItemStack stack) {
        boolean parent = event.getParentEntries().contains(stack);
        boolean search = event.getSearchEntries().contains(stack);
        if (parent) return search ? TabVisibility.PARENT_AND_SEARCH_TABS : TabVisibility.PARENT_TAB_ONLY;
        return search ? TabVisibility.SEARCH_TAB_ONLY : null;
    }

    @Override
    public boolean contains(ItemStack stack) {
        return get(stack) != null;
    }

    @Override
    public boolean isEmpty() {
        return event.getParentEntries().isEmpty() && event.getSearchEntries().isEmpty();
    }

    /** The listed stacks in tab order, each with where it shows, then the stacks only the search tab lists. */
    @Override
    public Iterator<Map.Entry<ItemStack, TabVisibility>> iterator() {
        List<Map.Entry<ItemStack, TabVisibility>> entries = new ArrayList<>();
        for (ItemStack stack : event.getParentEntries()) entries.add(new AbstractMap.SimpleImmutableEntry<>(stack, get(stack)));
        for (ItemStack stack : event.getSearchEntries()) {
            if (!event.getParentEntries().contains(stack)) {
                entries.add(new AbstractMap.SimpleImmutableEntry<>(stack, TabVisibility.SEARCH_TAB_ONLY));
            }
        }
        return entries.iterator();
    }

    private void removeIfListed(ItemStack stack) {
        if (contains(stack)) event.remove(stack, TabVisibility.PARENT_AND_SEARCH_TABS);
    }

    private static TabVisibility visibility(TabVisibility value) {
        return value != null ? value : TabVisibility.PARENT_AND_SEARCH_TABS;
    }
}
