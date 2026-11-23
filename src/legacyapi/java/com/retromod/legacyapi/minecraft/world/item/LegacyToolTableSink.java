/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.shim.common.embedded.LegacyLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Hands each entry an old mod adds to a tool table to the host's block transformers, so the axe, shovel or hoe uses it. */
final class LegacyToolTableSink {

    static final String STRIP = "strip";
    static final String FLATTEN = "flatten";
    static final String TILL = "till";

    // Every mod embeds its own copy of this class, so the entries live in shared slots that hold only JDK types.
    private static final String SLOT = "minecraft.legacyToolTables.";
    private static final int PROTOCOL = 1;
    // This copy's view of each shared table, so its puts reach the host.
    private static final Map<String, Map<Object, Object>> VIEWS = new ConcurrentHashMap<>();

    private LegacyToolTableSink() {}

    /** This copy's live view of the one table of a kind every copy shares. */
    static Map<Object, Object> table(String kind) {
        return VIEWS.computeIfAbsent(kind, unused -> new LiveTable(kind, shared(kind)));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> shared(String kind) {
        return LegacySharedSlots.get(SLOT + kind, PROTOCOL, Map.class,
                () -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    /** Adds what a replacement map holds beyond the live table; the field keeps pointing at the live table. */
    static void absorb(String kind, Map<?, ?> replacement) {
        Map<Object, Object> live = table(kind);
        if (replacement == null || replacement == live) return;
        replacement.forEach((input, output) -> {
            if (!java.util.Objects.equals(live.get(input), output)) live.put(input, output);
        });
    }

    /** A view whose puts also reach the host; removal only forgets the entry, since the host keeps it until restart. */
    private static final class LiveTable extends AbstractMap<Object, Object> {
        private final String kind;
        // Shared by every copy and synchronized on itself, so all copies lock the same object.
        private final Map<Object, Object> entries;

        LiveTable(String kind, Map<Object, Object> entries) {
            this.kind = kind;
            this.entries = entries;
        }

        @Override
        public Object put(Object input, Object output) {
            Object previous = entries.put(input, output);
            // Outside the table's lock, since registering with the host can take a while.
            if (input != null && output != null) apply(kind, input, output);
            return previous;
        }

        @Override
        public Object get(Object input) {
            return entries.get(input);
        }

        @Override
        public boolean containsKey(Object input) {
            return entries.containsKey(input);
        }

        @Override
        public Object remove(Object input) {
            return entries.remove(input);
        }

        @Override
        public void clear() {
            entries.clear();
        }

        @Override
        public int size() {
            return entries.size();
        }

        /** A copy taken under the table's lock, so iterating never meets another mod's put halfway. */
        @Override
        public Set<Entry<Object, Object>> entrySet() {
            synchronized (entries) {
                return Collections.unmodifiableSet(new LinkedHashMap<>(entries).entrySet());
            }
        }
    }

    /** Registers one entry with whichever loader can take it. */
    static void apply(String kind, Object input, Object output) {
        Object target = target(kind, input, output);
        if (target == null) return;
        ClassLoader loader = input.getClass().getClassLoader();
        try {
            if (exists("net.fabricmc.fabric.api.item.v1.BlockTransformerHelper", loader)) {
                applyFabric(kind, input, target, loader);
            } else if (exists("net.neoforged.neoforge.common.DataMapHooks", loader)) {
                applyNeoForge(kind, input, target, loader);
            } else {
                LegacyLog.warnOnce("legacy-tool-table:" + kind, "An old mod added " + describe(kind)
                        + " that this loader has no way to register on Minecraft 26.3, so those tools ignore it."
                        + " A version of the mod built for 26.3 is needed for it.");
            }
        } catch (ReflectiveOperationException | RuntimeException failed) {
            LegacyLog.warnOnce("legacy-tool-table-failed:" + kind, "Retromod could not register an old mod's "
                    + describe(kind) + " (" + failed + "), so those tools ignore it.");
        }
    }

    /** The block or state the input turns into, or null when the entry cannot be expressed as one. */
    private static Object target(String kind, Object input, Object output) {
        if (!kind.equals(TILL)) return output;
        // A tillable is a pair of a use check and an action; only the old helpers' actions name a state.
        Object action = invoke(output, "getSecond");
        Object state = action == null ? null : invoke(action, "legacyTilledState");
        if (state == null && action != null) state = invoke(action, "state");
        if (state == null) {
            LegacyLog.warnOnce("legacy-tool-table-custom-till:" + input, "An old mod tills " + input
                    + " with its own hoe action, which Minecraft 26.3 cannot run, so hoes leave it alone.");
        }
        return state;
    }

    private static void applyFabric(String kind, Object input, Object target, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> helper = Class.forName("net.fabricmc.fabric.api.item.v1.BlockTransformerHelper", true, loader);
        Class<?> block = Class.forName("net.minecraft.world.level.block.Block", false, loader);
        Class<?> state = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
        Class<?> targetType = block.isInstance(target) ? block : state;
        helper.getMethod(fabricMethod(kind), block, targetType).invoke(null, input, target);
    }

    private static String fabricMethod(String kind) {
        return switch (kind) {
            case STRIP -> "registerStripping";
            case FLATTEN -> "registerFlattening";
            default -> "registerTilling";
        };
    }

    /**
     * NeoForge 26.3 reads mod tool transforms from a table it refills from data maps on every reload, so each entry is
     * kept here and put back after every refill.
     */
    private static void applyNeoForge(String kind, Object input, Object target, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> block = Class.forName("net.minecraft.world.level.block.Block", false, loader);
        Object targetBlock = block.isInstance(target) ? target : invoke(target, "getBlock");
        Class<?> transformable = Class.forName("net.neoforged.neoforge.registries.datamaps.builtin.Transformable", true, loader);
        Object built = transformable.getMethod(neoFactory(kind), block, block).invoke(null, input, targetBlock);
        Map<?, ?> transformers = (Map<?, ?>) transformable.getMethod("transformers").invoke(built);
        List<Object[]> pending = neoEntries();
        synchronized (pending) {
            transformers.forEach((key, data) -> pending.add(new Object[] {key, data}));
        }
        listenForReloads(loader);
        addNeoForgeEntries(loader);
    }

    private static String neoFactory(String kind) {
        return switch (kind) {
            case STRIP -> "stripping";
            case FLATTEN -> "flattening";
            default -> "tilling";
        };
    }

    /** The entries every copy has handed NeoForge, synchronized on the list itself. */
    @SuppressWarnings("unchecked")
    private static List<Object[]> neoEntries() {
        return LegacySharedSlots.get(SLOT + "neoforgeEntries", PROTOCOL, List.class,
                () -> Collections.synchronizedList(new ArrayList<Object[]>()));
    }

    /** Puts every kept entry into NeoForge's table, skipping ones already there. */
    @SuppressWarnings("unchecked")
    private static void addNeoForgeEntries(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> hooks = Class.forName("net.neoforged.neoforge.common.DataMapHooks", true, loader);
        Field field = hooks.getDeclaredField("DATAMAP_BLOCK_TRANSFORMERS");
        field.setAccessible(true);
        Map<Object, List<Object>> table = (Map<Object, List<Object>>) field.get(null);
        List<Object[]> pending = neoEntries();
        synchronized (pending) {
            for (Object[] entry : pending) {
                List<Object> list = table.computeIfAbsent(entry[0], unused -> new ArrayList<>());
                if (!list.contains(entry[1])) list.add(entry[1]);
            }
        }
    }

    /** After NeoForge refills its table on a reload, the kept entries go back in; one listener serves every mod. */
    private static void listenForReloads(ClassLoader loader) throws ReflectiveOperationException {
        AtomicBoolean listening = LegacySharedSlots.get(SLOT + "neoforgeListening", PROTOCOL, AtomicBoolean.class,
                AtomicBoolean::new);
        if (!listening.compareAndSet(false, true)) return;
        Class<?> event = Class.forName("net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent", true, loader);
        Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority", true, loader);
        Object lowest = priority.getField("LOWEST").get(null);
        Object bus = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader).getField("EVENT_BUS").get(null);
        Consumer<Object> listener = unused -> {
            try {
                addNeoForgeEntries(loader);
            } catch (ReflectiveOperationException | RuntimeException failed) {
                LegacyLog.warnOnce("legacy-tool-table-reload", "Retromod could not put old mods' tool table entries back"
                        + " after a data reload (" + failed + "), so those tools ignore them until restart.");
            }
        };
        Method add = Class.forName("net.neoforged.bus.api.IEventBus", true, loader)
                .getMethod("addListener", priority, Class.class, Consumer.class);
        add.invoke(bus, lowest, event, listener);
    }

    private static boolean exists(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static String describe(String kind) {
        return switch (kind) {
            case STRIP -> "an axe stripping entry";
            case FLATTEN -> "a shovel path entry";
            default -> "a hoe tilling entry";
        };
    }
}
