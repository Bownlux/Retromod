/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old members that a 1.20.1 world-gen, loot, carry or crash report mod calls while a world loads or a player joins. */
class WorldLoadMemberBridgesTest {

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final boolean savedForge = McReflect.isForceForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        System.getProperties().remove("retromod.shared.entity_data.compound_tag.v1");
    }

    @Test
    @DisplayName("ChunkPos.asLong packs the same bits through 26.1's pack")
    void chunkPosAsLongIsPack() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/world/level/ChunkPos.java", """
                        package net.minecraft.world.level;
                        public class ChunkPos { public static long asLong(int x, int z) { return 0; } }""",
                "mod/RegionKeys.java", """
                        package mod;
                        public class RegionKeys {
                            public static long key(int x, int z) { return net.minecraft.world.level.ChunkPos.asLong(x, z); }
                        }""")), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/world/level/ChunkPos.java", """
                        package net.minecraft.world.level;
                        public record ChunkPos(int x, int z) {
                            public static long pack(int x, int z) { return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32; }
                        }"""));

        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> new Run(host, mod).call("mod/RegionKeys", "key", -3, 7)), "26.1 has no asLong");
        Run bridged = new Run(host, transform("26.2", Common_1_21_11_to_26_1_ClassMoves::register, mod));
        assertEquals(0xFFFFFFFDL | 7L << 32, bridged.call("mod/RegionKeys", "key", -3, 7));
    }

    @Test
    @DisplayName("TerraBlender's weighted region list keeps its sums and picks on the rebuilt entries")
    void weightedEntriesKeepTheirPicks() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/util/RandomSource.java", """
                        package net.minecraft.util;
                        public interface RandomSource { int nextInt(int bound); }""",
                "net/minecraft/util/random/Weight.java", """
                        package net.minecraft.util.random;
                        public class Weight { public static Weight of(int w) { return null; } public int asInt() { return 0; } }""",
                "net/minecraft/util/random/WeightedEntry.java", """
                        package net.minecraft.util.random;
                        public interface WeightedEntry {
                            Weight getWeight();
                            static <T> Wrapper<T> wrap(T data, int weight) { return null; }
                            class Wrapper<T> implements WeightedEntry {
                                public T getData() { return null; }
                                public Weight getWeight() { return null; }
                            }
                            abstract class IntrusiveBase implements WeightedEntry {
                                protected IntrusiveBase(int weight) {}
                                public Weight getWeight() { return null; }
                            }
                        }""",
                "net/minecraft/util/random/WeightedRandom.java", """
                        package net.minecraft.util.random;
                        import java.util.*;
                        public class WeightedRandom {
                            public static int getTotalWeight(List<? extends WeightedEntry> l) { return 0; }
                            public static <T extends WeightedEntry> Optional<T> getWeightedItem(List<T> l, int i) { return null; }
                            public static <T extends WeightedEntry> Optional<T> getRandomItem(net.minecraft.util.RandomSource r, List<T> l) { return null; }
                        }""",
                "mod/Regions.java", """
                        package mod;
                        import java.util.List;
                        import net.minecraft.util.random.*;
                        public class Regions {
                            static class Region extends WeightedEntry.IntrusiveBase { Region(int weight) { super(weight); } }
                            public static String pick(int index) {
                                List<WeightedEntry.Wrapper<String>> list = List.of(WeightedEntry.wrap("a", 2), WeightedEntry.wrap("b", 3));
                                int total = WeightedRandom.getTotalWeight(list);
                                WeightedEntry.Wrapper<String> picked = WeightedRandom.getWeightedItem(list, index).orElse(null);
                                return total + ":" + (picked == null ? "none" : picked.getData()) + ":" + list.get(1).getWeight().asInt();
                            }
                            public static int intrusive() {
                                List<Region> regions = List.of(new Region(4), new Region(1));
                                return WeightedRandom.getTotalWeight(regions) * 10 + regions.get(0).getWeight().asInt();
                            }
                            public static String random() {
                                List<WeightedEntry.Wrapper<String>> list = List.of(WeightedEntry.wrap("a", 2), WeightedEntry.wrap("b", 3));
                                return WeightedRandom.getRandomItem(bound -> bound - 1, list).map(WeightedEntry.Wrapper::getData).orElse("none");
                            }
                        }""")), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/util/RandomSource.java", """
                        package net.minecraft.util;
                        public interface RandomSource { int nextInt(int bound); }""",
                "net/minecraft/util/random/Weighted.java", """
                        package net.minecraft.util.random;
                        public record Weighted<T>(T value, int weight) {}""",
                "net/minecraft/util/random/WeightedRandom.java", """
                        package net.minecraft.util.random;
                        public class WeightedRandom {
                            public static <T> int getTotalWeight(java.util.List<T> l, java.util.function.ToIntFunction<T> w) { return -1; }
                        }"""));

        assertInstanceOf(NoClassDefFoundError.class, assertThrows(Throwable.class,
                () -> new Run(host, mod).call("mod/Regions", "pick", 2)), "1.21.5 has no WeightedEntry");
        Run bridged = new Run(host, transform("26.2", LegacyWeightedEntryBridge::register, mod));
        assertEquals("5:b:3", bridged.call("mod/Regions", "pick", 2), "index 2 passes a's weight of 2 and lands on b");
        assertEquals("5:none:3", bridged.call("mod/Regions", "pick", 5), "an index past the total picks nothing");
        assertEquals(54, bridged.call("mod/Regions", "intrusive"), "a mod's own entries keep their weights");
        assertEquals("b", bridged.call("mod/Regions", "random"), "the random pick goes through the method reference too");
    }

    @Test
    @DisplayName("Lootr's loot table field, setLootTable(id, seed) and getLootTable() reach the keyed container and a mod's overrides")
    void lootTableIdsBecomeKeys() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/resources/ResourceLocation.java", """
                        package net.minecraft.resources;
                        public final class ResourceLocation { public ResourceLocation(String id) {} }""",
                "net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity.java", """
                        package net.minecraft.world.level.block.entity;
                        import net.minecraft.resources.ResourceLocation;
                        public class RandomizableContainerBlockEntity {
                            public ResourceLocation lootTable;
                            public void setLootTable(ResourceLocation table, long seed) {}
                            public ResourceLocation getLootTable() { return lootTable; }
                        }""",
                "mod/SealedCrate.java", """
                        package mod;
                        import net.minecraft.resources.ResourceLocation;
                        import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
                        class SealedCrate extends RandomizableContainerBlockEntity {
                            @Override public ResourceLocation getLootTable() {
                                ResourceLocation own = super.getLootTable();
                                return own == null ? new ResourceLocation("chests/sealed") : own;
                            }
                        }""",
                "mod/LootChest.java", """
                        package mod;
                        import net.minecraft.resources.ResourceLocation;
                        import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
                        class LootChest extends RandomizableContainerBlockEntity {
                            public ResourceLocation saved;
                            public long seed;
                            @Override public void setLootTable(ResourceLocation table, long seed) {
                                super.setLootTable(table, seed);
                                this.saved = table;
                                this.seed = seed;
                            }
                        }""",
                "mod/Ticker.java", """
                        package mod;
                        import net.minecraft.resources.ResourceLocation;
                        import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
                        public class Ticker {
                            public static String tick() {
                                RandomizableContainerBlockEntity vanilla = new RandomizableContainerBlockEntity();
                                vanilla.setLootTable(new ResourceLocation("chests/ruin"), 7L);
                                LootChest chest = new LootChest();
                                RandomizableContainerBlockEntity replaced = chest;
                                replaced.setLootTable(vanilla.lootTable, 9L);
                                String swapped = chest.saved + "/" + chest.seed + "/" + chest.lootTable;
                                chest.lootTable = null;
                                RandomizableContainerBlockEntity crate = new SealedCrate();
                                String sealed = String.valueOf(crate.getLootTable());
                                crate.lootTable = new ResourceLocation("chests/open");
                                return vanilla.lootTable + "|" + swapped + "|" + chest.lootTable + "|" + sealed + "/" + crate.getLootTable();
                            }
                        }""")), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public final class Identifier {
                            private final String id;
                            public Identifier(String id) { this.id = id; }
                            @Override public String toString() { return id; }
                        }""",
                "net/minecraft/resources/ResourceKey.java", """
                        package net.minecraft.resources;
                        public final class ResourceKey<T> {
                            private final Identifier id;
                            private ResourceKey(Identifier id) { this.id = id; }
                            public static <T> ResourceKey<T> create(ResourceKey<?> registry, Identifier id) { return new ResourceKey<>(id); }
                            public Identifier identifier() { return id; }
                        }""",
                "net/minecraft/core/registries/Registries.java", """
                        package net.minecraft.core.registries;
                        import net.minecraft.resources.*;
                        public class Registries {
                            public static final ResourceKey<Object> LOOT_TABLE = ResourceKey.create(null, new Identifier("loot_table"));
                        }""",
                "net/minecraft/world/RandomizableContainer.java", """
                        package net.minecraft.world;
                        import net.minecraft.resources.ResourceKey;
                        public interface RandomizableContainer {
                            ResourceKey<Object> getLootTable();
                            void setLootTable(ResourceKey<Object> key);
                            void setLootTableSeed(long seed);
                            default void setLootTable(ResourceKey<Object> key, long seed) { setLootTable(key); setLootTableSeed(seed); }
                        }""",
                "net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity.java", """
                        package net.minecraft.world.level.block.entity;
                        import net.minecraft.resources.ResourceKey;
                        public class RandomizableContainerBlockEntity implements net.minecraft.world.RandomizableContainer {
                            protected ResourceKey<Object> lootTable;
                            protected long lootTableSeed;
                            public ResourceKey<Object> getLootTable() { return lootTable; }
                            public void setLootTable(ResourceKey<Object> key) { lootTable = key; }
                            public void setLootTableSeed(long seed) { lootTableSeed = seed; }
                        }"""));

        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> new Run(host, transform("26.2", renameIdentifier(), mod)).call("mod/Ticker", "tick")),
                "26.2 has no setLootTable(Identifier, long)");
        Map<String, byte[]> bridged = transform("26.2", t -> {
            Legacy1205MemberBridge.registerLootTableKeys(t);
            renameIdentifier().accept(t);
        }, mod, host);
        assertEquals("chests/ruin|chests/ruin/9/chests/ruin|null|chests/sealed/chests/open",
                new Run(host, bridged).call("mod/Ticker", "tick"),
                "each override runs once from a package-private class, its super call reaches the host, and null clears the table");
    }

    /** The 26.1 rename of ResourceLocation, which the chain registers after the 1.20.5 bridges. */
    private static java.util.function.Consumer<RetromodTransformer> renameIdentifier() {
        return t -> t.registerClassRedirect("net/minecraft/resources/ResourceLocation", "net/minecraft/resources/Identifier");
    }

    @Test
    @DisplayName("a 1.20.6 mod's keyed lootTable read stays as written")
    void keyedReadsStay() throws Exception {
        Map<String, byte[]> mod = only(compile(dir.resolve("new"), Map.of(
                "net/minecraft/resources/ResourceKey.java", """
                        package net.minecraft.resources;
                        public final class ResourceKey<T> {}""",
                "net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity.java", """
                        package net.minecraft.world.level.block.entity;
                        public class RandomizableContainerBlockEntity { public net.minecraft.resources.ResourceKey<Object> lootTable; }""",
                "mod/Reader.java", """
                        package mod;
                        public class Reader {
                            public static Object read(net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity be) { return be.lootTable; }
                        }""")), "mod/");
        Map<String, byte[]> out = transform("26.2", Legacy1205MemberBridge::registerLootTableKeys, mod);
        assertEquals(List.of("net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity.lootTable:Lnet/minecraft/resources/ResourceKey;"),
                fields(out.get("mod/Reader"), "read"));
    }

    @Test
    @DisplayName("Patchouli's setDetail(name, supplier) reaches 26.2's crash report detail form")
    void systemReportSupplierDetail() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/SystemReport.java", """
                        package net.minecraft;
                        public class SystemReport {
                            public final java.util.Map<String, Object> entries = new java.util.LinkedHashMap<>();
                            public void setDetail(String name, java.util.function.Supplier<String> value) {}
                        }""",
                "mod/BookCrash.java", """
                        package mod;
                        public class BookCrash {
                            public static Object append() {
                                net.minecraft.SystemReport report = new net.minecraft.SystemReport();
                                report.setDetail("Patchouli open book context", () -> "patchouli:guide");
                                report.setDetail("Broken", () -> { throw new IllegalStateException("no book"); });
                                return report.entries.get("Patchouli open book context") + "|"
                                        + (report.entries.get("Broken") instanceof IllegalStateException);
                            }
                        }""")), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/CrashReportDetail.java", """
                        package net.minecraft;
                        public interface CrashReportDetail<V> extends java.util.concurrent.Callable<V> {}""",
                "net/minecraft/SystemReport.java", """
                        package net.minecraft;
                        public class SystemReport {
                            public final java.util.Map<String, Object> entries = new java.util.LinkedHashMap<>();
                            public void setDetail(String name, String value) { entries.put(name, value); }
                            public void setDetail(String name, CrashReportDetail<Object> detail) {
                                try { entries.put(name, detail.call()); } catch (Exception e) { entries.put(name, e); }
                            }
                        }"""));

        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> new Run(host, mod).call("mod/BookCrash", "append")), "26.2 has no setDetail(String, Supplier)");
        Run bridged = new Run(host, transform("26.2", Legacy262MemberBridge::registerSystemReport, mod));
        assertEquals("patchouli:guide|true", bridged.call("mod/BookCrash", "append"),
                "the value is read at once and a failing supplier is recorded, not thrown");
        assertTrue(Legacy262MemberBridge.lostSupplierDetail(node("setDetail", "(Ljava/lang/String;Lnet/minecraft/CrashReportDetail;)V")));
        assertFalse(Legacy262MemberBridge.lostSupplierDetail(node("setDetail", Legacy262MemberBridge.SUPPLIER_DETAIL)),
                "26.1.2 keeps the supplier form");
    }

    @Test
    @DisplayName("an old isBiome rule built with no server finds its biomes in each world, in surface and carver steps")
    void surfaceRuleBuiltBeforeAnyServer() throws Throwable {
        Run run = new Run(surfaceHost("bare", Map.of()), surfaceMod());
        Object rule = run.loadClass("mod.Rules").getField("PLAINS_OR_DESERT").get(null);
        assertNotNull(rule, "the rule builds with no server running");
        // Two worlds, the second a reload with new holders for the same biomes.
        for (int world = 1; world <= 2; world++) {
            World generated = World.generate(run, rule);
            assertTrue(generated.surface(List.of("plains", "ocean"), "plains"), "world " + world + ": plains matches");
            assertFalse(generated.surface(List.of("plains", "ocean"), "ocean"), "world " + world + ": ocean does not");
            assertTrue(generated.surface(List.of("desert"), "desert"), "world " + world + ": desert matches");
            // With no server to ask, a carver uses the holders this world's surface steps found, whichever ran last.
            assertTrue(generated.carve("ocean", "plains"), "world " + world + ": a carver after another chunk's surface step");
            assertTrue(generated.carve("ocean", "desert"), "world " + world + ": a carver at desert");
            assertFalse(generated.carve("plains", "ocean"), "world " + world + ": the carver's own biome decides, not the origin's");
        }
        World generated = World.generate(run, rule);
        Object context = generated.moved(List.of("desert"), "ocean", "desert");
        assertSame(generated.holder("desert"), run.call("mod/Rules", "biome", context),
                "the old biome supplier reads the context's biome where it stands");
    }

    @Test
    @DisplayName("a carver's context finds its biomes in the running server's registry on every loader, after a reload too")
    void carverReadsTheServersBiomes() throws Throwable {
        Map<String, byte[]> mod = surfaceMod();
        // Forge and NeoForge name the running server in their lifecycle hooks.
        for (String hooks : List.of("net.minecraftforge.server.ServerLifecycleHooks", "net.neoforged.neoforge.server.ServerLifecycleHooks")) {
            String owner = hooks.substring(0, hooks.lastIndexOf('.'));
            Run run = new Run(surfaceHost(owner, Map.of(hooks.replace('.', '/') + ".java", "package " + owner + ";\n" + """
                    public class ServerLifecycleHooks {
                        public static net.minecraft.server.MinecraftServer current;
                        public static net.minecraft.server.MinecraftServer getCurrentServer() { return current; }
                    }""")), mod);
            carveAcrossWorlds(run, owner, server -> run.loadClass(hooks).getField("current").set(null, server));
        }
        // Fabric hands out the client, which hosts its world in an integrated server.
        Run fabric = new Run(surfaceHost("fabric", Map.of(
                "net/fabricmc/loader/api/FabricLoader.java", """
                        package net.fabricmc.loader.api;
                        public interface FabricLoader {
                            static FabricLoader getInstance() { return Game.LOADER; }
                            Object getGameInstance();
                            final class Game {
                                public static Object current;
                                static final FabricLoader LOADER = () -> current;
                            }
                        }""",
                "net/minecraft/client/Minecraft.java", """
                        package net.minecraft.client;
                        public class Minecraft {
                            private final net.minecraft.server.MinecraftServer server;
                            public Minecraft(net.minecraft.server.MinecraftServer server) { this.server = server; }
                            public net.minecraft.server.MinecraftServer getSingleplayerServer() { return server; }
                        }""")), mod);
        Class<?> server = fabric.loadClass("net.minecraft.server.MinecraftServer");
        carveAcrossWorlds(fabric, "fabric", running -> fabric.loadClass("net.fabricmc.loader.api.FabricLoader$Game").getField("current")
                .set(null, fabric.loadClass("net.minecraft.client.Minecraft").getConstructor(server).newInstance(running)));
    }

    /** Two worlds, the second a reload, whose carvers run after another chunk's surface step or before any. */
    private static void carveAcrossWorlds(Run run, String loader, ServerSetter setServer) throws Exception {
        Object rule = run.loadClass("mod.Rules").getField("PLAINS_OR_DESERT").get(null);
        World first = World.generate(run, rule);
        setServer.set(first.server());
        assertTrue(first.surface(List.of("plains", "ocean"), "plains"), loader + ": a plains and ocean chunk");
        assertTrue(first.carve("ocean", "desert"), loader + ": a carver at desert after a plains and ocean chunk");
        assertTrue(first.surface(List.of("desert"), "desert"), loader + ": a desert chunk");
        assertTrue(first.carve("ocean", "plains"), loader + ": a carver at plains after a desert chunk");
        assertFalse(first.carve("plains", "ocean"), loader + ": the carver's own biome decides, not the origin's");
        // A reload brings a new registry with new holders, before any surface step of the new world.
        World second = World.generate(run, rule);
        setServer.set(second.server());
        assertTrue(second.carve("ocean", "plains"), loader + ": a carver at plains in the reloaded world");
        assertFalse(second.carve("plains", "ocean"), loader + ": an ocean carver in the reloaded world");
    }

    private interface ServerSetter {
        void set(Object server) throws Exception;
    }

    /** One world of the fake host: its biome registry, and surface and carver steps in the host's order. */
    private record World(Run run, Object rule, Object biomes) {

        static World generate(Run run, Object rule) throws ReflectiveOperationException {
            Object biomes = run.loadClass("world.Biomes").getConstructor(String[].class)
                    .newInstance((Object) new String[] {"plains", "desert", "ocean"});
            return new World(run, rule, biomes);
        }

        Object holder(String id) throws ReflectiveOperationException {
            return biomes.getClass().getMethod("holder", String.class).invoke(biomes, id);
        }

        Object server() throws ReflectiveOperationException {
            return biomes.getClass().getMethod("server").invoke(biomes);
        }

        /** A surface step over a chunk of {@code chunk} biomes, tested where {@code at} stands. */
        boolean surface(List<String> chunk, String at) throws Exception {
            return step(chunk, "ocean", at);
        }

        /** A carver step, whose context names no biome set and stands at {@code origin} while the rule is applied. */
        boolean carve(String origin, String at) throws Exception {
            return step(null, origin, at);
        }

        /** A context built at {@code origin} and moved to {@code at}, with no rule applied. */
        Object moved(List<String> chunk, String origin, String at) throws Exception {
            Object context = context(chunk, origin);
            context.getClass().getMethod("moveTo", run.loadClass("net.minecraft.core.Holder")).invoke(context, holder(at));
            return context;
        }

        // The host applies the rule first and moves the context after, so a condition may read the biome only when tested.
        private boolean step(List<String> chunk, String origin, String at) throws Exception {
            Object context = context(chunk, origin);
            @SuppressWarnings("unchecked")
            Object condition = ((java.util.function.Function<Object, Object>) rule).apply(context);
            context.getClass().getMethod("moveTo", run.loadClass("net.minecraft.core.Holder")).invoke(context, holder(at));
            java.lang.reflect.Method test = condition.getClass().getMethod("test");
            test.setAccessible(true);
            return (boolean) test.invoke(condition);
        }

        private Object context(List<String> chunk, String origin) throws Exception {
            java.util.Set<Object> possible = null;
            if (chunk != null) {
                possible = new java.util.HashSet<>();
                for (String id : chunk) possible.add(holder(id));
            }
            return run.loadClass("net.minecraft.world.level.levelgen.SurfaceRules$Context")
                    .getMethod("at", java.util.Set.class, run.loadClass("net.minecraft.core.Holder"))
                    .invoke(null, possible, holder(origin));
        }
    }

    /** The old rule mod, built against the key-only isBiome and translated for 26.2. */
    private Map<String, byte[]> surfaceMod() throws Exception {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/resources/ResourceKey.java", """
                        package net.minecraft.resources;
                        public final class ResourceKey<T> {
                            public static ResourceKey<Object> of(String id) { return null; }
                        }""",
                "net/minecraft/world/level/levelgen/SurfaceRules.java", """
                        package net.minecraft.world.level.levelgen;
                        public class SurfaceRules {
                            public interface ConditionSource {}
                            public static ConditionSource isBiome(net.minecraft.resources.ResourceKey<?>... keys) { return null; }
                            public static class Context { public java.util.function.Supplier<Object> biome; }
                        }""",
                "mod/Rules.java", """
                        package mod;
                        import net.minecraft.resources.ResourceKey;
                        import net.minecraft.world.level.levelgen.SurfaceRules;
                        public class Rules {
                            // Built while blocks register, before any server exists, as Biomes O' Plenty does.
                            public static final Object PLAINS_OR_DESERT = SurfaceRules.isBiome(ResourceKey.of("plains"), ResourceKey.of("desert"));
                            public static Object biome(SurfaceRules.Context context) { return context.biome.get(); }
                        }""")), "mod/");
        return transform("26.2", Legacy262MemberBridge::registerSurfaceRules, mod);
    }

    /** 26.2's surface rule shapes, plus {@code loader} stand-ins, compiled into a folder of their own. */
    private Map<String, byte[]> surfaceHost(String name, Map<String, String> loader) throws Exception {
        Map<String, String> sources = new java.util.HashMap<>(loader);
        sources.putAll(Map.of(
                "net/minecraft/resources/ResourceKey.java", """
                        package net.minecraft.resources;
                        public final class ResourceKey<T> {
                            private final String id;
                            private ResourceKey(String id) { this.id = id; }
                            public static ResourceKey<Object> of(String id) { return new ResourceKey<>(id); }
                            @Override public boolean equals(Object other) { return other instanceof ResourceKey<?> key && key.id.equals(id); }
                            @Override public int hashCode() { return id.hashCode(); }
                        }""",
                "net/minecraft/tags/TagKey.java", "package net.minecraft.tags; public final class TagKey<T> {}",
                "net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        public interface Holder<T> {
                            T value();
                            boolean is(net.minecraft.resources.ResourceKey<T> key);
                            final class Reference<T> implements Holder<T> {
                                private final net.minecraft.resources.ResourceKey<T> key;
                                public Reference(net.minecraft.resources.ResourceKey<T> key) { this.key = key; }
                                public T value() { return null; }
                                public boolean is(net.minecraft.resources.ResourceKey<T> other) { return key.equals(other); }
                            }
                        }""",
                "net/minecraft/core/HolderSet.java", "package net.minecraft.core; public interface HolderSet<T> { abstract class Named<T> implements HolderSet<T> {} }",
                "net/minecraft/core/HolderGetter.java", """
                        package net.minecraft.core;
                        public interface HolderGetter<T> {
                            java.util.Optional<Holder.Reference<T>> get(net.minecraft.resources.ResourceKey<T> key);
                            java.util.Optional<HolderSet.Named<T>> get(net.minecraft.tags.TagKey<T> tag);
                            default Holder.Reference<T> getOrThrow(net.minecraft.resources.ResourceKey<T> key) { return get(key).orElseThrow(); }
                        }""",
                "com/mojang/serialization/MapCodec.java", """
                        package com.mojang.serialization;
                        public abstract class MapCodec<A> { public static <A> MapCodec<A> unit(A value) { return null; } }""",
                "net/minecraft/world/level/levelgen/SurfaceRules.java", """
                        package net.minecraft.world.level.levelgen;
                        import java.util.*;
                        import net.minecraft.core.*;
                        import net.minecraft.resources.ResourceKey;
                        public class SurfaceRules {
                            interface Condition { boolean test(); }
                            public interface ConditionSource extends java.util.function.Function<Context, Condition> {
                                com.mojang.serialization.MapCodec<? extends ConditionSource> codec();
                            }
                            // Resolves the keys into holders at once, and reads the biome only when tested, as 26.2 does.
                            public static ConditionSource isBiome(HolderGetter<Object> getter, ResourceKey<Object>... keys) {
                                Set<Holder<Object>> holders = new HashSet<>();
                                for (ResourceKey<Object> key : keys) holders.add(getter.getOrThrow(key));
                                return new ConditionSource() {
                                    public Condition apply(Context context) {
                                        if (context.possibleBiomes != null && Collections.disjoint(holders, context.possibleBiomes)) return () -> false;
                                        return () -> holders.contains(context.getBiome());
                                    }
                                    public com.mojang.serialization.MapCodec<? extends ConditionSource> codec() { return null; }
                                };
                            }
                            // Built at the origin and moved only after the rule is applied, as SurfaceSystem does.
                            public static final class Context {
                                private final Set<Holder<Object>> possibleBiomes;
                                private Holder<Object> biome;
                                private Context(Set<Holder<Object>> possibleBiomes, Holder<Object> origin) { this.possibleBiomes = possibleBiomes; this.biome = origin; }
                                public static Context at(Set<Holder<Object>> possibleBiomes, Holder<Object> origin) { return new Context(possibleBiomes, origin); }
                                public void moveTo(Holder<Object> biome) { this.biome = biome; }
                                protected Holder<Object> getBiome() { return biome; }
                            }
                        }"""));
        sources.putAll(Map.of(
                "net/minecraft/core/Registry.java", "package net.minecraft.core; public interface Registry<T> extends HolderGetter<T> {}",
                "net/minecraft/core/RegistryAccess.java", """
                        package net.minecraft.core;
                        public interface RegistryAccess {
                            <E> java.util.Optional<Registry<E>> lookup(net.minecraft.resources.ResourceKey<? extends Registry<? extends E>> key);
                            interface Frozen extends RegistryAccess {}
                        }""",
                "net/minecraft/core/registries/Registries.java", """
                        package net.minecraft.core.registries;
                        public final class Registries {
                            public static final net.minecraft.resources.ResourceKey<Object> BIOME = net.minecraft.resources.ResourceKey.of("minecraft:worldgen/biome");
                        }""",
                "net/minecraft/server/MinecraftServer.java", """
                        package net.minecraft.server;
                        public class MinecraftServer {
                            private final net.minecraft.core.RegistryAccess.Frozen registries;
                            public MinecraftServer(net.minecraft.core.RegistryAccess.Frozen registries) { this.registries = registries; }
                            public net.minecraft.core.RegistryAccess.Frozen registryAccess() { return registries; }
                        }""",
                "world/Biomes.java", """
                        package world;
                        import java.util.*;
                        import net.minecraft.core.*;
                        import net.minecraft.resources.ResourceKey;
                        // One world's biome registry with fresh holders, and the server that holds it.
                        public final class Biomes implements Registry<Object>, RegistryAccess.Frozen {
                            private final Map<ResourceKey<Object>, Holder.Reference<Object>> holders = new HashMap<>();
                            public Biomes(String... ids) { for (String id : ids) holders.put(ResourceKey.of(id), new Holder.Reference<>(ResourceKey.of(id))); }
                            public Holder.Reference<Object> holder(String id) { return holders.get(ResourceKey.of(id)); }
                            public net.minecraft.server.MinecraftServer server() { return new net.minecraft.server.MinecraftServer(this); }
                            public Optional<Holder.Reference<Object>> get(ResourceKey<Object> key) { return Optional.ofNullable(holders.get(key)); }
                            public Optional<HolderSet.Named<Object>> get(net.minecraft.tags.TagKey<Object> tag) { return Optional.empty(); }
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            public <E> Optional<Registry<E>> lookup(ResourceKey<? extends Registry<? extends E>> key) {
                                return key.equals(net.minecraft.core.registries.Registries.BIOME) ? Optional.of((Registry) this) : Optional.empty();
                            }
                        }"""));
        return compile(dir.resolve("host-" + name), sources);
    }

    @Test
    @DisplayName("the 26.2 surface rule bridge stays off 26.1 and 26.3, which have other surface rule shapes")
    void surfaceRuleBridgeIsFor262Only() {
        ClassNode oldRules = node("isBiome", Legacy262MemberBridge.OLD_IS_BIOME);
        assertFalse(Legacy262MemberBridge.surfaceRulesTakeLookup(oldRules, null), "26.1.2 keeps the key-only form");
        assertTrue(Legacy262MemberBridge.surfaceRulesTakeLookup(node("isBiome",
                "(Lnet/minecraft/core/HolderGetter;[Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/level/levelgen/SurfaceRules$ConditionSource;"),
                null));
        assertFalse(Legacy262MemberBridge.surfaceRulesTakeLookup(null, new ClassNode()), "26.3 has material rules");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertFalse(Legacy262MemberBridge.surfaceRulesTakeLookup(null, null), "offline 26.3");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(Legacy262MemberBridge.surfaceRulesTakeLookup(null, null), "offline 26.2");
    }

    @Test
    @DisplayName("VillagerData.getProfession() and GameEvent constants answer with the held value")
    void heldValues() throws Throwable {
        String villager = "net/minecraft/world/entity/npc/villager/";
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                villager + "VillagerProfession.java", "package net.minecraft.world.entity.npc.villager; public class VillagerProfession {}",
                villager + "VillagerData.java", """
                        package net.minecraft.world.entity.npc.villager;
                        public class VillagerData { public VillagerProfession getProfession() { return null; } }""",
                "net/minecraft/world/level/gameevent/GameEvent.java", """
                        package net.minecraft.world.level.gameevent;
                        public class GameEvent { public static final GameEvent JUKEBOX_PLAY = null; public int notificationRadius() { return 0; } }""",
                "mod/Farmers.java", """
                        package mod;
                        public class Farmers {
                            public static Object profession(net.minecraft.world.entity.npc.villager.VillagerData data) { return data.getProfession(); }
                            public static int radius() { return net.minecraft.world.level.gameevent.GameEvent.JUKEBOX_PLAY.notificationRadius(); }
                        }""")), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/core/Holder.java", "package net.minecraft.core; public interface Holder<T> { T value(); record Reference<T>(T value) implements Holder<T> {} }",
                villager + "VillagerProfession.java", "package net.minecraft.world.entity.npc.villager; public class VillagerProfession {}",
                villager + "VillagerData.java", """
                        package net.minecraft.world.entity.npc.villager;
                        public record VillagerData(net.minecraft.core.Holder<VillagerProfession> profession) {}""",
                "net/minecraft/world/level/gameevent/GameEvent.java", """
                        package net.minecraft.world.level.gameevent;
                        public class GameEvent {
                            public static final net.minecraft.core.Holder.Reference<GameEvent> JUKEBOX_PLAY = new net.minecraft.core.Holder.Reference<>(new GameEvent());
                            public int notificationRadius() { return 10; }
                        }"""));

        Map<String, byte[]> out = transform("26.2", t -> {
            LegacyVillagerBridge.registerProfessionGetter(t);
            LegacyHolderConstantBridge.registerRedirects(t, Map.of("net/minecraft/world/level/gameevent/GameEvent",
                    Map.of("JUKEBOX_PLAY", "Lnet/minecraft/core/Holder$Reference;")));
        }, mod);
        Run run = new Run(host, out);
        Object farmer = run.loadClass(villager.replace('/', '.') + "VillagerProfession").getConstructor().newInstance();
        assertSame(farmer, run.call("mod/Farmers", "profession", villagerData(run, villager, farmer)));
        assertEquals(10, run.call("mod/Farmers", "radius"));
        Run old = new Run(host, mod);
        Object oldData = villagerData(old, villager, null);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> old.call("mod/Farmers", "profession", oldData)), "1.21.5 has profession() only");
        assertInstanceOf(NoSuchFieldError.class, assertThrows(Throwable.class,
                () -> old.call("mod/Farmers", "radius")), "1.20.5 made JUKEBOX_PLAY a holder");
    }

    @Test
    @DisplayName("a Fabric 1.21.4 mod's getProfession() reaches profession() on a 1.21.5 to 1.21.11 host's intermediary names")
    void intermediaryProfession() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/class_3852.java", "package net.minecraft; public class class_3852 {}",
                "net/minecraft/class_3850.java", "package net.minecraft; public class class_3850 { public class_3852 method_16924() { return null; } }",
                "mod/Farmers.java", """
                        package mod;
                        public class Farmers { public static Object profession(net.minecraft.class_3850 data) { return data.method_16924(); } }""")), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/class_6880.java", "package net.minecraft; public interface class_6880<T> { T comp_349(); }",
                "net/minecraft/class_3852.java", "package net.minecraft; public class class_3852 {}",
                "net/minecraft/class_3850.java", """
                        package net.minecraft;
                        public record class_3850(class_6880<class_3852> comp_3521) {}"""));
        Run run = new Run(host, transform("1.21.11", LegacyVillagerBridge::registerProfessionGetterIntermediary, mod));
        Object farmer = run.loadClass("net.minecraft.class_3852").getConstructor().newInstance();
        Class<?> holder = run.loadClass("net.minecraft.class_6880");
        Object held = java.lang.reflect.Proxy.newProxyInstance(run, new Class<?>[] {holder}, (proxy, method, args) -> farmer);
        Object data = run.loadClass("net.minecraft.class_3850").getConstructors()[0].newInstance(held);
        assertSame(farmer, run.call("mod/Farmers", "profession", data));
    }

    /** A host {@code VillagerData} holding {@code profession}, built in {@code run}'s classes. */
    private static Object villagerData(Run run, String villager, Object profession) throws Exception {
        Class<?> reference = run.loadClass("net.minecraft.core.Holder$Reference");
        return run.loadClass(villager.replace('/', '.') + "VillagerData")
                .getConstructors()[0].newInstance(reference.getConstructors()[0].newInstance(profession));
    }

    @Test
    @DisplayName("Lootr's chunk status check, Farmer's Delight's soup checks and the Forge remainder read the host's forms")
    void chunkStatusFoodAndRemainder() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/world/level/chunk/status/ChunkStatus.java", "package net.minecraft.world.level.chunk.status; public class ChunkStatus {}",
                "net/minecraft/world/level/chunk/ChunkAccess.java", """
                        package net.minecraft.world.level.chunk;
                        public abstract class ChunkAccess { public abstract net.minecraft.world.level.chunk.status.ChunkStatus getStatus(); }""",
                "net/minecraft/world/item/ItemStack.java", """
                        package net.minecraft.world.item;
                        public final class ItemStack {
                            public static final ItemStack EMPTY = null;
                            public boolean isEdible() { return false; }
                            public ItemStack getCraftingRemainingItem() { return null; }
                            public boolean hasCraftingRemainingItem() { return false; }
                        }""",
                "mod/Soup.java", """
                        package mod;
                        import net.minecraft.world.item.ItemStack;
                        public class Soup {
                            public static Object status(net.minecraft.world.level.chunk.ChunkAccess chunk) { return chunk.getStatus(); }
                            public static String finish(ItemStack bowl) {
                                return bowl.isEdible() + ":" + bowl.hasCraftingRemainingItem() + ":" + bowl.getCraftingRemainingItem();
                            }
                        }""")), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/world/level/chunk/status/ChunkStatus.java", "package net.minecraft.world.level.chunk.status; public class ChunkStatus {}",
                "net/minecraft/world/level/chunk/ChunkAccess.java", """
                        package net.minecraft.world.level.chunk;
                        public class ChunkAccess {
                            public static final net.minecraft.world.level.chunk.status.ChunkStatus FULL = new net.minecraft.world.level.chunk.status.ChunkStatus();
                            public net.minecraft.world.level.chunk.status.ChunkStatus getPersistedStatus() { return FULL; }
                        }""",
                "net/minecraft/core/component/DataComponentType.java", "package net.minecraft.core.component; public class DataComponentType<T> {}",
                "net/minecraft/core/component/DataComponents.java", """
                        package net.minecraft.core.component;
                        public class DataComponents { public static final DataComponentType<Object> FOOD = new DataComponentType<>(); }""",
                "net/minecraft/core/component/DataComponentHolder.java", """
                        package net.minecraft.core.component;
                        public interface DataComponentHolder {
                            java.util.Set<Object> types();
                            default boolean has(DataComponentType<?> type) { return types().contains(type); }
                        }""",
                "net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        public interface Holder<T> { default boolean areComponentsBound() { return true; } }""",
                "net/minecraft/world/item/ItemStackTemplate.java", """
                        package net.minecraft.world.item;
                        public final class ItemStackTemplate {
                            private final String item;
                            public ItemStackTemplate(String item) { this.item = item; }
                            public net.minecraft.core.Holder<Object> item() { return new net.minecraft.core.Holder<>() {}; }
                            public ItemStack create() { return new ItemStack(item, null); }
                        }""",
                "net/minecraft/world/item/ItemStack.java", """
                        package net.minecraft.world.item;
                        public final class ItemStack implements net.minecraft.core.component.DataComponentHolder {
                            public static final ItemStack EMPTY = new ItemStack("air", null);
                            private final String item;
                            private final String remainder;
                            public ItemStack(String item, String remainder) { this.item = item; this.remainder = remainder; }
                            public java.util.Set<Object> types() {
                                return item.endsWith("soup") ? java.util.Set.of(net.minecraft.core.component.DataComponents.FOOD) : java.util.Set.of();
                            }
                            public ItemStackTemplate getCraftingRemainder() { return remainder == null ? null : new ItemStackTemplate(remainder); }
                            @Override public String toString() { return item; }
                        }"""));

        McReflect.setForceForge(true);
        Map<String, byte[]> out = transform("26.2", t -> {
            Legacy1205MemberBridge.registerChunkStatusRename(t);
            LegacyFoodPropertiesBridge.registerRedirects(t, LegacyFoodPropertiesBridge.candidates().stream()
                    .filter(rewrite -> rewrite.owner().equals(LegacyFoodPropertiesBridge.STACK)).toList());
            com.retromod.shim.neoforge.LegacyCraftingRemainderBridge.registerForge(t);
        }, mod);
        Run run = new Run(host, out);
        Object chunk = run.loadClass("net.minecraft.world.level.chunk.ChunkAccess").getConstructor().newInstance();
        assertSame(run.loadClass("net.minecraft.world.level.chunk.ChunkAccess").getField("FULL").get(null),
                run.call("mod/Soup", "status", chunk));
        var stack = run.loadClass("net.minecraft.world.item.ItemStack").getConstructor(String.class, String.class);
        assertEquals("true:true:bowl", run.call("mod/Soup", "finish", stack.newInstance("mushroom_soup", "bowl")));
        assertEquals("false:false:air", run.call("mod/Soup", "finish", stack.newInstance("stone", null)));
    }

    /** A host class with one method, standing in for the class a probe reads. */
    private static ClassNode node(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(1, name, desc, null, null));
        return node;
    }
}
