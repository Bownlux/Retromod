/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.packver;

/** A pack format with a major and a minor number. */
public final class FakePackFormat implements Comparable<FakePackFormat> {
    private final int major;
    private final int minor;

    private FakePackFormat(int major, int minor) {
        this.major = major;
        this.minor = minor;
    }

    public static FakePackFormat of(int major) {
        return new FakePackFormat(major, 0);
    }

    public static FakePackFormat of(int major, int minor) {
        return new FakePackFormat(major, minor);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    @Override
    public int compareTo(FakePackFormat other) {
        return major != other.major ? Integer.compare(major, other.major) : Integer.compare(minor, other.minor);
    }
}
