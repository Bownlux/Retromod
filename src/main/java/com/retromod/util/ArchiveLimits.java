/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import java.io.IOException;

/** How much the mod jar this thread is translating may unpack to, and whether its resources stream. */
public final class ArchiveLimits {

    /** A jar that unpacks to this much or less is translated without the archive check (1 GB). */
    public static final long NORMAL_TOTAL_BYTES = 1L << 30;

    /** A jar that passed the archive check is translated up to this much without approval (25 GB). */
    public static final long VERIFIED_TOTAL_BYTES = 25L << 30;

    /** What a translation may write beyond a checked jar's declared size, for the files it adds (256 MiB). */
    public static final long OUTPUT_SLACK_BYTES = 256L << 20;

    /**
     * From this declared size on, a translation reads a jar's assets and data from the source jar
     * when a step asks for them, instead of unpacking them to disk or memory (256 MiB).
     */
    public static final long STREAM_RESOURCES_FROM_BYTES = 256L << 20;

    /** The limits for one jar. */
    public record Allowance(long maxTotalBytes, boolean streamResources) {
        public Allowance {
            if (maxTotalBytes <= 0) throw new IllegalArgumentException("maxTotalBytes must be positive");
        }
    }

    /** Ends an allowance and restores the one before it. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    // Outside an admitted jar every reader keeps the cap it always had.
    private static final Allowance DEFAULT = new Allowance(ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, false);
    private static final ThreadLocal<Allowance> CURRENT = new ThreadLocal<>();

    // Tests lower this so a small fixture takes the streaming path.
    private static volatile long streamResourcesFrom = STREAM_RESOURCES_FROM_BYTES;

    private ArchiveLimits() {}

    /** The allowance of the jar this thread translates, or the 500 MB default outside one. */
    public static Allowance current() {
        Allowance allowance = CURRENT.get();
        return allowance == null ? DEFAULT : allowance;
    }

    /** Total unpacked bytes the current jar may reach. */
    public static long maxTotalBytes() {
        return current().maxTotalBytes();
    }

    /** True when the current jar's assets and data stay in the source jar until written. */
    public static boolean streamsResources() {
        return current().streamResources();
    }

    /** Whether a jar that declares {@code unpackedBytes} should stream its resources. */
    public static boolean streamsResourcesFor(long unpackedBytes) {
        return unpackedBytes >= streamResourcesFrom;
    }

    /** Applies {@code allowance} on this thread until the scope closes. */
    public static Scope enter(Allowance allowance) {
        java.util.Objects.requireNonNull(allowance, "allowance");
        Allowance previous = CURRENT.get();
        CURRENT.set(allowance);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** The most one entry unpacked into a translation's folder may reach: 32 MiB for a class, 128 MiB otherwise. */
    public static long entryLimit(String entryName, long remainingTotal) {
        long remaining = Math.max(0, remainingTotal);
        // Steps read files from the folder whole, so a larger file is copied from the source jar instead.
        long cap = entryName != null && entryName.endsWith(".class")
                ? ZipSecurity.MAX_CLASS_ENTRY_SIZE : ZipSecurity.DEFAULT_MAX_ENTRY_SIZE;
        return Math.min(cap, remaining);
    }

    /** Counts one jar's class bytes against {@link ZipSecurity#MAX_CLASS_TOTAL_SIZE}. */
    public static final class ClassBytes {
        private final String jarName;
        private final long limit;
        private long total;

        public ClassBytes(String jarName) {
            this(jarName, ZipSecurity.MAX_CLASS_TOTAL_SIZE);
        }

        ClassBytes(String jarName, long limit) {
            this.jarName = jarName;
            this.limit = limit;
        }

        /** Adds an entry's unpacked bytes when it is a class file, and throws once the classes pass the limit. */
        public void add(String entryName, long bytes) throws IOException {
            if (entryName == null || !entryName.endsWith(".class")) return;
            total += bytes;
            if (total > limit) {
                throw new IOException(jarName + " holds more than " + ArchiveCheck.humanBytes(limit)
                        + " of class files. Retromod keeps a jar's classes in memory while it translates"
                        + " them, so it stopped. A real mod's classes are far smaller; download the jar"
                        + " again from where you got it.");
            }
        }
    }

    /** Test seam: the declared size from which resources stream. */
    public static void setStreamResourcesFromForTesting(long bytes) {
        streamResourcesFrom = bytes;
    }

    /** Test seam: restores {@link #STREAM_RESOURCES_FROM_BYTES}. */
    public static void resetForTesting() {
        streamResourcesFrom = STREAM_RESOURCES_FROM_BYTES;
    }
}
