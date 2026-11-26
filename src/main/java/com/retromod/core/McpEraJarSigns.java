/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Tells whether a jar that declares a newer version in a stale mods.toml is really a 1.12-era build. */
final class McpEraJarSigns {

    private static final Pattern MCVERSION = Pattern.compile("\"mcversion\"\\s*:\\s*\"(\\d+)\\.(\\d+)[^\"]*\"");
    private static final Pattern PACK_FORMAT = Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+)");
    // Class names 1.12.2 code uses everywhere and MCP renamed in 1.13, so a 1.13 to 1.16.5 build never names them.
    private static final List<byte[]> PRE_113_NAMES = List.of(
            bytes("net/minecraft/entity/EntityLivingBase"), bytes("net/minecraft/entity/player/EntityPlayer"),
            bytes("net/minecraft/block/state/IBlockState"), bytes("net/minecraft/util/EnumFacing"),
            bytes("net/minecraft/nbt/NBTTagCompound"), bytes("net/minecraft/world/WorldServer"));
    private static final long MAX_SCANNED_BYTES = 256L << 20;

    private McpEraJarSigns() {
    }

    /**
     * Whether the jar carries a 1.12-era mcmod.info and shows it is such a build: an mcversion below 1.13, a resource
     * pack format of 3 or less, or classes that name 1.12.2 classes. A later mod's stale mcmod.info alone, such as
     * one that still says ${mcversion}, does not count.
     */
    static boolean predates113(Path jar) {
        if (jar == null) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry info = zip.getEntry("mcmod.info");
            if (info == null) return false;
            if (namesVersionBefore113(read(zip, info))) return true;
            ZipEntry pack = zip.getEntry("pack.mcmeta");
            if (pack != null) {
                Matcher format = PACK_FORMAT.matcher(read(zip, pack));
                if (format.find() && Integer.parseInt(format.group(1)) <= 3) return true;
            }
            return namesPre113Classes(zip);
        } catch (IOException | RuntimeException unreadable) {
            // A jar Retromod cannot read here is judged by its declared version alone, as before.
            return false;
        }
    }

    private static boolean namesVersionBefore113(String mcmodInfo) {
        Matcher version = MCVERSION.matcher(mcmodInfo);
        while (version.find()) {
            int major = Integer.parseInt(version.group(1));
            int minor = Integer.parseInt(version.group(2));
            if (major == 1 && minor < 13) return true;
        }
        return false;
    }

    private static boolean namesPre113Classes(ZipFile zip) throws IOException {
        long scanned = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class")) continue;
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = ZipSecurity.safeReadAllBytes(in, ZipSecurity.MAX_CLASS_ENTRY_SIZE);
            }
            // The names sit in the constant pool as plain UTF-8, so a byte search finds them.
            for (byte[] name : PRE_113_NAMES) {
                if (contains(bytes, name)) return true;
            }
            scanned += bytes.length;
            if (scanned > MAX_SCANNED_BYTES) return false;
        }
        return false;
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(ZipSecurity.safeReadAllBytes(in, 1L << 20), StandardCharsets.UTF_8);
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] bytes(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }
}
