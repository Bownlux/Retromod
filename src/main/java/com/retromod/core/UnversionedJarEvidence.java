/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Decides from its classes whether a Fabric jar with no Minecraft constraint is already built for an unobfuscated host. */
public final class UnversionedJarEvidence {

    // Java 25 class files, the level every 26.x host runs.
    static final int UNOBFUSCATED_HOST_CLASS_MAJOR = 69;
    // Essential nests a loader that nests the mod; deeper than this is treated as unknown.
    private static final int MAX_DEPTH = 4;
    // A current mod can still carry a few intermediary strings, such as Essential's compat patches for
    // other mods; at most one such class per this many Mojang-named classes still counts as current.
    private static final int MOJANG_CLASSES_PER_INTERMEDIARY = 20;
    // A scan this large gives up and leaves the jar to the normal path.
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;

    private static final byte[] MINECRAFT = "net/minecraft/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MINECRAFT_DOTTED = "net.minecraft.".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INTERMEDIARY_CLASS = "class_".getBytes(StandardCharsets.US_ASCII);
    // The minecraft constraint of a Fabric descriptor, or the versions of a Quilt minecraft dependency.
    private static final Pattern FABRIC_MINECRAFT = Pattern.compile("\"minecraft\"\\s*:\\s*(\"[^\"]*\"|\\[[^\\]]*\\])");
    private static final Pattern QUILT_MINECRAFT = Pattern.compile(
            "\"id\"\\s*:\\s*\"minecraft\"[^}]*?\"versions\"\\s*:\\s*(\"[^\"]*\"|\\[[^\\]]*\\])");

    private UnversionedJarEvidence() {}

    /**
     * Whether the Fabric or Quilt mod {@code jar}, with the jars nested in it, is built for
     * {@code hostVersion}. Its classes must name Minecraft by Mojang names with almost no intermediary
     * ones, or name no Minecraft class at all and include a Java 25 class file. Always false on a host
     * that still runs intermediary names, where the names cannot tell an old mod from a current one.
     */
    public static boolean isBuiltForHost(Path jar, String hostVersion) {
        if (jar == null || !RetromodVersion.isUnobfuscatedTarget(hostVersion)) return false;
        try (InputStream in = Files.newInputStream(jar)) {
            Scan scan = new Scan();
            if (!scan.jar(in, 0) || !scan.fabricOrQuilt) return false;
            boolean current = scan.mojangClasses > 0
                    ? scan.intermediaryClasses * MOJANG_CLASSES_PER_INTERMEDIARY <= scan.mojangClasses
                    : scan.intermediaryClasses == 0 && scan.maxMajor >= UNOBFUSCATED_HOST_CLASS_MAJOR;
            if (!current) return false;
            // Mojang names are the same from 26.1 on, so past 26.1 the jar must also point at the host.
            return isFirstUnobfuscatedLine(hostVersion) || namesHost(jar, scan.nestedConstraints, hostVersion);
        } catch (IOException | RuntimeException unreadable) {
            // An unreadable jar gets the normal translation, which reports the problem itself.
            return false;
        }
    }

    /** Whether every declared Minecraft range is free of version numbers, such as {@code "*"}, so it dates nothing. */
    public static boolean datesNothing(List<String> ranges) {
        return ranges.stream().noneMatch(range -> range != null && range.chars().anyMatch(Character::isDigit));
    }

    private static boolean isFirstUnobfuscatedLine(String hostVersion) {
        return hostVersion.equals("26.1") || hostVersion.startsWith("26.1.");
    }

    /** Whether the file name, such as Essential's {@code fabric_26-3}, or a nested descriptor names the host. */
    private static boolean namesHost(Path jar, List<String> nestedConstraints, String hostVersion) {
        String[] parts = hostVersion.split("\\.");
        if (parts.length < 2) return false;
        Pattern host = Pattern.compile("(?<![0-9])" + Pattern.quote(parts[0]) + "[._-]" + Pattern.quote(parts[1])
                + "(?![0-9])");
        if (host.matcher(jar.getFileName().toString()).find()) return true;
        return nestedConstraints.stream().anyMatch(constraint -> host.matcher(constraint).find());
    }

    /** What the classes of one jar tree show. */
    private static final class Scan {
        int intermediaryClasses;
        int mojangClasses;
        int maxMajor;
        long bytes;
        int entries;
        boolean fabricOrQuilt;
        final List<String> nestedConstraints = new ArrayList<>();

        /** Scans one jar stream; false when it hit a limit, so nothing can be concluded. */
        boolean jar(InputStream stream, int depth) throws IOException {
            ZipInputStream zip = new ZipInputStream(new KeepOpen(stream));
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) return false;
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    byte[] data = zip.readAllBytes();
                    bytes += data.length;
                    if (bytes > MAX_BYTES) return false;
                    // A multi-release copy for a newer Java says nothing about the level the mod targets.
                    readClass(data, !name.startsWith("META-INF/versions/"));
                } else if (name.equals("fabric.mod.json") || name.equals("quilt.mod.json")) {
                    // Only the outer jar's descriptor makes it a Fabric or Quilt mod; nested ones may name the host.
                    if (depth == 0) fabricOrQuilt = true;
                    byte[] data = zip.readAllBytes();
                    bytes += data.length;
                    if (bytes > MAX_BYTES) return false;
                    readConstraint(new String(data, StandardCharsets.UTF_8));
                } else if (name.endsWith(".jar")) {
                    if (depth >= MAX_DEPTH || !jar(zip, depth + 1)) return false;
                }
            }
            return true;
        }

        private void readConstraint(String descriptor) {
            for (Pattern pattern : new Pattern[]{FABRIC_MINECRAFT, QUILT_MINECRAFT}) {
                Matcher matcher = pattern.matcher(descriptor);
                while (matcher.find()) nestedConstraints.add(matcher.group(1));
            }
        }

        private void readClass(byte[] data, boolean countsForLevel) {
            if (countsForLevel && data.length >= 8) {
                maxMajor = Math.max(maxMajor, ((data[6] & 0xFF) << 8) | (data[7] & 0xFF));
            }
            // A class naming any net/minecraft/class_<n> counts as intermediary, even when it also has
            // Yarn text in mixin selectors; only a class with Minecraft names and none of those is Mojang-named.
            boolean mojang = false;
            for (byte[] prefix : new byte[][]{MINECRAFT, MINECRAFT_DOTTED}) {
                for (int at = indexOf(data, prefix, 0); at >= 0; at = indexOf(data, prefix, at + 1)) {
                    if (startsWith(data, at + prefix.length, INTERMEDIARY_CLASS)) {
                        intermediaryClasses++;
                        return;
                    }
                    mojang = true;
                }
            }
            if (mojang) mojangClasses++;
        }
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = from; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, int at, byte[] prefix) {
        if (at + prefix.length > data.length) return false;
        for (int j = 0; j < prefix.length; j++) {
            if (data[at + j] != prefix[j]) return false;
        }
        return true;
    }

    /** Lets a nested jar's reader finish without closing the outer one. */
    private static final class KeepOpen extends FilterInputStream {
        KeepOpen(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // The outer stream is closed by its owner.
        }
    }
}
