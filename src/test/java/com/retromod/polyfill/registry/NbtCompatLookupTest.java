/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Each removed NBT call returns what it did before 1.21.5, on stand-ins with the 1.21.5 shapes. */
class NbtCompatLookupTest {

    private static final UUID OWNER = UUID.fromString("12345678-9abc-def0-fedc-ba9876543210");
    // UUIDUtil.uuidToIntArray(OWNER), as 1.21.4's putUUID stored it.
    private static final int[] OWNER_INTS = {0x12345678, 0x9abcdef0, 0xfedcba98, 0x76543210};

    @Test
    @DisplayName("number getters narrow any number tag and return 0 for a missing key or another type")
    void numberGettersKeepTheOldDefaults() {
        ModernCompound tag = new ModernCompound();
        tag.tags.put("b", 7);
        tag.tags.put("big", 300);
        tag.tags.put("s", new ModernString("text"));
        assertEquals(7, NbtCompatLookup.compoundGetByte(tag, "b"));
        assertEquals((byte) 300, NbtCompatLookup.compoundGetByte(tag, "big"));
        assertEquals(300, NbtCompatLookup.compoundGetShort(tag, "big"));
        assertEquals(0, NbtCompatLookup.compoundGetByte(tag, "s"), "a string tag read as a byte was 0");
        assertEquals(0, NbtCompatLookup.compoundGetByte(tag, "missing"));
        assertEquals(0, NbtCompatLookup.compoundGetShort(tag, "missing"));
    }

    @Test
    @DisplayName("array getters return the tag's own array, or a new empty one")
    void arrayGettersReturnTheTagsArray() {
        ModernCompound tag = new ModernCompound();
        int[] ints = {1, 2, 3};
        tag.tags.put("ints", ints);
        tag.tags.put("bytes", new byte[]{4, 5});
        tag.tags.put("longs", new long[]{9L});
        assertSame(ints, NbtCompatLookup.compoundGetIntArray(tag, "ints"), "old callers could edit the tag through it");
        assertArrayEquals(new byte[]{4, 5}, NbtCompatLookup.compoundGetByteArray(tag, "bytes"));
        assertArrayEquals(new long[]{9L}, NbtCompatLookup.compoundGetLongArray(tag, "longs"));
        assertEquals(0, NbtCompatLookup.compoundGetIntArray(tag, "missing").length);
        assertEquals(0, NbtCompatLookup.compoundGetByteArray(tag, "ints").length, "another array type reads as empty");
        assertEquals(0, NbtCompatLookup.compoundGetLongArray(tag, "missing").length);
    }

    @Test
    @DisplayName("putUUID stores four ints and getUUID and hasUUID read them back")
    void uuidKeepsTheIntArrayFormat() {
        ModernCompound tag = new ModernCompound();
        NbtCompatLookup.compoundPutUUID(tag, "Owner", OWNER);
        assertArrayEquals(OWNER_INTS, (int[]) tag.tags.get("Owner"), "worlds saved before 1.21.5 hold this layout");
        assertEquals(OWNER, NbtCompatLookup.compoundGetUUID(tag, "Owner"));
        assertTrue(NbtCompatLookup.compoundHasUUID(tag, "Owner"));
        tag.tags.put("short", new int[]{1, 2, 3});
        assertFalse(NbtCompatLookup.compoundHasUUID(tag, "short"));
        assertFalse(NbtCompatLookup.compoundHasUUID(tag, "missing"));
    }

    @Test
    @DisplayName("getUUID throws for a missing key and for a tag that is not four ints, as 1.21.4 did")
    void getUuidFailsLikeTheOldRead() {
        ModernCompound tag = new ModernCompound();
        tag.tags.put("short", new int[]{1, 2, 3});
        tag.tags.put("text", new ModernString("x"));
        // 1.21.4 called getType() on the missing tag, so mods guard getUUID with hasUUID.
        assertThrows(NullPointerException.class, () -> NbtCompatLookup.compoundGetUUID(tag, "missing"));
        assertThrows(IllegalArgumentException.class, () -> NbtCompatLookup.compoundGetUUID(tag, "short"));
        assertThrows(IllegalArgumentException.class, () -> NbtCompatLookup.compoundGetUUID(tag, "text"));
        assertThrows(NullPointerException.class, () -> NbtCompatLookup.compoundPutUUID(tag, "Owner", null));
    }

    @Test
    @DisplayName("getAsString is a string tag's raw text and any other tag's SNBT form")
    void getAsStringKeepsRawStrings() {
        assertEquals("a\"b", NbtCompatLookup.tagGetAsString(new ModernString("a\"b")));
        assertEquals("5", NbtCompatLookup.tagGetAsString(new ModernInt(5)), "asString() is empty for numbers");
        assertThrows(NullPointerException.class, () -> NbtCompatLookup.tagGetAsString(null));
    }

    @Test
    @DisplayName("getTagType is the stored tag's id, and 0 for a missing key")
    void getTagTypeReadsTheTagId() {
        ModernCompound tag = new ModernCompound();
        tag.tags.put("f", new ModernFloatTag(1f));
        assertEquals(5, NbtCompatLookup.compoundGetTagType(tag, "f"));
        assertEquals(0, NbtCompatLookup.compoundGetTagType(tag, "missing"));
    }

    @Test
    @DisplayName("the number getters read the renamed conversions, so a float still floors")
    void numberGettersUseTheRenamedConversions() {
        ModernFloatTag tag = new ModernFloatTag(-2.5f);
        assertEquals(-3, NbtCompatLookup.numericGetAsInt(tag), "1.21.4 floored a float read as an int");
        assertEquals((byte) -3, NbtCompatLookup.numericGetAsByte(tag));
        assertEquals((short) -3, NbtCompatLookup.numericGetAsShort(tag));
        assertEquals(-2L, NbtCompatLookup.numericGetAsLong(tag));
        assertEquals(-2.5f, NbtCompatLookup.numericGetAsFloat(tag));
        assertEquals(-2.5d, NbtCompatLookup.numericGetAsDouble(tag));
        assertEquals(-2.5f, NbtCompatLookup.numericGetAsNumber(tag));
        assertThrows(NullPointerException.class, () -> NbtCompatLookup.numericGetAsInt(null));
        assertThrows(IllegalStateException.class, () -> NbtCompatLookup.numericGetAsInt(new ModernInt(1)),
                "a host type without intValue() is named in the error");
    }

    @Test
    @DisplayName("a Fabric 1.21.5 to 1.21.11 host is read through the intermediary names")
    void intermediaryHostIsReadByItsNames() {
        IntermediaryCompound tag = new IntermediaryCompound();
        tag.tags.put("b", 7);
        tag.tags.put("ints", new int[]{1, 2});
        NbtCompatLookup.compoundPutUUID(tag, "Owner", OWNER);
        assertEquals(7, NbtCompatLookup.compoundGetByte(tag, "b"));
        assertArrayEquals(new int[]{1, 2}, NbtCompatLookup.compoundGetIntArray(tag, "ints"));
        assertEquals(OWNER, NbtCompatLookup.compoundGetUUID(tag, "Owner"));
        assertEquals(0, NbtCompatLookup.compoundGetInt(tag, "missing"));
        assertEquals("raw", NbtCompatLookup.tagGetAsString(new IntermediaryString("raw")));
        tag.tags.put("i", new IntermediaryIntTag(9));
        assertEquals(3, NbtCompatLookup.compoundGetTagType(tag, "i"));
        assertEquals(9, NbtCompatLookup.numericGetAsInt(new IntermediaryIntTag(9)));
    }

    /** A tag type that keeps Tag's default asString(), as numbers do on 1.21.5. */
    interface ModernTag {
        default Optional<String> asString() {
            return Optional.empty();
        }
    }

    record ModernString(String value) implements ModernTag {
        @Override
        public Optional<String> asString() {
            return Optional.of(value);
        }

        @Override
        public String toString() {
            return '"' + value + '"';
        }
    }

    record ModernInt(int value) implements ModernTag {
        @Override
        public String toString() {
            return Integer.toString(value);
        }
    }

    /** A 1.21.5 float tag: its id and the renamed number conversions. */
    public record ModernFloatTag(float value) {
        public byte getId() {
            return 5;
        }

        public byte byteValue() {
            return (byte) (Math.floor(value));
        }

        public short shortValue() {
            return (short) Math.floor(value);
        }

        public int intValue() {
            return (int) Math.floor(value);
        }

        public long longValue() {
            return (long) value;
        }

        public float floatValue() {
            return value;
        }

        public double doubleValue() {
            return value;
        }

        public Number box() {
            return value;
        }
    }

    /** The 1.21.5 CompoundTag getters, under Mojang names. */
    public static final class ModernCompound {
        final Map<String, Object> tags = new HashMap<>();

        public Object get(String key) {
            return tags.get(key);
        }

        public Optional<Byte> getByte(String key) {
            return tags.get(key) instanceof Number n ? Optional.of(n.byteValue()) : Optional.empty();
        }

        public Optional<Short> getShort(String key) {
            return tags.get(key) instanceof Number n ? Optional.of(n.shortValue()) : Optional.empty();
        }

        public Optional<int[]> getIntArray(String key) {
            return tags.get(key) instanceof int[] value ? Optional.of(value) : Optional.empty();
        }

        public Optional<byte[]> getByteArray(String key) {
            return tags.get(key) instanceof byte[] value ? Optional.of(value) : Optional.empty();
        }

        public Optional<long[]> getLongArray(String key) {
            return tags.get(key) instanceof long[] value ? Optional.of(value) : Optional.empty();
        }

        public void putIntArray(String key, int[] value) {
            tags.put(key, value);
        }
    }

    /** The same getters under the intermediary names Fabric runs on 1.21.5 to 1.21.11. */
    public static final class IntermediaryCompound {
        final Map<String, Object> tags = new HashMap<>();

        public Object method_10580(String key) {
            return tags.get(key);
        }

        public Optional<Byte> method_10571(String key) {
            return tags.get(key) instanceof Number n ? Optional.of(n.byteValue()) : Optional.empty();
        }

        public Optional<Integer> method_10550(String key) {
            return tags.get(key) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
        }

        public Optional<int[]> method_10561(String key) {
            return tags.get(key) instanceof int[] value ? Optional.of(value) : Optional.empty();
        }

        public void method_10539(String key, int[] value) {
            tags.put(key, value);
        }
    }

    /** An int tag under intermediary ids: getId and intValue. */
    public record IntermediaryIntTag(int value) {
        public byte method_10711() {
            return 3;
        }

        public int method_10701() {
            return value;
        }
    }

    public record IntermediaryString(String value) {
        public Optional<String> method_68658() {
            return Optional.of(value);
        }
    }
}
