/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.util.JsonSecurity;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.ClassReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Defines narrow pass-through rules for bundled provider archives. */
public final class NestedArchivePolicy {
    private static final String FABRIC_METADATA = "fabric.mod.json";
    private static final String MIXIN_EXTRAS_ID = "mixinextras";
    private static final String FABRIC_CONFIG = "mixinextras.mixins.json";
    private static final String FABRIC_PACKAGE = "com.llamalad7.mixinextras.mixin";
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final String NEOFORGE_CONFIG = "mixinextras.init.mixins.json";
    private static final String NEOFORGE_PLUGIN =
            "com.llamalad7.mixinextras.platform.neoforge.MixinExtrasConfigPlugin";
    private static final String NEOFORGE_PACKAGE =
            "com.llamalad7.mixinextras.platform.neoforge.mixins";
    private static final Set<String> FABRIC_MARKERS = Set.of(
            "com/llamalad7/mixinextras/MixinExtrasBootstrap.class",
            "com/llamalad7/mixinextras/service/MixinExtrasService.class",
            "com/llamalad7/mixinextras/service/MixinExtrasServiceImpl.class");
    private static final Set<String> NEOFORGE_MARKERS = Set.of(
            "com/llamalad7/mixinextras/MixinExtrasBootstrap.class",
            "com/llamalad7/mixinextras/platform/neoforge/MixinExtrasConfigPlugin.class",
            "com/llamalad7/mixinextras/service/MixinExtrasService.class",
            "com/llamalad7/mixinextras/service/MixinExtrasServiceImpl.class");
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final int MAX_JSON_DEPTH = 256;
    private static final long MAX_METADATA_BYTES = 2L * 1024 * 1024;
    private static final long MAX_PROVIDER_CLASS_BYTES = 4L * 1024 * 1024;

    private NestedArchivePolicy() {
    }

    /**
     * Return true only for a bounded nested MixinExtras provider archive. Fabric must match its
     * exact mod ID, Mixin config, package, and provider classes together. NeoForge publishes
     * MixinExtras as a game library without mod metadata, so that variant must match its manifest,
     * initialization config, and provider classes together. The provider owns its bootstrap,
     * service wiring, and Mixin config, so the complete archive must pass through unchanged.
     */
    public static boolean shouldPreserve(Path nestedJar) {
        if (nestedJar == null) {
            return false;
        }
        try {
            return shouldPreserve(nestedJar, RetromodTransformer.NestedArchiveBudget.defaults(),
                    nestedJar.getFileName().toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Checked provider probe that charges the caller's complete nested-archive tree budget. */
    public static boolean shouldPreserve(Path nestedJar,
            RetromodTransformer.NestedArchiveBudget budget, String archiveKey) throws IOException {
        Objects.requireNonNull(nestedJar, "nestedJar");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(archiveKey, "archiveKey");
        ZipSecurity.validateNotSymlink(nestedJar);
        if (!Files.isRegularFile(nestedJar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("nested archive is not a regular file: " + nestedJar);
        }
        if (Files.size(nestedJar) > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) {
            throw new IOException("nested archive exceeds " + ZipSecurity.DEFAULT_MAX_ENTRY_SIZE
                    + " compressed bytes: " + archiveKey);
        }
        try (JarFile jar = new JarFile(nestedJar.toFile())) {
            return inspectProvider(jar, budget, archiveKey);
        }
    }

    private static boolean inspectProvider(JarFile jar,
            RetromodTransformer.NestedArchiveBudget budget, String archiveKey) throws IOException {
        if (jar.size() > MAX_ARCHIVE_ENTRIES) {
            throw new IOException("nested archive contains more than " + MAX_ARCHIVE_ENTRIES
                    + " entries: " + archiveKey);
        }

        ProviderEvidence evidence = collectEvidence(jar, archiveKey);
        boolean preserve = evidence.fabricMetadata != null
                ? hasExactFabricProviderShape(evidence)
                : hasExactNeoForgeProviderShape(evidence);
        if (preserve) {
            chargeArchive(jar, budget, archiveKey);
        }
        return preserve;
    }

    private static ProviderEvidence collectEvidence(JarFile jar, String archiveKey)
            throws IOException {
        ProviderEvidence evidence = new ProviderEvidence();
        Set<String> names = new HashSet<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = ZipSecurity.safeEntryName(entry.getName());
            String canonicalName = ZipSecurity.canonicalEntryName(name);
            String qualifiedName = archiveKey + "!/" + name;
            if (!names.add(canonicalName)) {
                throw new IOException("duplicate nested JAR entry: " + qualifiedName);
            }
            long contentLimit = evidence.contentLimit(name);
            if (!entry.isDirectory() && contentLimit >= 0) {
                evidence.record(name, readEvidence(jar, entry, contentLimit));
            }
        }
        return evidence;
    }

    private static byte[] readEvidence(JarFile jar, JarEntry entry, long maxBytes)
            throws IOException {
        try (InputStream input = jar.getInputStream(entry)) {
            return ZipSecurity.safeReadAllBytes(input, maxBytes + 1);
        }
    }

    private static void chargeArchive(JarFile jar,
            RetromodTransformer.NestedArchiveBudget budget, String archiveKey)
            throws IOException {
        Set<String> names = new HashSet<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = ZipSecurity.safeEntryName(entry.getName());
            String canonicalName = ZipSecurity.canonicalEntryName(name);
            String qualifiedName = archiveKey + "!/" + name;
            if (!names.add(canonicalName)) {
                throw new IOException("duplicate nested JAR entry: " + qualifiedName);
            }
            if (entry.isDirectory()) {
                budget.reserve(0, qualifiedName);
                continue;
            }
            long allowance = budget.beginRead(
                    ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, qualifiedName);
            long actualBytes;
            try (InputStream input = jar.getInputStream(entry)) {
                actualBytes = drainBounded(input, allowance, qualifiedName);
            }
            budget.completeRead(allowance, actualBytes);
        }
    }

    private static boolean hasExactFabricProviderShape(ProviderEvidence evidence)
            throws IOException {
        if (evidence.fabricConfig == null
                || !hasAuthenticMarkers(evidence.providerClasses, FABRIC_MARKERS)
                || evidence.fabricMetadata.length > MAX_METADATA_BYTES
                || evidence.fabricConfig.length > MAX_METADATA_BYTES) {
            return false;
        }
        JsonElement parsed;
        JsonElement configParsed;
        JsonSecurity.validate(evidence.fabricMetadata, MAX_METADATA_BYTES,
                MAX_JSON_DEPTH, "nested Fabric provider metadata");
        JsonSecurity.validate(evidence.fabricConfig, MAX_METADATA_BYTES,
                MAX_JSON_DEPTH, "nested Fabric provider config");
        try {
            parsed = JsonParser.parseString(
                    new String(evidence.fabricMetadata, StandardCharsets.UTF_8));
            configParsed = JsonParser.parseString(
                    new String(evidence.fabricConfig, StandardCharsets.UTF_8));
        } catch (StackOverflowError invalidJson) {
            throw new IOException("nested Fabric provider JSON exceeded parser depth",
                    invalidJson);
        } catch (RuntimeException invalidJson) {
            return false;
        }
        if (!parsed.isJsonObject()) {
            return false;
        }
        JsonObject metadata = parsed.getAsJsonObject();
        JsonElement id = metadata.get("id");
        if (id == null || !id.isJsonPrimitive()
                || !id.getAsJsonPrimitive().isString()
                || !MIXIN_EXTRAS_ID.equals(id.getAsString())
                || !referencesFabricConfig(metadata.get("mixins"))) {
            return false;
        }
        return configParsed.isJsonObject()
                && hasExactString(configParsed.getAsJsonObject(), "package", FABRIC_PACKAGE);
    }

    private static boolean referencesFabricConfig(JsonElement mixins) {
        if (mixins == null || !mixins.isJsonArray()) {
            return false;
        }
        for (JsonElement candidate : mixins.getAsJsonArray()) {
            if (candidate.isJsonPrimitive()
                    && candidate.getAsJsonPrimitive().isString()
                    && FABRIC_CONFIG.equals(candidate.getAsString())) {
                return true;
            }
            if (candidate.isJsonObject()
                    && hasExactString(candidate.getAsJsonObject(), "config", FABRIC_CONFIG)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExactNeoForgeProviderShape(ProviderEvidence evidence)
            throws IOException {
        if (evidence.manifest == null || evidence.neoForgeConfig == null
                || !hasAuthenticMarkers(evidence.providerClasses, NEOFORGE_MARKERS)
                || evidence.manifest.length > MAX_METADATA_BYTES
                || evidence.neoForgeConfig.length > MAX_METADATA_BYTES) {
            return false;
        }

        Manifest manifest;
        try {
            manifest = new Manifest(new ByteArrayInputStream(evidence.manifest));
        } catch (IOException invalidManifest) {
            return false;
        }
        Attributes attributes = manifest.getMainAttributes();
        if (!"GAMELIBRARY".equals(attributes.getValue("FMLModType"))
                || !NEOFORGE_CONFIG.equals(attributes.getValue("MixinConfigs"))) {
            return false;
        }

        JsonElement parsed;
        JsonSecurity.validate(evidence.neoForgeConfig, MAX_METADATA_BYTES,
                MAX_JSON_DEPTH, "nested NeoForge provider config");
        try {
            parsed = JsonParser.parseString(
                    new String(evidence.neoForgeConfig, StandardCharsets.UTF_8));
        } catch (StackOverflowError invalidJson) {
            throw new IOException("nested NeoForge provider JSON exceeded parser depth",
                    invalidJson);
        } catch (RuntimeException invalidJson) {
            return false;
        }
        if (!parsed.isJsonObject()) {
            return false;
        }
        JsonObject config = parsed.getAsJsonObject();
        return hasExactString(config, "plugin", NEOFORGE_PLUGIN)
                && hasExactString(config, "package", NEOFORGE_PACKAGE);
    }

    private static boolean hasExactString(JsonObject object, String key, String expected) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                && expected.equals(value.getAsString());
    }

    private static boolean hasAuthenticMarkers(
            Map<String, byte[]> classes, Set<String> expectedEntries) {
        for (String entryName : expectedEntries) {
            byte[] classBytes = classes.get(entryName);
            if (classBytes == null || classBytes.length > MAX_PROVIDER_CLASS_BYTES) {
                return false;
            }
            try {
                String expectedClass = entryName.substring(0, entryName.length() - 6);
                if (!expectedClass.equals(new ClassReader(classBytes).getClassName())) {
                    return false;
                }
            } catch (RuntimeException invalidClass) {
                return false;
            }
        }
        return true;
    }

    private static long drainBounded(InputStream input, long allowance, String qualifiedName)
            throws IOException {
        byte[] buffer = new byte[8192];
        long actualBytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            actualBytes += read;
            if (actualBytes > allowance) {
                throw new IOException("nested archive entry exceeds " + allowance
                        + " expanded bytes: " + qualifiedName);
            }
        }
        return actualBytes;
    }

    private static final class ProviderEvidence {
        private byte[] fabricMetadata;
        private byte[] fabricConfig;
        private byte[] manifest;
        private byte[] neoForgeConfig;
        private final Map<String, byte[]> providerClasses = new HashMap<>();

        private long contentLimit(String name) {
            if (FABRIC_METADATA.equals(name)
                    || FABRIC_CONFIG.equals(name)
                    || MANIFEST.equalsIgnoreCase(name)
                    || NEOFORGE_CONFIG.equals(name)) {
                return MAX_METADATA_BYTES;
            }
            if (FABRIC_MARKERS.contains(name) || NEOFORGE_MARKERS.contains(name)) {
                return MAX_PROVIDER_CLASS_BYTES;
            }
            return -1;
        }

        private void record(String name, byte[] content) {
            if (FABRIC_METADATA.equals(name)) {
                fabricMetadata = content;
            } else if (FABRIC_CONFIG.equals(name)) {
                fabricConfig = content;
            } else if (MANIFEST.equalsIgnoreCase(name)) {
                manifest = content;
            } else if (NEOFORGE_CONFIG.equals(name)) {
                neoForgeConfig = content;
            }
            if (FABRIC_MARKERS.contains(name) || NEOFORGE_MARKERS.contains(name)) {
                providerClasses.put(name, content);
            }
        }
    }

    /** In-memory counterpart used by CLI and AOT nested archive paths. */
    public static boolean shouldPreserve(byte[] nestedJar) {
        if (nestedJar == null) return false;
        try {
            return shouldPreserve(nestedJar,
                    RetromodTransformer.NestedArchiveBudget.defaults(), "nested-provider.jar");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Checked in-memory provider probe using the caller's aggregate nested-archive budget. */
    public static boolean shouldPreserve(byte[] nestedJar,
            RetromodTransformer.NestedArchiveBudget budget, String archiveKey) throws IOException {
        Objects.requireNonNull(nestedJar, "nestedJar");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(archiveKey, "archiveKey");
        if (nestedJar.length == 0 || nestedJar.length > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) {
            throw new IOException("invalid nested archive size for " + archiveKey + ": "
                    + nestedJar.length + " bytes");
        }
        Path staged = null;
        try {
            staged = Files.createTempFile("retromod-nested-provider-", ".jar");
            Files.write(staged, nestedJar);
            return shouldPreserve(staged, budget, archiveKey);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // The operating system can reclaim an isolated temporary probe file.
                }
            }
        }
    }
}
