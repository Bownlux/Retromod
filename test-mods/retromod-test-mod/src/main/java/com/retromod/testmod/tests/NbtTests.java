/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.SimpleTest;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * NBT (named binary tag). Used heavily by mods for save data, item NBT, and
 * networking. Yarn name {@code NbtCompound} → Mojang {@code CompoundTag}.
 */
public final class NbtTests {

    private NbtTests() {}

    public static List<Test> all() {
        return List.of(
            new SimpleTest("NbtCompound.putString round-trip", () -> {
                NbtCompound c = new NbtCompound();
                c.putString("key", "value");
                return "value".equals(c.getString("key"))
                    ? TestResult.success()
                    : TestResult.fail("getString=" + c.getString("key"));
            }),
            new SimpleTest("NbtCompound.putInt round-trip", () -> {
                NbtCompound c = new NbtCompound();
                c.putInt("k", 42);
                return c.getInt("k") == 42
                    ? TestResult.success()
                    : TestResult.fail("getInt=" + c.getInt("k"));
            }),
            new SimpleTest("NbtCompound.contains", () -> {
                NbtCompound c = new NbtCompound();
                c.putBoolean("here", true);
                if (!c.contains("here")) return TestResult.fail("contains() returned false");
                if (c.contains("not_here")) return TestResult.fail("returned true for missing key");
                return TestResult.success();
            }),
            new SimpleTest("NbtList.add + size + get", () -> {
                NbtList list = new NbtList();
                list.add(NbtString.of("a"));
                list.add(NbtString.of("b"));
                if (list.size() != 2) return TestResult.fail("size=" + list.size());
                if (!"a".equals(list.getString(0))) return TestResult.fail("get(0)=" + list.getString(0));
                return TestResult.success();
            }),
            // 1.21.5 made the number and array getters Optional, removed the UUID helpers, and kept
            // asString only as an Optional that is empty for anything but a string.
            new SimpleTest("NbtCompound byte, int array and byte array getters keep their defaults", () -> {
                NbtCompound c = new NbtCompound();
                c.putInt("wide", 300);
                c.putIntArray("ints", new int[]{1, 2, 3});
                c.putByteArray("bytes", new byte[]{4, 5});
                if (c.getByte("wide") != (byte) 300 || c.getByte("missing") != 0) return TestResult.fail("getByte=" + c.getByte("wide"));
                if (!Arrays.equals(c.getIntArray("ints"), new int[]{1, 2, 3}) || c.getIntArray("missing").length != 0) {
                    return TestResult.fail("getIntArray=" + Arrays.toString(c.getIntArray("ints")));
                }
                if (!Arrays.equals(c.getByteArray("bytes"), new byte[]{4, 5}) || c.getByteArray("ints").length != 0) {
                    return TestResult.fail("getByteArray=" + Arrays.toString(c.getByteArray("bytes")));
                }
                return TestResult.success();
            }),
            new SimpleTest("NbtCompound putUuid writes four ints that getUuid and containsUuid read", () -> {
                UUID owner = UUID.fromString("12345678-9abc-def0-fedc-ba9876543210");
                NbtCompound c = new NbtCompound();
                c.putUuid("Owner", owner);
                if (!owner.equals(c.getUuid("Owner"))) return TestResult.fail("getUuid=" + c.getUuid("Owner"));
                if (!c.containsUuid("Owner") || c.containsUuid("missing")) return TestResult.fail("containsUuid");
                return c.getIntArray("Owner").length == 4 ? TestResult.success() : TestResult.fail("stored as " + c.get("Owner"));
            }),
            new SimpleTest("NbtCompound getType and AbstractNbtNumber intValue", () -> {
                NbtCompound c = new NbtCompound();
                c.putFloat("f", -2.5f);
                if (c.getType("f") != NbtElement.FLOAT_TYPE || c.getType("missing") != 0) return TestResult.fail("getType=" + c.getType("f"));
                int floored = ((AbstractNbtNumber) c.get("f")).intValue();
                return floored == -3 ? TestResult.success() : TestResult.fail("intValue=" + floored);
            }),
            new SimpleTest("NbtElement.asString keeps a string raw and a number plain", () -> {
                String text = NbtString.of("a\"b").asString();
                String number = NbtInt.of(5).asString();
                return "a\"b".equals(text) && "5".equals(number)
                    ? TestResult.success()
                    : TestResult.fail("asString=" + text + " / " + number);
            })
        );
    }
}
