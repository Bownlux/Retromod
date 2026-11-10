/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.AccessWidenerRemapper;
import com.retromod.core.FabricModTransformer;
import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.util.McReflect;
import com.retromod.util.RetromodLog;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** The final host methods a jar opens through the access files its loader applies, named as the host reads them. */
public final class OpenedFinalMethods {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-final-overrides");

    /** An access transformer entry that drops {@code final}: access-f, owner, then a field, or a method and its descriptor. */
    private static final Pattern AT_METHOD = Pattern.compile(
            "^\\s*(?:public|protected|private|default)-f\\s+(\\S+)\\s+([\\w$<>*]+)(\\(\\S*)?");
    /** An access widener or class tweaker entry that makes a method overridable. */
    private static final Pattern AW_METHOD = Pattern.compile(
            "^\\s*(?:transitive-)?extendable\\s+method\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)");
    /** One {@code [[accessTransformers]]} table of a mods.toml, up to the next table. */
    private static final Pattern AT_TABLE = Pattern.compile(
            "(?ms)^\\s*\\[\\[accessTransformers]]\\s*$(.*?)(?=^\\s*\\[|\\z)");
    private static final Pattern AT_FILE = Pattern.compile("(?m)^\\s*file\\s*=\\s*\"([^\"]+)\"");
    /** Forge's top-level string list form: {@code accessTransformers = ["a.cfg", "b.cfg"]}. */
    private static final Pattern AT_LIST = Pattern.compile("(?ms)^\\s*accessTransformers\\s*=\\s*\\[(.*?)]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    /** quilt.mod.json's {@code access_widener}, a string or a list of strings. */
    private static final Pattern QUILT_WIDENER = Pattern.compile("(?s)\"access_widener\"\\s*:\\s*(\"[^\"]*\"|\\[[^\\]]*])");

    static final String DEFAULT_AT = "META-INF/accesstransformer.cfg";
    private static final List<String> FORGE_TOMLS = List.of("META-INF/mods.toml", "META-INF/neoforge.mods.toml");
    private static final String FABRIC_JSON = "fabric.mod.json";
    private static final String QUILT_JSON = "quilt.mod.json";

    private OpenedFinalMethods() {}

    /** Reads an extracted jar. */
    public static Set<String> read(Path root, String targetMcVersion) {
        if (root == null) return Set.of();
        Path base = root.toAbsolutePath().normalize();
        return read(name -> {
            Path file = base.resolve(name).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) return null;
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                return null;
            }
        }, targetMcVersion);
    }

    /** Reads an open jar. */
    public static Set<String> read(ZipFile jar, String targetMcVersion) {
        if (jar == null) return Set.of();
        return read(name -> {
            ZipEntry entry = jar.getEntry(name);
            if (entry == null || entry.isDirectory()) return null;
            try (InputStream in = jar.getInputStream(entry)) {
                return ZipSecurity.safeReadAllBytes(in);
            } catch (IOException e) {
                return null;
            }
        }, targetMcVersion);
    }

    /** Reads a nested jar held in memory: its metadata first, then the access files that names. */
    public static Set<String> read(byte[] jarData, String targetMcVersion) {
        if (jarData == null) return Set.of();
        Map<String, byte[]> metadata = entries(jarData,
                name -> name.equals(FABRIC_JSON) || name.equals(QUILT_JSON) || FORGE_TOMLS.contains(name));
        Set<String> wanted = new HashSet<>(accessFiles(metadata::get));
        Map<String, byte[]> files = wanted.isEmpty() ? Map.of() : entries(jarData, wanted::contains);
        return read(name -> metadata.containsKey(name) ? metadata.get(name) : files.get(name), targetMcVersion);
    }

    /** Reads a jar through {@code read}, which answers an entry name with its bytes, or null when there is none. */
    public static Set<String> read(Function<String, byte[]> read, String targetMcVersion) {
        Set<String> opened = new HashSet<>();
        if (forgeFamily()) {
            readAccessTransformers(read, targetMcVersion, opened);
        } else {
            readFabricTweakers(read, targetMcVersion, opened);
        }
        return Set.copyOf(opened);
    }

    /** Whether the host method {@code owner.name desc} is opened, by name or by an owner-wide wildcard. */
    public static boolean opens(Set<String> opened, String owner, String name, String desc) {
        return !opened.isEmpty() && (opened.contains(owner + "." + name + desc) || opened.contains(owner + ".*"));
    }

    private static boolean forgeFamily() {
        return McReflect.isForgeTarget() || McReflect.isNeoForge();
    }

    /** The access files the jar's loader applies, by entry name. */
    private static List<String> accessFiles(Function<String, byte[]> read) {
        if (forgeFamily()) return accessTransformerFiles(read);
        String json = readQuietly(read, FABRIC_JSON);
        if (json != null) return FabricModTransformer.declaredTweakers(json);
        String quilt = readQuietly(read, QUILT_JSON);
        return quilt == null ? List.of() : quiltWideners(quilt);
    }

    /** Forge and NeoForge apply the files a mods.toml names, or META-INF/accesstransformer.cfg when it names none, never a widener. */
    private static void readAccessTransformers(Function<String, byte[]> read, String target, Set<String> opened) {
        for (String name : accessTransformerFiles(read)) {
            // Each file on its own, so one that cannot be read leaves the others counted.
            try {
                String content = readQuietly(read, name);
                if (content == null) continue;
                // Retromod translates the default file only, so a named one is applied as shipped.
                addAccessTransformer(name.equals(DEFAULT_AT)
                        ? ForgeModTransformer.prepareAccessTransformer(content, target) : content, opened);
            } catch (RuntimeException malformed) {
                LOGGER.debug(RetromodLog.DETAIL, "Skipped access transformer {}: {}", name, malformed.toString());
            }
        }
    }

    /** The files a mods.toml declares, in either form; with none declared, the loader reads the default one. */
    private static List<String> accessTransformerFiles(Function<String, byte[]> read) {
        Set<String> files = new LinkedHashSet<>();
        for (String toml : FORGE_TOMLS) {
            String content = readQuietly(read, toml);
            if (content == null) continue;
            Matcher table = AT_TABLE.matcher(content);
            while (table.find()) {
                Matcher file = AT_FILE.matcher(table.group(1));
                if (file.find()) files.add(file.group(1));
            }
            Matcher list = AT_LIST.matcher(content);
            while (list.find()) {
                Matcher file = QUOTED.matcher(list.group(1));
                while (file.find()) files.add(file.group(1));
            }
        }
        if (files.isEmpty()) files.add(DEFAULT_AT);
        return new ArrayList<>(files);
    }

    /** Fabric applies the files fabric.mod.json declares, whatever their extension, as Retromod leaves them; Quilt those quilt.mod.json declares. */
    private static void readFabricTweakers(Function<String, byte[]> read, String target, Set<String> opened) {
        if (target == null) return;
        List<String> declared = accessFiles(read);
        if (declared.isEmpty()) return;
        boolean official = RetromodVersion.isUnobfuscatedTarget(target);
        boolean fabricDeclared = readQuietly(read, FABRIC_JSON) != null;
        String runtimeNamespace = official ? "official" : "intermediary";
        List<String> applied = new ArrayList<>();
        for (String name : declared) {
            String content = readQuietly(read, name);
            // FabricModTransformer.updateFabricModJson drops every declaration unless each file exists
            // and names the host's namespace once its remap pass ran, which on a Mojang-named host
            // rewrites the files fabric.mod.json declares and any other .accesswidener or .classtweaker.
            if (content == null) return;
            boolean remapped = fabricDeclared || AccessWidenerRemapper.isWidener(name, Set.of());
            String hostContent = official && remapped ? remapToOfficial(content) : content;
            if (!FabricModTransformer.tweakerHeaderNamespace(hostContent).equals(runtimeNamespace)) return;
            applied.add(hostContent);
        }
        for (int i = 0; i < applied.size(); i++) {
            try {
                addAccessWidener(applied.get(i), opened);
            } catch (RuntimeException malformed) {
                LOGGER.debug(RetromodLog.DETAIL, "Skipped access widener {}: {}", declared.get(i), malformed.toString());
            }
        }
    }

    private static List<String> quiltWideners(String json) {
        Matcher field = QUILT_WIDENER.matcher(json);
        if (!field.find()) return List.of();
        List<String> files = new ArrayList<>();
        Matcher file = QUOTED.matcher(field.group(1));
        while (file.find()) files.add(file.group(1));
        return files;
    }

    private static String remapToOfficial(String content) {
        IntermediaryToMojangMapper mapper = IntermediaryToMojangMapper.getInstance();
        return mapper.isLoaded() ? AccessWidenerRemapper.remapToOfficial(content, mapper) : content;
    }

    static void addAccessTransformer(String content, Set<String> opened) {
        for (String line : content.split("\n")) {
            // A bare name or * names fields; a method carries its descriptor, and *() means every method.
            Matcher entry = AT_METHOD.matcher(stripComment(line));
            if (!entry.find() || entry.group(3) == null) continue;
            String owner = entry.group(1).replace('.', '/');
            opened.add(owner + "." + (entry.group(2).equals("*") ? "*" : entry.group(2) + entry.group(3)));
        }
    }

    static void addAccessWidener(String content, Set<String> opened) {
        for (String line : content.split("\n")) {
            Matcher entry = AW_METHOD.matcher(stripComment(line));
            if (entry.find()) opened.add(entry.group(1) + "." + entry.group(2) + entry.group(3));
        }
    }

    /** The entries of an in-memory jar whose names {@code wanted} accepts. */
    private static Map<String, byte[]> entries(byte[] jarData, Predicate<String> wanted) {
        Map<String, byte[]> found = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarData))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && wanted.test(entry.getName())) {
                    found.put(entry.getName(), ZipSecurity.safeReadAllBytes(in));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return found;
        }
        return found;
    }

    /** An entry's text, or null when it is missing or cannot be read. */
    private static String readQuietly(Function<String, byte[]> read, String name) {
        try {
            return text(read.apply(name));
        } catch (RuntimeException unreadable) {
            LOGGER.debug(RetromodLog.DETAIL, "Could not read access file {}: {}", name, unreadable.toString());
            return null;
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }
}
