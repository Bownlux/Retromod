/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** #364: Forge 1.19.4 tab registration replayed on RegisterEvent, with stand-ins for the Minecraft side. */
class LegacyCreativeTabEventsTest {

    @Test
    @DisplayName("A tab is built once, registered only on the tab registry's turn, and placed after the spawn eggs")
    void registersOnceAfterSpawnEggs() {
        Event blocks = new Event("blocks");
        Event tabs = new Event("creative_mode_tab");
        List<Object> configured = new ArrayList<>();

        Object first = LegacyCreativeTabEvents.register(Calls.class, blocks, "mod:tab", null, null, configured::add);
        Object second = LegacyCreativeTabEvents.register(Calls.class, tabs, "mod:tab", null, null, configured::add);
        Object third = LegacyCreativeTabEvents.register(Calls.class, tabs, "mod:tab", null, null, configured::add);

        assertSame(first, second);
        assertSame(first, third);
        assertEquals(1, configured.size(), "the mod's configurator runs once");
        assertEquals(List.of(), blocks.registered);
        assertEquals(List.of("mod:tab"), tabs.registered, "registered once, on the creative tab registry's turn");
        Builder builder = (Builder) configured.get(0);
        assertEquals(List.of("minecraft:spawn_eggs"), builder.tabsBefore, "spawn eggs come before an unordered 1.19.4 tab");
        assertEquals(List.of(), builder.tabsAfter);
    }

    @Test
    @DisplayName("1.19.4's afterEntries become the tabs before it, and its beforeEntries the tabs after it")
    void orderedListsSwapNames() {
        List<Object> configured = new ArrayList<>();
        LegacyCreativeTabEvents.register(Calls.class, new Event("creative_mode_tab"), "mod:ordered",
                List.of("minecraft:combat"), List.of("minecraft:tools_and_utilities"), configured::add);

        Builder builder = (Builder) configured.get(0);
        assertEquals(List.of("minecraft:tools_and_utilities"), builder.tabsBefore);
        assertEquals(List.of("minecraft:combat"), builder.tabsAfter);
    }

    /** The generated calls class, over the stand-ins. */
    public static final class Calls {
        public static Builder builder() { return new Builder(); }
        public static Object build(Builder builder) { return new Object(); }
        public static boolean isTabRegistry(Object key) { return "creative_mode_tab".equals(key); }
        public static String id(Object entry) { return entry == null ? null : entry.toString(); }
    }

    /** A tab builder with Forge's two ordering lists. */
    public static final class Builder {
        final List<String> tabsBefore = new ArrayList<>();
        final List<String> tabsAfter = new ArrayList<>();

        public Builder withTabsBefore(String... ids) { tabsBefore.addAll(List.of(ids)); return this; }
        public Builder withTabsAfter(String... ids) { tabsAfter.addAll(List.of(ids)); return this; }
    }

    /** RegisterEvent for one registry. */
    public static final class Event {
        final String key;
        final List<Object> registered = new ArrayList<>();

        Event(String key) { this.key = key; }

        public Object getRegistryKey() { return key; }
        public void register(Object registry, Object id, Supplier<?> value) {
            assertNotNull(value.get());
            registered.add(id);
        }
    }
}
