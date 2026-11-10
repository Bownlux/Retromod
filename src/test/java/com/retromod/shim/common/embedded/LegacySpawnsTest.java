/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Old biome spawn calls build and add entries on the 1.21.5 to 26.2 and 26.3 shapes, NeoForge's and Forge's. */
class LegacySpawnsTest {

    @Test
    @DisplayName("a 1.21.5 to 26.2 host builds (type, 2, 4) and adds it once with its weight")
    void threeComponentHostKeepsCountsAndWeight() throws Exception {
        Host host = Host.threeComponent(false);
        Object zombie = host.type("zombie");
        Object entry = host.spawnerData(zombie, 7, 2, 4);
        assertEquals(2, host.read(entry, "minCount"));
        assertEquals(4, host.read(entry, "maxCount"));

        Object builder = host.builder();
        host.addSpawn(builder, host.monster(), entry);
        assertEquals(List.of("monster 7 zombie"), host.added(builder), "added once, with the old weight");
    }

    @Test
    @DisplayName("on 26.3 the four-argument addSpawn is used, never the (type, weight, counts) overload")
    void providerHostUsesTheCategoryOverload() throws Exception {
        Host host = Host.provider();
        Object entry = host.spawnerData(host.type("husk"), 5, 1, 3);
        Object builder = host.builder();
        host.addSpawn(builder, host.monster(), entry);

        assertEquals(List.of("monster 5 husk"), host.added(builder));
        assertEquals(0, builder.getClass().getField("wrongOverload").getInt(builder),
                "the overload without a category would put the mob in its default category");
    }

    @Test
    @DisplayName("one entry added to two biomes keeps its weight both times")
    void reusedEntryKeepsItsWeight() throws Exception {
        Host host = Host.threeComponent(false);
        Object entry = host.spawnerData(host.type("zombie"), 7, 1, 1);
        Object first = host.builder();
        Object second = host.builder();
        host.addSpawn(first, host.monster(), entry);
        host.addSpawn(second, host.monster(), entry);

        assertEquals(List.of("monster 7 zombie"), host.added(first));
        assertEquals(List.of("monster 7 zombie"), host.added(second), "the weight must not be used up");
    }

    @Test
    @DisplayName("getSpawner(category).add reaches the builder once per entry, with the remembered weight")
    void spawnerListAddsThroughTheBuilder() throws Exception {
        Host host = Host.threeComponent(false);
        Object builder = host.builder();
        List<Object> spawns = host.spawnerList(builder, host.monster());
        Object zombie = host.spawnerData(host.type("zombie"), 7, 1, 2);
        Object skeleton = host.spawnerData(host.type("skeleton"), 3, 1, 1);

        assertTrue(spawns.add(zombie));
        assertTrue(spawns.addAll(List.of(skeleton)));
        assertEquals(List.of("monster 7 zombie", "monster 3 skeleton"), host.added(builder));
        assertEquals(2, spawns.size(), "reads see what the builder holds");
        assertSame(zombie, spawns.get(0));
    }

    @Test
    @DisplayName("removeIf removes through a NeoForge-shaped weighted builder, whose list is read-only")
    void removeIfOnNeoForge() throws Exception {
        assertRemoveIfWorks(Host.threeComponent(false));
    }

    @Test
    @DisplayName("removeIf removes through a Forge-shaped weighted builder, which tests entries")
    void removeIfOnForge() throws Exception {
        assertRemoveIfWorks(Host.threeComponent(true));
    }

    @Test
    @DisplayName("a category the biome does not define reads as empty and still takes an add")
    void undefinedCategoryReadsEmpty() throws Exception {
        Host host = Host.provider();
        Object builder = host.builder();
        List<Object> spawns = host.spawnerList(builder, host.monster());

        assertTrue(spawns.isEmpty());
        assertFalse(spawns.removeIf(entry -> true));
        spawns.add(host.spawnerData(host.type("husk"), 4, 1, 1));
        assertEquals(List.of("monster 4 husk"), host.added(builder));
        assertEquals(1, spawns.size());
    }

    @Test
    @DisplayName("an old structure override addSpawn(entry) passes the entry's weight")
    void structureSpawnKeepsTheWeight() throws Exception {
        Host host = Host.threeComponent(false);
        Object entry = host.spawnerData(host.type("pillager"), 9, 1, 1);
        Object overrides = host.load(Overrides.class).getConstructor().newInstance();
        host.helper("addStructureSpawn").invoke(null, overrides, entry);

        assertEquals(9, overrides.getClass().getField("weight").getInt(overrides));
        assertSame(entry, overrides.getClass().getField("entry").get(overrides));
    }

    private static void assertRemoveIfWorks(Host host) throws Exception {
        Object builder = host.builder();
        List<Object> spawns = host.spawnerList(builder, host.monster());
        spawns.add(host.spawnerData(host.type("zombie"), 7, 1, 1));
        spawns.add(host.spawnerData(host.type("skeleton"), 3, 1, 1));

        Predicate<Object> zombies = entry -> "zombie".equals(host.typeName(entry));
        assertTrue(spawns.removeIf(zombies), "the zombie entry matched");
        assertEquals(1, spawns.size());
        assertEquals("skeleton", host.typeName(spawns.get(0)));
        assertFalse(spawns.removeIf(zombies), "nothing is left to remove");
    }

    // Stand-ins, renamed into Minecraft's names inside each host's own class loader.

    public static class Type {
        public final String name;
        public Type(String name) { this.name = name; }
    }

    public static class Category {
        public static final Category MONSTER = new Category();
    }

    public abstract static class Provider {}

    public static class Uniform extends Provider {
        public final int min;
        public final int max;
        private Uniform(int min, int max) { this.min = min; this.max = max; }
        public static Uniform of(int min, int max) { return new Uniform(min, max); }
    }

    /** SpawnerData from 1.21.5 to 26.2. */
    public static final class DataThree {
        private final Type type;
        private final int minCount;
        private final int maxCount;
        public DataThree(Type type, int minCount, int maxCount) {
            this.type = type;
            this.minCount = minCount;
            this.maxCount = maxCount;
        }
        public Type type() { return type; }
        public int minCount() { return minCount; }
        public int maxCount() { return maxCount; }
    }

    /** SpawnerData on 26.3. */
    public static final class DataProvider {
        private final Type type;
        private final Provider count;
        public DataProvider(Type type, Provider count) { this.type = type; this.count = count; }
        public Type type() { return type; }
        public Provider count() { return count; }
    }

    public static final class Weighted {
        private final Object value;
        private final int weight;
        public Weighted(Object value, int weight) { this.value = value; this.weight = weight; }
        public Object value() { return value; }
        public int weight() { return weight; }
    }

    /** NeoForge's weighted builder: a read-only list, and removeIf over the Weighted wrappers. */
    public static class NeoList {
        private final List<Weighted> result = new ArrayList<>();
        public NeoList add(Object entry, int weight) { result.add(new Weighted(entry, weight)); return this; }
        public List<Weighted> getList() { return Collections.unmodifiableList(result); }
        public NeoList removeIf(Predicate<Weighted> filter) {
            for (Iterator<Weighted> it = result.iterator(); it.hasNext(); ) {
                if (filter.test(it.next())) it.remove();
            }
            return this;
        }
    }

    /** Forge's weighted builder: no list, a removeIf over the entries, and a build to read. */
    public static class ForgeList {
        private final List<Weighted> result = new ArrayList<>();
        public ForgeList add(Object entry, int weight) { result.add(new Weighted(entry, weight)); return this; }
        public ForgeList removeIf(Predicate<Object> filter) {
            for (Iterator<Weighted> it = result.iterator(); it.hasNext(); ) {
                if (filter.test(it.next().value())) it.remove();
            }
            return this;
        }
        public ForgeBuilt build() { return new ForgeBuilt(new ArrayList<>(result)); }
    }

    public static class ForgeBuilt {
        private final List<Weighted> entries;
        public ForgeBuilt(List<Weighted> entries) { this.entries = entries; }
        public List<Weighted> unwrap() { return entries; }
    }

    /** The 1.21.5 to 26.2 builder, with the loader's getSpawner. */
    public static class BuilderThree {
        public final List<String> added = new ArrayList<>();
        private final Map<Category, NeoList> spawners = new HashMap<>();
        public BuilderThree addSpawn(Category category, int weight, DataThree entry) {
            added.add("monster " + weight + " " + entry.type().name);
            getSpawner(category).add(entry, weight);
            return this;
        }
        public NeoList getSpawner(Category category) {
            NeoList spawner = spawners.get(category);
            if (spawner == null) {
                spawner = new NeoList();
                spawners.put(category, spawner);
            }
            return spawner;
        }
    }

    /** The 26.3 builder: three addSpawn overloads, and no spawner for an undefined category. */
    public static class BuilderProvider {
        public final List<String> added = new ArrayList<>();
        public int wrongOverload;
        private final Map<Category, NeoList> spawners = new HashMap<>();
        public BuilderProvider addSpawn(Type type, int weight, int min, int max) { wrongOverload++; return this; }
        public BuilderProvider addSpawn(Type type, int weight, Provider count) { wrongOverload++; return this; }
        public BuilderProvider addSpawn(Type type, Category category, int weight, Provider count) {
            added.add("monster " + weight + " " + type.name);
            NeoList spawner = spawners.get(category);
            if (spawner == null) {
                spawner = new NeoList();
                spawners.put(category, spawner);
            }
            spawner.add(new DataProvider(type, count), weight);
            return this;
        }
        public NeoList getSpawner(Category category) { return spawners.get(category); }
    }

    /** NeoForge's structure override builder from 21.11, which takes an entry and a weight. */
    public static class Overrides {
        public Object entry;
        public int weight;
        public void addSpawn(DataThree entry, int weight) { this.entry = entry; this.weight = weight; }
    }

    /** A private loader holding LegacySpawns and the stand-ins for one host shape. */
    private static final class Host {
        private static final String TEST = LegacySpawnsTest.class.getName().replace('.', '/') + "$";
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, String> names = new HashMap<>();
        private final Class<?> builderClass;
        private final ClassLoader loader;

        /** Defines each stand-in under its host name; an alias is only renamed where it is used. */
        private Host(Map<Class<?>, String> standIns, Map<Class<?>, String> aliases, Class<?> builder)
                throws Exception {
            standIns.forEach((type, name) -> names.put(internal(type), name));
            aliases.forEach((type, name) -> names.put(internal(type), name));
            SimpleRemapper remapper = new SimpleRemapper(names);
            for (Class<?> type : standIns.keySet()) {
                classes.put(names.get(internal(type)).replace('/', '.'), remap(type, remapper));
            }
            String helper = LegacySpawns.class.getName();
            classes.put(helper, bytesOf(LegacySpawns.class));
            // The shared lookup helper travels with every embedded copy that calls it.
            classes.put(LegacyPublicMethods.class.getName(), bytesOf(LegacyPublicMethods.class));
            loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name);
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            builderClass = load(builder);
        }

        static Host threeComponent(boolean forge) throws Exception {
            Map<Class<?>, String> standIns = common();
            standIns.put(DataThree.class, "net/minecraft/world/level/biome/MobSpawnSettings$SpawnerData");
            standIns.put(BuilderThree.class, forge ? "net/minecraftforge/common/world/MobSpawnSettingsBuilder"
                    : "net/neoforged/neoforge/common/world/MobSpawnSettingsBuilder");
            standIns.put(Overrides.class,
                    "net/neoforged/neoforge/common/world/StructureSettingsBuilder$StructureSpawnOverrideBuilder");
            // Both weighted builders take the one host name; BuilderThree creates whichever is defined.
            String weighted = "net/minecraft/util/random/WeightedList$Builder";
            Map<Class<?>, String> aliases = new HashMap<>();
            if (forge) {
                standIns.put(ForgeList.class, weighted);
                standIns.put(ForgeBuilt.class, "net/minecraft/util/random/WeightedList");
                aliases.put(NeoList.class, weighted);
            } else {
                standIns.put(NeoList.class, weighted);
            }
            return new Host(standIns, aliases, BuilderThree.class);
        }

        static Host provider() throws Exception {
            Map<Class<?>, String> standIns = common();
            standIns.put(DataProvider.class, "net/minecraft/world/level/biome/MobSpawnSettings$SpawnerData");
            standIns.put(Provider.class, "net/minecraft/util/valueproviders/IntProvider");
            standIns.put(Uniform.class, "net/minecraft/util/valueproviders/UniformInt");
            standIns.put(NeoList.class, "net/minecraft/util/random/WeightedList$Builder");
            standIns.put(BuilderProvider.class, "net/neoforged/neoforge/common/world/MobSpawnSettingsBuilder");
            return new Host(standIns, Map.of(), BuilderProvider.class);
        }

        private static Map<Class<?>, String> common() {
            Map<Class<?>, String> standIns = new HashMap<>();
            standIns.put(Type.class, "net/minecraft/world/entity/EntityType");
            standIns.put(Category.class, "net/minecraft/world/entity/MobCategory");
            standIns.put(Weighted.class, "net/minecraft/util/random/Weighted");
            return standIns;
        }

        Class<?> load(Class<?> standIn) throws ClassNotFoundException {
            return Class.forName(names.get(internal(standIn)).replace('/', '.'), true, loader);
        }

        Method helper(String name) throws ClassNotFoundException {
            for (Method method : Class.forName(LegacySpawns.class.getName(), true, loader).getMethods()) {
                if (method.getName().equals(name)) return method;
            }
            throw new AssertionError("LegacySpawns has no " + name);
        }

        Object type(String name) throws Exception {
            return load(Type.class).getConstructor(String.class).newInstance(name);
        }

        Object monster() throws Exception {
            return load(Category.class).getField("MONSTER").get(null);
        }

        Object builder() throws Exception {
            return builderClass.getConstructor().newInstance();
        }

        Object spawnerData(Object type, int weight, int min, int max) throws Exception {
            return helper("spawnerData").invoke(null, type, weight, min, max);
        }

        void addSpawn(Object builder, Object category, Object entry) throws Exception {
            helper("addSpawn").invoke(null, builder, category, entry);
        }

        @SuppressWarnings("unchecked")
        List<Object> spawnerList(Object builder, Object category) throws Exception {
            return (List<Object>) helper("spawnerList").invoke(null, builder, category);
        }

        @SuppressWarnings("unchecked")
        List<String> added(Object builder) throws Exception {
            return (List<String>) builder.getClass().getField("added").get(builder);
        }

        Object read(Object target, String method) throws Exception {
            return target.getClass().getMethod(method).invoke(target);
        }

        String typeName(Object entry) {
            try {
                Object type = read(entry, "type");
                return (String) type.getClass().getField("name").get(type);
            } catch (Exception e) {
                throw new AssertionError("could not read the entry's type", e);
            }
        }

        private static String internal(Class<?> type) {
            return type.getName().replace('.', '/');
        }

        private static byte[] bytesOf(Class<?> type) throws Exception {
            try (InputStream in = type.getClassLoader().getResourceAsStream(internal(type) + ".class")) {
                return in.readAllBytes();
            }
        }

        /** The stand-in under its host name, without the nest and inner-class links to this test. */
        private static byte[] remap(Class<?> type, SimpleRemapper remapper) throws Exception {
            ClassWriter writer = new ClassWriter(0);
            ClassVisitor detach = new ClassVisitor(Opcodes.ASM9, new ClassRemapper(writer, remapper)) {
                @Override public void visitInnerClass(String name, String outer, String inner, int access) {}
                @Override public void visitOuterClass(String owner, String name, String descriptor) {}
                @Override public void visitNestHost(String nestHost) {}
                @Override public void visitNestMember(String nestMember) {}
            };
            new ClassReader(bytesOf(type)).accept(detach, 0);
            return writer.toByteArray();
        }
    }
}
