/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.neoforge.LegacyBurnTimeBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** NeoForge 21.1's getBurnTime reads answer on NeoForge 21.2 to 26.2, which want fuel values, and on 26.3, which reads a component. */
class LegacyBurnTimeBridgeTest {

    private static final String EXTENSION = "net/neoforged/neoforge/common/extensions/IItemStackExtension";
    private static final String OLD_READ = "(Lnet/minecraft/world/item/crafting/RecipeType;)I";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On 26.3 a stack read returns its fuel component's number, 0 for no fuel or an empty stack, and a mod item's own answer")
    void componentHostAnswersOldReads(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, HOST_263, host("26.3"));
        assertEquals(1600, run.call("mod/Calls", "stack", run.call("mod/Make", "fuel", 1600, false)));
        assertEquals(0, run.call("mod/Calls", "stack", run.call("mod/Make", "plain", false)), "an item with no fuel component");
        assertEquals(0, run.call("mod/Calls", "stack", run.call("mod/Make", "fuel", 1600, true)), "an empty stack never burns");
        assertEquals(300, run.call("mod/Calls", "stack", run.call("mod/Make", "modFuel")), "the mod item's own override still answers");
        assertEquals(301, run.call("mod/Calls", "stack", run.call("mod/Make", "superFuel", 300)),
                "an override's super call reads the component, not the override again");
        assertEquals(1600, run.call("mod/Calls", "item", run.call("mod/Make", "fuel", 1600, false)), "the item read on Item");
    }

    @Test
    @DisplayName("On 26.3 a data pack provider with no running server reads 0, as there is no world to compute it in")
    void componentHostWithoutServer(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, HOST_263, host("26.3"));
        assertEquals(0, run.call("mod/Calls", "stack", run.call("mod/Make", "provider")));
    }

    @Test
    @DisplayName("On NeoForge 21.2 to 26.2 a stack read asks the host with the running server's fuel values")
    void fuelValuesHostAnswersOldReads(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, HOST_262, host("26.2"));
        assertEquals(1600, run.call("mod/Calls", "stack", run.call("mod/Make", "fuel", 1600, false)));
        assertEquals(0, run.call("mod/Calls", "stack", run.call("mod/Make", "fuel", 1600, true)));
        assertEquals(300, run.call("mod/Calls", "stack", run.call("mod/Make", "modFuel")));
        assertEquals(1601, run.call("mod/Calls", "stack", run.call("mod/Make", "superFuel", 1600)));
    }

    @Test
    @DisplayName("On NeoForge 21.2 to 26.2 a furnace's fuel values read reaches a mod item's old override, and a negative answer falls back")
    void fuelValuesFurnaceReachesOldOverride(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, HOST_262, host("26.2"));
        assertEquals(300, run.call("net/minecraft/world/level/block/entity/Furnace", "burn", run.call("mod/Make", "modFuel")),
                "the furnace calls the fuel values form, which now answers through the 2-argument override");
        assertEquals(1600, run.call("net/minecraft/world/level/block/entity/Furnace", "burn", run.call("mod/Make", "defaultFuel", 1600)),
                "a negative from the old override means the default");
        assertEquals(1600, run.call("net/minecraft/world/level/block/entity/Furnace", "burn", run.call("mod/Make", "fuel", 1600, false)),
                "a host item keeps the host's answer");
    }

    @Test
    @DisplayName("Only NeoForge 21.2 to 26.2 hosts give an old override the furnace form; 26.3 furnaces read only the component")
    void furnaceFormFollowsTheHost(@TempDir Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), OLD_1211), "mod/");
        String furnaceForm = "getBurnTime(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;"
                + "Lnet/minecraft/world/level/block/entity/FuelValues;)I";
        for (String version : new String[] {"26.2", "26.3"}) {
            Map<String, byte[]> mod = BridgeHarness.transform(version, t -> LegacyBurnTimeBridge.register(t, host(version)), old);
            ClassNode item = new ClassNode();
            new org.objectweb.asm.ClassReader(mod.get("mod/FuelItem")).accept(item, 0);
            boolean added = item.methods.stream().anyMatch(m -> (m.name + m.desc).equals(furnaceForm));
            assertEquals(version.equals("26.2"), added, version);
            ClassNode calls = new ClassNode();
            new org.objectweb.asm.ClassReader(mod.get("mod/Calls")).accept(calls, 0);
            assertTrue(calls.methods.stream().noneMatch(m -> (m.name + m.desc).equals(furnaceForm)), "only items that declare the old form");
        }
    }

    @Test
    @DisplayName("Each host shape gets its own helper, a host that kept the read gets none, and offline the target decides")
    void followsTheHost() {
        assertNull(LegacyBurnTimeBridge.helperFor(host("1.21.1")), "NeoForge 21.1 still has the read");
        assertEquals(LegacyBurnTimeBridge.FUEL_VALUES_HELPER, LegacyBurnTimeBridge.helperFor(host("26.2")));
        assertEquals(LegacyBurnTimeBridge.COMPONENT_HELPER, LegacyBurnTimeBridge.helperFor(host("26.3")));
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertNull(LegacyBurnTimeBridge.helperFor(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertEquals(LegacyBurnTimeBridge.FUEL_VALUES_HELPER, LegacyBurnTimeBridge.helperFor(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertEquals(LegacyBurnTimeBridge.COMPONENT_HELPER, LegacyBurnTimeBridge.helperFor(name -> null));
    }

    private BridgeHarness.Run run(Path dir, Map<String, String> hostSources, Function<String, ClassNode> host) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> LegacyBurnTimeBridge.register(t, host),
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), OLD_1211), "mod/"));
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), hostSources), mod);
    }

    /** The classes the gate reads on a NeoForge host of {@code version}. */
    private static Function<String, ClassNode> host(String version) {
        Map<String, ClassNode> classes = new HashMap<>();
        ClassNode extension = node(EXTENSION);
        if (version.equals("1.21.1")) extension.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getBurnTime", OLD_READ, null, null));
        classes.put(EXTENSION, extension);
        if (version.equals("26.2")) classes.put("net/minecraft/world/level/block/entity/FuelValues", node("net/minecraft/world/level/block/entity/FuelValues"));
        ClassNode components = node("net/minecraft/core/component/DataComponents");
        if (version.equals("26.3")) {
            components.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "COOKING_FUEL",
                    "Lnet/minecraft/core/component/DataComponentType;", null, null));
        }
        classes.put(components.name, components);
        return classes::get;
    }

    private static ClassNode node(String name) {
        ClassNode node = new ClassNode();
        node.name = name;
        return node;
    }

    /** NeoForge 21.1's shape, with mod code that reads burn times and a fuel item of its own. */
    private static final Map<String, String> OLD_1211 = Map.of(
            "net/minecraft/world/item/crafting/RecipeType.java", """
                    package net.minecraft.world.item.crafting;
                    public interface RecipeType<T> {}""",
            "net/minecraft/world/item/Item.java", """
                    package net.minecraft.world.item;
                    import net.minecraft.world.item.crafting.RecipeType;
                    public class Item {
                        public int getBurnTime(ItemStack stack, RecipeType<?> type) { return 0; }
                    }""",
            "net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    import net.minecraft.world.item.crafting.RecipeType;
                    public final class ItemStack {
                        public Item getItem() { return null; }
                        public int getBurnTime(RecipeType<?> type) { return 0; }
                    }""",
            "mod/FuelItem.java", """
                    package mod;
                    import net.minecraft.world.item.*;
                    import net.minecraft.world.item.crafting.RecipeType;
                    public class FuelItem extends Item {
                        @Override public int getBurnTime(ItemStack stack, RecipeType<?> type) { return 300; }
                    }""",
            "mod/SuperFuelItem.java", """
                    package mod;
                    import net.minecraft.world.item.*;
                    import net.minecraft.world.item.crafting.RecipeType;
                    public class SuperFuelItem extends Item {
                        @Override public int getBurnTime(ItemStack stack, RecipeType<?> type) { return super.getBurnTime(stack, type) + 1; }
                    }""",
            "mod/DefaultFuelItem.java", """
                    package mod;
                    import net.minecraft.world.item.*;
                    import net.minecraft.world.item.crafting.RecipeType;
                    public class DefaultFuelItem extends Item {
                        @Override public int getBurnTime(ItemStack stack, RecipeType<?> type) { return -1; }
                    }""",
            "mod/Calls.java", """
                    package mod;
                    import net.minecraft.world.item.ItemStack;
                    public class Calls {
                        public static int stack(ItemStack stack) { return stack.getBurnTime(null); }
                        public static int item(ItemStack stack) { return stack.getItem().getBurnTime(stack, null); }
                    }""");

    /** A stand-in for each host's stacks, with mod items built on that host's Item. */
    private static final String MAKE = """
            package mod;
            import net.minecraft.world.item.*;
            public class Make {
                public static ItemStack fuel(int burn, boolean empty) { return ItemStack.fuel(new Item(), burn, empty); }
                public static ItemStack plain(boolean empty) { return ItemStack.plain(new Item(), empty); }
                public static ItemStack modFuel() { return ItemStack.plain(new FuelItem(), false); }
                public static ItemStack superFuel(int burn) { return ItemStack.fuel(new SuperFuelItem(), burn, false); }
                public static ItemStack provider() { return ItemStack.provider(new Item()); }
                public static ItemStack defaultFuel(int burn) { return ItemStack.fuel(new DefaultFuelItem(), burn, false); }
            }""";

    private static final String MOD_ITEMS_ON_HOST = """
            package mod;
            public class FuelItem extends net.minecraft.world.item.Item {
                public int getBurnTime(net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.crafting.RecipeType<?> type) { return 300; }
            }""";

    /** 26.3: fuel is a cooking fuel component holding a number or a named data pack provider. */
    private static final Map<String, String> HOST_263 = Map.ofEntries(
            Map.entry("net/minecraft/world/item/crafting/RecipeType.java", """
                    package net.minecraft.world.item.crafting;
                    public interface RecipeType<T> {}"""),
            Map.entry("net/minecraft/world/item/Item.java", """
                    package net.minecraft.world.item;
                    public class Item {}"""),
            Map.entry("net/minecraft/core/component/DataComponentType.java", """
                    package net.minecraft.core.component;
                    public final class DataComponentType<T> {}"""),
            Map.entry("net/minecraft/core/component/DataComponents.java", """
                    package net.minecraft.core.component;
                    public final class DataComponents {
                        public static final DataComponentType<net.minecraft.world.item.component.CookingFuel> COOKING_FUEL = new DataComponentType<>();
                    }"""),
            Map.entry("net/minecraft/world/level/storage/loot/LootContext.java", """
                    package net.minecraft.world.level.storage.loot;
                    public class LootContext {}"""),
            Map.entry("net/minecraft/world/level/storage/loot/providers/number/ints/ResolvableInt.java", """
                    package net.minecraft.world.level.storage.loot.providers.number.ints;
                    import net.minecraft.world.level.storage.loot.LootContext;
                    public interface ResolvableInt {
                        int get(LootContext context, int fallback);
                        record Constant(int value) implements ResolvableInt {
                            public int get(LootContext context, int fallback) { return value; }
                        }
                        record Reference(String key) implements ResolvableInt {
                            public int get(LootContext context, int fallback) { throw new IllegalStateException("needs a server"); }
                        }
                    }"""),
            Map.entry("net/minecraft/world/item/component/CookingFuel.java", """
                    package net.minecraft.world.item.component;
                    import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
                    public record CookingFuel(ResolvableInt burnTime) {}"""),
            Map.entry("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    import net.minecraft.core.component.*;
                    import net.minecraft.world.item.component.CookingFuel;
                    import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
                    public final class ItemStack {
                        private final Item item;
                        private final CookingFuel fuel;
                        private final boolean empty;
                        private ItemStack(Item item, CookingFuel fuel, boolean empty) { this.item = item; this.fuel = fuel; this.empty = empty; }
                        public static ItemStack fuel(Item item, int burn, boolean empty) {
                            return new ItemStack(item, new CookingFuel(new ResolvableInt.Constant(burn)), empty);
                        }
                        public static ItemStack plain(Item item, boolean empty) { return new ItemStack(item, null, empty); }
                        public static ItemStack provider(Item item) {
                            return new ItemStack(item, new CookingFuel(new ResolvableInt.Reference("cooking/time_coal")), false);
                        }
                        public boolean isEmpty() { return empty; }
                        public Item getItem() { return item; }
                        @SuppressWarnings("unchecked")
                        public <T> T get(DataComponentType<? extends T> type) { return type == DataComponents.COOKING_FUEL ? (T) fuel : null; }
                    }"""),
            Map.entry("net/minecraft/server/level/ServerLevel.java", """
                    package net.minecraft.server.level;
                    public class ServerLevel {}"""),
            Map.entry("net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer {
                        public final net.minecraft.server.level.ServerLevel overworld() { return null; }
                    }"""),
            Map.entry("net/neoforged/neoforge/server/ServerLifecycleHooks.java", """
                    package net.neoforged.neoforge.server;
                    public class ServerLifecycleHooks {
                        public static net.minecraft.server.MinecraftServer getCurrentServer() { return null; }
                    }"""),
            Map.entry("mod/FuelItem.java", MOD_ITEMS_ON_HOST),
            Map.entry("mod/SuperFuelItem.java", """
                    package mod;
                    public class SuperFuelItem extends net.minecraft.world.item.Item {}"""),
            Map.entry("mod/DefaultFuelItem.java", """
                    package mod;
                    public class DefaultFuelItem extends net.minecraft.world.item.Item {}"""),
            Map.entry("mod/Make.java", MAKE));

    /** NeoForge 21.2 to 26.2: the host's read takes the world's fuel values, which the server holds. */
    private static final Map<String, String> HOST_262 = Map.ofEntries(
            Map.entry("net/minecraft/world/item/crafting/RecipeType.java", """
                    package net.minecraft.world.item.crafting;
                    public interface RecipeType<T> {}"""),
            Map.entry("net/minecraft/world/item/Item.java", """
                    package net.minecraft.world.item;
                    import net.minecraft.world.item.crafting.RecipeType;
                    import net.minecraft.world.level.block.entity.FuelValues;
                    public class Item {
                        public int getBurnTime(ItemStack stack, RecipeType<?> type, FuelValues fuelValues) { return fuelValues.burnDuration(stack); }
                    }"""),
            Map.entry("net/minecraft/world/level/block/entity/Furnace.java", """
                    package net.minecraft.world.level.block.entity;
                    public class Furnace {
                        public static int burn(net.minecraft.world.item.ItemStack stack) {
                            return stack.getItem().getBurnTime(stack, null, new FuelValues());
                        }
                    }"""),
            Map.entry("net/minecraft/world/level/block/entity/FuelValues.java", """
                    package net.minecraft.world.level.block.entity;
                    public class FuelValues {
                        public int burnDuration(net.minecraft.world.item.ItemStack stack) { return stack.burn(); }
                    }"""),
            Map.entry(EXTENSION + ".java", """
                    package net.neoforged.neoforge.common.extensions;
                    import net.minecraft.world.item.crafting.RecipeType;
                    import net.minecraft.world.level.block.entity.FuelValues;
                    public interface IItemStackExtension {
                        default int getBurnTime(RecipeType<?> type, FuelValues fuelValues) {
                            return fuelValues.burnDuration((net.minecraft.world.item.ItemStack) this);
                        }
                    }"""),
            Map.entry("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public final class ItemStack implements net.neoforged.neoforge.common.extensions.IItemStackExtension {
                        private final Item item;
                        private final int burn;
                        private final boolean empty;
                        private ItemStack(Item item, int burn, boolean empty) { this.item = item; this.burn = burn; this.empty = empty; }
                        public static ItemStack fuel(Item item, int burn, boolean empty) { return new ItemStack(item, burn, empty); }
                        public static ItemStack plain(Item item, boolean empty) { return new ItemStack(item, 0, empty); }
                        public static ItemStack provider(Item item) { return new ItemStack(item, 0, false); }
                        public int burn() { return burn; }
                        public boolean isEmpty() { return empty; }
                        public Item getItem() { return item; }
                    }"""),
            Map.entry("net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer {
                        public net.minecraft.world.level.block.entity.FuelValues fuelValues() {
                            return new net.minecraft.world.level.block.entity.FuelValues();
                        }
                    }"""),
            Map.entry("net/neoforged/neoforge/server/ServerLifecycleHooks.java", """
                    package net.neoforged.neoforge.server;
                    public class ServerLifecycleHooks {
                        public static net.minecraft.server.MinecraftServer getCurrentServer() { return new net.minecraft.server.MinecraftServer(); }
                    }"""),
            Map.entry("mod/FuelItem.java", MOD_ITEMS_ON_HOST),
            Map.entry("mod/SuperFuelItem.java", """
                    package mod;
                    public class SuperFuelItem extends net.minecraft.world.item.Item {}"""),
            Map.entry("mod/DefaultFuelItem.java", """
                    package mod;
                    public class DefaultFuelItem extends net.minecraft.world.item.Item {}"""),
            Map.entry("mod/Make.java", MAKE));
}
