/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.util.ZipSecurity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Updates Fabric access wideners for hosts that use Mojang names. Fabric reads
 * these files before mod construction, so a stale intermediary namespace can
 * prevent the mod from loading at all.
 */
public final class AccessWidenerRemapper {

    private static final String FABRIC_METADATA = "fabric.mod.json";
    private static final long MAX_METADATA_BYTES = 2L * 1024 * 1024;

    private AccessWidenerRemapper() {}

    /**
     * Whether an entry is an access widener or class tweaker: a file with the usual extension, or
     * one the metadata declares, since Fabric applies a declared file under any name (Lithostitched
     * and Yet Another Config Lib ship theirs as {@code .ct}).
     */
    public static boolean isWidener(String entryName, Set<String> declared) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker") || declared.contains(entryName);
    }

    /** The files a fabric.mod.json declares as wideners or tweakers; none when it is missing or unreadable. */
    public static Set<String> declaredWideners(byte[] fabricModJson) {
        if (fabricModJson == null) return Set.of();
        return Set.copyOf(FabricModTransformer.declaredTweakers(
                new String(fabricModJson, StandardCharsets.UTF_8)));
    }

    /** The wideners declared by the fabric.mod.json in an extracted mod folder. */
    public static Set<String> declaredWideners(Path modFolder) {
        Path metadata = modFolder.resolve(FABRIC_METADATA);
        try {
            if (!Files.isRegularFile(metadata) || Files.size(metadata) > MAX_METADATA_BYTES) return Set.of();
            return declaredWideners(Files.readAllBytes(metadata));
        } catch (IOException e) {
            return Set.of();
        }
    }

    /** The wideners declared by the fabric.mod.json inside a jar held in memory. */
    public static Set<String> declaredWidenersInJar(byte[] jarData) {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarData))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (FABRIC_METADATA.equals(entry.getName())) {
                    return declaredWideners(ZipSecurity.safeReadAllBytes(input, MAX_METADATA_BYTES));
                }
            }
        } catch (IOException | RuntimeException e) {
            // A jar that cannot be read keeps the extension rule alone.
        }
        return Set.of();
    }

    /** Remaps intermediary content and leaves every other namespace unchanged. */
    public static String remapToOfficial(String content, IntermediaryToMojangMapper mapper) {
        try {
            if (content == null || !content.contains("intermediary")) return content;
            String[] lines = content.split("\n", -1);
            StringBuilder patched = new StringBuilder(content.length() + 16);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                String out;
                if (i == 0) {
                    // The header declares the namespace Fabric should expect below.
                    out = line.replace("intermediary", "official");
                } else if (line.isEmpty() || line.startsWith("#")) {
                    out = line;
                } else {
                    out = mapper.remapString(line);
                }
                patched.append(out);
                if (i < lines.length - 1) patched.append("\n");
            }
            return patched.toString();
        } catch (Throwable t) {
            return content;
        }
    }
}
