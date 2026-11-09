/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A mod's own data is never upgraded with the world, so stacks it saved before 1.20.5 go through the host's data fixer
 * from the mod's own data version, and a 1.20.1 JSON name is read as the component it was. The rebuilt classes run
 * against stand-ins shaped like the host's fixer entry point, NBT and text codec.
 */
class LegacyItemDataTest {

    private static final String STAND_INS = "com/retromod/shim/common/ItemDataStandIns$";
    private static final String ITEM_DATA = "com/retromod/legacyapi/minecraft/world/item/LegacyItemData";
    private static final String MEMBERS = LegacyRegistryNbtBridge.MEMBERS;
    private static final String STORAGE = "net/minecraft/world/level/storage/";
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        String[][] names = {{"Tag", "net/minecraft/nbt/Tag"}, {"StringTag", "net/minecraft/nbt/StringTag"},
                {"ListTag", "net/minecraft/nbt/ListTag"}, {"CompoundTag", "net/minecraft/nbt/CompoundTag"},
                {"NbtOps", "net/minecraft/nbt/NbtOps"}, {"DynamicOps", "com/mojang/serialization/DynamicOps"},
                {"JsonOps", "com/mojang/serialization/JsonOps"}, {"Dynamic", "com/mojang/serialization/Dynamic"},
                {"TypeReference", "com/mojang/datafixers/DSL$TypeReference"}, {"DataFixer", "com/mojang/datafixers/DataFixer"},
                {"RecordingFixer", "test/RecordingFixer"}, {"DataFixers", "net/minecraft/util/datafix/DataFixers"},
                {"References", "net/minecraft/util/datafix/fixes/References"},
                {"DataVersion", "net/minecraft/world/level/storage/DataVersion"}, {"WorldVersion", "net/minecraft/WorldVersion"},
                {"CurrentVersion", "test/CurrentVersion"}, {"SharedConstants", "net/minecraft/SharedConstants"},
                {"Component", "net/minecraft/network/chat/Component"},
                {"MutableComponent", "net/minecraft/network/chat/MutableComponent"},
                {"DataResult", "com/mojang/serialization/DataResult"}, {"Result", "test/Result"},
                {"Codec", "com/mojang/serialization/Codec"}, {"TextCodec", "test/TextCodec"},
                {"ComponentSerialization", "net/minecraft/network/chat/ComponentSerialization"}};
        for (String[] name : names) NAMES.put(STAND_INS + name[0], name[1]);
    }

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private HostStubs host;

    @BeforeEach
    void load() throws Exception {
        transformer.clearRedirectsForTesting();
        RebuiltApiClasses.registerTree(transformer, MEMBERS);
        host = new HostStubs();
        for (String standIn : NAMES.keySet()) host.add(standIn(standIn));
        // The verifier checks every method of a rebuilt class, so the save types it passes on are shaped as on the host.
        host.add(HostStubs.iface(STORAGE + "ValueOutput"));
        host.add(HostStubs.plainClass(STORAGE + "TagValueOutput", "java/lang/Object", STORAGE + "ValueOutput"));
        host.add(HostStubs.iface("net/minecraft/core/HolderLookup$Provider"));
        host.add(HostStubs.iface("net/minecraft/core/RegistryAccess"));
        transformer.getSyntheticClasses().forEach(host::add);
    }

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.20.4-shaped stack goes through the host's fixer from the mod's data version to the current one")
    void stackUpgradesFromTheModsVersion() throws Exception {
        Object path = stack("minecraft:grass_path", (byte) 5);

        Object upgraded = call(ITEM_DATA, "upgradeStack", path, 2586);

        assertEquals("minecraft:dirt_path", text(upgraded, "id"), "a 1.16.5 mod's path block gets the 1.17 rename");
        assertEquals(5, values(upgraded).get("count"));
        assertArrayEquals(new int[]{2586, 4700}, updates().get(0), "from the mod's version to the host's");
    }

    @Test
    @DisplayName("a stack already in today's shape is not touched")
    void currentStackUntouched() throws Exception {
        Object current = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
        values(current).put("count", 3);

        assertSame(current, call(ITEM_DATA, "upgradeStack", current, 3465));
        assertTrue(updates().isEmpty());
    }

    @Test
    @DisplayName("a byte count that wrapped past 127 is read unsigned and capped to what the item codec takes")
    void wrappedCountsAreCapped() throws Exception {
        assertEquals(99, values(call(ITEM_DATA, "upgradeStack", stack("minecraft:stone", (byte) -56), 3465)).get("count"));
        assertEquals(1, values(call(ITEM_DATA, "upgradeStack", stack("minecraft:stone", (byte) 0), 3465)).get("count"));
        assertEquals(99, values(call(ITEM_DATA, "upgradeStack", stack("minecraft:stone", (byte) 100), 3465)).get("count"));
    }

    @Test
    @DisplayName("a stack can only be in the old shape up to 1.20.4, so a newer version is read as 1.20.4's")
    void oldShapeReadsAsAtMost1204() throws Exception {
        call(ITEM_DATA, "upgradeStack", stack("minecraft:bread", (byte) 2), 3955);

        assertArrayEquals(new int[]{3700, 4700}, updates().get(0));
    }

    @Test
    @DisplayName("each read of an Items list sees the list as it is now, even after it changed in place")
    void itemsReadAfterAnInPlaceChange() throws Exception {
        Object tag = itemsTag(stack("minecraft:bread", (byte) 5));
        Object items = values(tag).get("Items");
        call(ITEM_DATA, "upgradeItems", tag, 3465);

        values(((List<?>) items).get(0)).put("Slot", (byte) 7);
        call(items, "add", stack("minecraft:stone", (byte) 1));
        List<?> again = (List<?>) values(call(ITEM_DATA, "upgradeItems", tag, 3465)).get("Items");

        assertEquals(2, again.size(), "the added stack is read");
        assertEquals((byte) 7, values(again.get(0)).get("Slot"), "the changed slot is read");
    }

    @Test
    @DisplayName("a caller that edits its upgraded list changes neither the mod's tag nor the next caller's list")
    void upgradedListsAreCopies() throws Exception {
        Object current = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
        values(current).put("count", 1);
        values(current).put("Slot", (byte) 1);
        Object tag = itemsTag(stack("minecraft:bread", (byte) 5), current);

        List<?> first = (List<?>) values(call(ITEM_DATA, "upgradeItems", tag, 3465)).get("Items");
        values(first.get(0)).put("Slot", (byte) 9);
        values(first.get(1)).put("Slot", (byte) 9);
        List<?> second = (List<?>) values(call(ITEM_DATA, "upgradeItems", tag, 3465)).get("Items");

        assertEquals((byte) 0, values(second.get(0)).get("Slot"));
        assertEquals((byte) 1, values(second.get(1)).get("Slot"));
        assertEquals((byte) 1, values(current).get("Slot"), "a stack already current is copied too");
    }

    @Test
    @DisplayName("an Items list with no old stack is handed back in the mod's own tag")
    void currentItemsUntouched() throws Exception {
        Object current = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
        values(current).put("count", 1);
        Object tag = itemsTag(current);

        assertSame(tag, call(ITEM_DATA, "upgradeItems", tag, 3465));
        assertTrue(updates().isEmpty());
    }

    @Test
    @DisplayName("a 1.20.1 JSON name becomes the component it was, and the parent's Items come from the mod's version")
    void forHostReadsA1201Name() throws Exception {
        Object tag = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
        values(tag).put("CustomName", stringTag("{\"text\":\"My Basket\"}"));
        Object items = host.load("net/minecraft/nbt/ListTag").getConstructor().newInstance();
        call(items, "add", stack("minecraft:grass_path", (byte) 2));
        values(tag).put("Items", items);

        Object forHost = call(MEMBERS, "forHost", tag, 2586);

        assertEquals("My Basket", text(values(forHost).get("CustomName"), "text"));
        assertArrayEquals(new int[]{2586, 4700}, updates().get(0));
    }

    @Test
    @DisplayName("a name a player typed after 1.21.5 stays exactly as typed")
    void typedNamesStay() throws Exception {
        for (String typed : List.of("[Ores]", "\"Ores\"", "[\"a\",\"b\"]", "Ores", "{not json", "{\"text\":\"x\"} tail",
                "{\"color\":\"red\"}")) {
            Object tag = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
            Object name = stringTag(typed);
            values(tag).put("CustomName", name);

            Object forHost = call(MEMBERS, "forHost", tag, 3465);

            assertSame(name, values(forHost).get("CustomName"), typed);
        }
    }

    @Test
    @DisplayName("a plain name a 1.20.3 to 1.21.4 mod saved as a JSON string loses its quote marks")
    void jsonStringNamesFrom1203To1214() throws Exception {
        for (int dataVersion : new int[]{3698, 3700, 3955, 4189}) {
            Object forHost = call(MEMBERS, "forHost", namedTag("\"My Basket\""), dataVersion);

            assertEquals("My Basket", text(values(forHost).get("CustomName"), "text"), "data version " + dataVersion);
        }
    }

    @Test
    @DisplayName("a JSON string name stays as typed for a mod from before 1.20.3 or from 1.21.5 on")
    void jsonStringNamesOutsideThoseVersionsStay() throws Exception {
        for (int dataVersion : new int[]{3465, 3578, 4325}) {
            Object tag = namedTag("\"Ores\"");
            Object name = values(tag).get("CustomName");

            assertSame(name, values(call(MEMBERS, "forHost", tag, dataVersion)).get("CustomName"), "data version " + dataVersion);
        }
        for (String typed : List.of("\"a\" tail", "[\"a\"]", "\"unclosed")) {
            Object tag = namedTag(typed);
            Object name = values(tag).get("CustomName");

            assertSame(name, values(call(MEMBERS, "forHost", tag, 3700)).get("CustomName"), typed);
        }
    }

    // ---- stand-ins and reflection ----

    private Object itemsTag(Object... stacks) throws Exception {
        Object tag = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
        Object items = host.load("net/minecraft/nbt/ListTag").getConstructor().newInstance();
        for (Object stack : stacks) call(items, "add", stack);
        values(tag).put("Items", items);
        return tag;
    }

    private Object namedTag(String name) throws Exception {
        Object tag = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
        values(tag).put("CustomName", stringTag(name));
        return tag;
    }

    private Object stack(String id, byte count) throws Exception {
        Object stack = host.load("net/minecraft/nbt/CompoundTag").getConstructor().newInstance();
        values(stack).put("Slot", (byte) 0);
        values(stack).put("id", stringTag(id));
        values(stack).put("Count", count);
        return stack;
    }

    private Object stringTag(String text) throws Exception {
        return host.load("net/minecraft/nbt/StringTag").getConstructor(String.class).newInstance(text);
    }

    private String text(Object tag, String key) throws Exception {
        Object value = values(tag).get(key);
        return (String) value.getClass().getField("value").get(value);
    }

    @SuppressWarnings("unchecked")
    private List<int[]> updates() throws Exception {
        return (List<int[]>) host.load("test/RecordingFixer").getField("UPDATES").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Object tag) throws Exception {
        return (Map<String, Object>) tag.getClass().getField("values").get(tag);
    }

    private Object call(String owner, String name, Object... args) throws Exception {
        for (Method method : host.load(owner).getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) return method.invoke(null, args);
        }
        throw new AssertionError("no " + name + " on " + owner);
    }

    private static Object call(Object target, String name, Object argument) throws Exception {
        return target.getClass().getMethod(name, Object.class).invoke(target, argument);
    }

    /** A stand-in under its host name, as a top-level class of its own. */
    private static byte[] standIn(String name) throws Exception {
        try (InputStream in = LegacyItemDataTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, name);
            ClassWriter writer = new ClassWriter(0);
            ClassVisitor flat = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public void visitNestHost(String nestHost) {}
                @Override public void visitNestMember(String nestMember) {}
                @Override public void visitInnerClass(String n, String outer, String inner, int access) {}
                @Override public void visitOuterClass(String owner, String n, String descriptor) {}
            };
            new ClassReader(in.readAllBytes()).accept(new ClassRemapper(flat, new SimpleRemapper(NAMES)), 0);
            return writer.toByteArray();
        }
    }
}
