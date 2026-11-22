/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.LegacyHiddenTooltipBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A mod built for 1.21.4 that hides an item's extra tooltip lines builds valid items on hosts without that component. */
class LegacyHiddenTooltipBridgeTest {

    private static final String HOST = "net/minecraft/core/component/Host";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The old flag is dropped from a component builder and an item, and their other components stay")
    void oldFlagIsDropped(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("rarity", run.call(HOST, "keys", run.call("mod/Banner", "components")),
                "only the components the host can save are built");
        assertEquals("rarity", run.call(HOST, "keys", run.call("mod/Banner", "stack")));
    }

    @Test
    @DisplayName("An item's default components skip the old flag, so the host never asks the stand-in for a codec")
    void itemPropertiesSkipTheOldFlag(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("rarity", run.call(HOST, "keys", run.call("mod/Banner", "properties")));
    }

    @Test
    @DisplayName("A mod built for 1.21.5 or later keeps its own set calls")
    void newerModsKeepTheirCalls(@TempDir Path dir) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        register(transformer, name -> null);
        transformer.setModSourceVersion("1.21.5");
        byte[] banner = BridgeHarness.only(BridgeHarness.compile(dir, withShared(OLD_1214)), "mod/").get("mod/Banner");
        byte[] out = transformer.transformClass(banner, "mod/Banner");
        assertTrue(BridgeHarness.calls(out, "components").contains(BUILDER + ".set" + BUILDER_SET));
    }

    @Test
    @DisplayName("The bridge registers only where the host removed the component; offline the target decides")
    void followsTheHost() {
        ClassNode oldHost = new ClassNode();
        oldHost.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, FIELD, TYPE, null, null));
        assertFalse(hostRemovedIt(name -> name.equals(COMPONENTS) ? oldHost : null), "1.21.4");
        assertTrue(hostRemovedIt(name -> name.equals(COMPONENTS) ? new ClassNode() : null), "1.21.5 and newer");
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        assertFalse(hostRemovedIt(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.5";
        assertTrue(hostRemovedIt(name -> null));
    }

    private BridgeHarness.Run run(Path dir, String modVersion) throws Exception {
        Consumer<RetromodTransformer> register = t -> {
            register(t, name -> null);
            t.setModSourceVersion(modVersion);
        };
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", register,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), withShared(OLD_1214)), "mod/"));
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), withShared(HOST_263)), mod);
    }

    private static Map<String, String> withShared(Map<String, String> sources) {
        Map<String, String> all = new HashMap<>(SHARED);
        all.putAll(sources);
        return all;
    }

    /** Types the same on 1.21.4 and 26.3: a component type, a component map builder, and an item stack holding components. */
    private static final Map<String, String> SHARED = Map.of(
            "net/minecraft/util/Unit.java", """
                    package net.minecraft.util;
                    public enum Unit { INSTANCE }""",
            "net/minecraft/core/component/DataComponentType.java", """
                    package net.minecraft.core.component;
                    public interface DataComponentType<T> { String name(); }""",
            "net/minecraft/core/component/DataComponentMap.java", """
                    package net.minecraft.core.component;
                    import java.util.LinkedHashMap;
                    import java.util.Map;
                    public interface DataComponentMap {
                        Map<DataComponentType<?>, Object> values();
                        static Builder builder() { return new Builder(); }
                        class Builder {
                            private final Map<DataComponentType<?>, Object> values = new LinkedHashMap<>();
                            public <T> Builder set(DataComponentType<T> type, T value) { values.put(type, value); return this; }
                            public DataComponentMap build() { return () -> values; }
                        }
                    }""",
            "net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    import java.util.LinkedHashMap;
                    import java.util.Map;
                    import net.minecraft.core.component.DataComponentMap;
                    import net.minecraft.core.component.DataComponentType;
                    public final class ItemStack implements DataComponentMap {
                        private final Map<DataComponentType<?>, Object> values = new LinkedHashMap<>();
                        public Map<DataComponentType<?>, Object> values() { return values; }
                        @SuppressWarnings("unchecked")
                        public <T> T set(DataComponentType<T> type, T value) { return (T) values.put(type, value); }
                    }""",
            "net/minecraft/world/item/Item.java", """
                    package net.minecraft.world.item;
                    import java.util.LinkedHashMap;
                    import java.util.Map;
                    import net.minecraft.core.component.DataComponentMap;
                    import net.minecraft.core.component.DataComponentType;
                    public class Item {
                        public static class Properties implements DataComponentMap {
                            private final Map<DataComponentType<?>, Object> values = new LinkedHashMap<>();
                            public Map<DataComponentType<?>, Object> values() { return values; }
                            public <T> Properties component(DataComponentType<T> type, T value) { values.put(type, value); return this; }
                        }
                    }""");

    /** 1.21.4's components, and a mod that hides a banner's pattern lines as YUNG's API does. */
    private static final Map<String, String> OLD_1214 = Map.of(
            "net/minecraft/core/component/DataComponents.java", """
                    package net.minecraft.core.component;
                    import net.minecraft.util.Unit;
                    public final class DataComponents {
                        public static final DataComponentType<Unit> HIDE_ADDITIONAL_TOOLTIP = () -> "hide_additional_tooltip";
                        public static final DataComponentType<String> RARITY = () -> "rarity";
                    }""",
            "mod/Banner.java", """
                    package mod;
                    import net.minecraft.core.component.DataComponentMap;
                    import net.minecraft.core.component.DataComponents;
                    import net.minecraft.util.Unit;
                    import net.minecraft.world.item.ItemStack;
                    public class Banner {
                        public static Object components() {
                            DataComponentMap.Builder builder = DataComponentMap.builder();
                            builder.set(DataComponents.RARITY, "rare");
                            builder.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
                            return builder.build();
                        }
                        public static Object properties() {
                            return new net.minecraft.world.item.Item.Properties().component(DataComponents.RARITY, "rare")
                                    .component(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
                        }
                        public static Object stack() {
                            ItemStack stack = new ItemStack();
                            stack.set(DataComponents.RARITY, "rare");
                            stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
                            return stack;
                        }
                    }""");

    /** 26.3's components, without the old one, and a host class that lists what a map would save. */
    private static final Map<String, String> HOST_263 = Map.of(
            "net/minecraft/core/component/DataComponents.java", """
                    package net.minecraft.core.component;
                    public final class DataComponents {
                        public static final DataComponentType<String> RARITY = () -> "rarity";
                    }""",
            "net/minecraft/core/component/Host.java", """
                    package net.minecraft.core.component;
                    import java.util.stream.Collectors;
                    public final class Host {
                        public static String keys(Object map) {
                            return ((DataComponentMap) map).values().keySet().stream().map(DataComponentType::name)
                                    .collect(Collectors.joining(","));
                        }
                    }""");
}
