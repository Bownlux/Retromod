/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.regex.*;

/**
 * Transforms Forge and NeoForge mods to work on newer Minecraft versions.
 * Like FabricModTransformer, but handles mods.toml / neoforge.mods.toml instead
 * of fabric.mod.json: extract, transform bytecode, patch metadata, repackage.
 */
public class ForgeModTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeTransform");

    /** Per-entry and total extracted size caps (zip-bomb guard). */
    private static final long MAX_ENTRY_SIZE = 50 * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 500 * 1024 * 1024;

    /**
     * Forge/NeoForge mod IDs for APIs Retromod shims. A restrictive versionRange
     * on these in mods.toml would block the mod, so we relax the constraint since
     * the bytecode is already transformed.
     */
    private static final Set<String> SHIMMED_API_MOD_IDS = Set.of(
        "mekanism", "mekanismapi",
        "ae2", "appliedenergistics2",
        "botania", "botania_api",
        "create",
        "thermal", "thermal_foundation", "thermal_expansion", "cofh_core",
        "curios", "curiosapi",
        "baubles",
        "jei", "just_enough_items",
        "nei",
        "jade", "waila", "wthit",
        "cloth_config", "cloth-config",
        "geckolib", "geckolib3", "geckolib4",
        "architectury",
        "patchouli",
        "autoreglib"
    );

    /** Matches a TOML {@code modId = "..."} assignment; find()-based so inline comments don't defeat it. */
    private static final Pattern MOD_ID_PATTERN = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");

    /** Button/widget superclasses that gained MC 26.1's abstract {@code extractContents}. */
    private static final Set<String> BUTTON_SUPERCLASSES = Set.of(
        "net/minecraft/client/gui/components/Button",
        "net/minecraft/client/gui/components/AbstractButton",
        "net/minecraft/client/gui/components/AbstractWidget"
    );

    private final String targetMcVersion;
    private final RetromodTransformer bytecodeTransformer;
    private final MixinCompatibilityTransformer mixinTransformer;

    public ForgeModTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.bytecodeTransformer = RetromodTransformer.getInstance();
        this.mixinTransformer = new MixinCompatibilityTransformer(bytecodeTransformer);
    }

    /**
     * Transform a Forge or NeoForge mod JAR. Returns the transformed JAR path,
     * or null if it failed or was skipped.
     */
    public Path transformMod(Path sourceJar, Path outputDir) throws IOException {
        String originalName = sourceJar.getFileName().toString();
        String baseName = originalName.replace(".jar", "");
        // When a mod ships no module-info, NeoForge/Forge derive the JPMS module
        // name from the jar filename; spaces or odd chars break the derived
        // module's reads and the mod can't resolve core MC classes (#47).
        String safeBaseName = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        String outputName = safeBaseName + "-retromod.jar";
        Path outputJar = outputDir.resolve(outputName);

        LOGGER.info("Checking Forge/NeoForge mod: {}", originalName);

        // Honor mod-author opt-out (META-INF/retromod-opt-out marker).
        if (com.retromod.util.OptOutCheck.isOptedOut(sourceJar)) {
            com.retromod.util.OptOutCheck.logSkipped(sourceJar);
            Path passthrough = outputDir.resolve(originalName);
            Files.copy(sourceJar, passthrough, StandardCopyOption.REPLACE_EXISTING);
            return passthrough;
        }

        String modMcVersion = extractMinecraftVersion(sourceJar);
        if (targetMcVersion.equals(modMcVersion)) {
            LOGGER.info("  {} is already for MC {} - no transformation needed", originalName, targetMcVersion);
            Path directCopy = outputDir.resolve(originalName);
            Files.copy(sourceJar, directCopy, StandardCopyOption.REPLACE_EXISTING);
            return directCopy;
        }

        // Reuse a previous run's output when source + Retromod version + loader all
        // match. The key mirrors the AOT cache; the loader is folded in because the
        // transform differs by host. (adapted from Sinytra Connector (MIT))
        String cacheKey = transformCacheKey(sourceJar);
        if (cacheKey != null && isCacheUpToDate(outputJar, cacheKey)) {
            LOGGER.info("  {} is up to date in the transform cache - reusing {}",
                    originalName, outputName);
            return outputJar;
        }
        // Stale or absent: drop any previous output + sidecar so we do not read a partial one.
        invalidateCache(outputJar);

        LOGGER.info("Transforming Forge/NeoForge mod: {} -> {}", originalName, outputName);

        Path tempDir = Files.createTempDirectory("retromod-forge-");

        try {
            extractJar(sourceJar, tempDir);

            int classesTransformed = transformClasses(tempDir);
            LOGGER.info("Transformed {} class files", classesTransformed);

            int refmapsTransformed = transformRefmaps(tempDir);
            if (refmapsTransformed > 0) {
                LOGGER.info("Transformed {} Mixin refmap(s)", refmapsTransformed);
            }

            makeMixinConfigsNonFatal(tempDir);
            stripAccessWideners(tempDir);
            stripMixinSyntheticPackage(tempDir);

            // Embed registered synthetic classes this mod references under a
            // unique-per-mod Retromod package (split-package-safe) and redirect
            // the mod's references there.
            int synthetics = SyntheticEmbedder.embed(
                    tempDir, sourceJar.getFileName().toString(), bytecodeTransformer);
            if (synthetics > 0) {
                LOGGER.info("Embedded {} referenced synthetic class(es)", synthetics);
            }

            // 1.12.2 mods ship only mcmod.info and modern Forge/NeoForge never scans them;
            // synthesize a mods.toml (#79). promoteToNeoForgeToml below renames it on NeoForge.
            generateTomlFromMcmodInfo(tempDir);

            updateModsToml(tempDir, "META-INF/mods.toml");
            updateModsToml(tempDir, "META-INF/neoforge.mods.toml");
            promoteToNeoForgeToml(tempDir);

            // Patch metadata in nested Jar-in-Jar deps. A bundled dep carries its
            // own mods.toml with a stale minecraft versionRange Forge would reject.
            int jijPatched = patchJarInJarMetadata(tempDir);
            if (jijPatched > 0) {
                LOGGER.info("Patched metadata in {} JIJ dependencies", jijPatched);
            }

            // Migrate bundled data-pack JSON across the 1.21.x -> 26.x format
            // changes the bytecode pass can't reach. Gated to 26.x in ModDataMigrator.
            int dataMigrated = com.retromod.resources.ModDataMigrator.migrateTree(tempDir, targetMcVersion);
            if (dataMigrated > 0) {
                LOGGER.info("Migrated 26.x data formats in {} data file(s)", dataMigrated);
            }

            // Stamp the manifest so runtime code can tell a Retromod-brought mod from a
            // native one (#14/#46). The Fabric path already stamps Retromod-Transformed.
            stampTransformedManifest(tempDir, originalName);

            repackageJar(tempDir, outputJar);
            LOGGER.info("Created transformed mod: {}", outputJar.getFileName());

            // Record the cache sidecar for this exact source + version + loader so a later
            // launch can skip the whole extract/transform when nothing changed.
            if (cacheKey != null) {
                writeCacheSidecar(outputJar, cacheKey);
            }

            if (TransformVerifier.isEnabled()) {
                TransformVerifier.verifyAndReport(outputJar, originalName, targetMcVersion);
            }

            return outputJar;

        } catch (Exception e) {
            LOGGER.error("Failed to transform {}: {}", originalName, e.getMessage());
            return null;
        } finally {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    /** Read the minecraft version a Forge/NeoForge mod JAR targets, or null. */
    private String extractMinecraftVersion(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) entry = jar.getEntry("META-INF/mods.toml");

            if (entry != null) {
                String content = new String(jar.getInputStream(entry).readAllBytes());
                Pattern p = Pattern.compile("versionRange\\s*=\\s*\"\\[([0-9.]+)");
                Matcher m = p.matcher(content);
                // first match is usually the forge/neoforge version; keep
                // scanning for the minecraft one
                while (m.find()) {
                    String version = m.group(1);
                    if (version.startsWith("1.") || version.matches("\\d{2,}\\..*")) {
                        return version;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Extract JAR with zip-bomb protection based on bytes read, not the declared
     * entry size. See {@link FabricModTransformer#extractJar}.
     */
    private void extractJar(Path jarPath, Path outputDir) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            long totalSize = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                Path outputPath = ZipSecurity.safeResolve(outputDir, entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());
                    long writtenBytes;
                    try (InputStream is = jar.getInputStream(entry)) {
                        writtenBytes = copyBounded(is, outputPath, MAX_ENTRY_SIZE, entry.getName());
                    }
                    totalSize += writtenBytes;
                    if (totalSize > MAX_TOTAL_SIZE) {
                        throw new IOException("ZIP total extracted size exceeds limit ("
                            + MAX_TOTAL_SIZE + " bytes) - possible zip bomb (decompressed "
                            + totalSize + " bytes so far)");
                    }
                }
            }
        }
    }

    /** @see FabricModTransformer#copyBounded */
    private static long copyBounded(InputStream is, Path target, long maxBytes,
                                     String entryNameForError) throws IOException {
        long written = 0;
        byte[] buf = new byte[8192];
        try (var out = java.nio.file.Files.newOutputStream(target,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE)) {
            int n;
            while ((n = is.read(buf)) > 0) {
                written += n;
                if (written > maxBytes) {
                    throw new IOException("ZIP entry too large: " + entryNameForError
                        + " (exceeded " + maxBytes + " bytes while reading, "
                        + "possible zip bomb - declared size in central directory "
                        + "may be falsified)");
                }
                out.write(buf, 0, n);
            }
        }
        return written;
    }

    // Package-private for ForgeFrameMergeTest, which pins the frames this rebuilds.
    int transformClasses(Path dir) throws IOException {
        final java.util.List<Path> classFiles;
        try (var stream = Files.walk(dir)) {
            classFiles = stream
                .filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.toString().contains("META-INF"))
                .toList();
        }

        // Classes are independent and the redirect tables are safe to read in parallel.
        final java.util.concurrent.atomic.AtomicInteger counter =
                new java.util.concurrent.atomic.AtomicInteger();

        // Rebuilding a frame needs the mod's own class hierarchy, and those classes are not on the
        // transform classpath. Without this a branch between two of the mod's own types is typed as
        // Object, and the JVM rejects the method as soon as that value is passed somewhere that
        // wants a specific type. The Fabric pass has always done this; Forge and NeoForge did not.
        bytecodeTransformer.setJarClassBytesProvider(name -> {
            try {
                Path cf = dir.resolve(name + ".class");
                return Files.exists(cf) ? Files.readAllBytes(cf) : null;
            } catch (IOException e) {
                return null;
            }
        });
        try {
            com.retromod.core.parallel.RetromodExecutors.parallelForEach(classFiles, classFile -> {
                try {
                    byte[] original = Files.readAllBytes(classFile);
                    String className = dir.relativize(classFile).toString()
                        .replace(".class", "")
                        .replace(File.separator, "/");

                    // Remove handlers that cannot be translated before changing their bytecode.
                    byte[] preStripped = mixinTransformer.stripBlocklistedHandlers(original);
                    // GUI migration needs the old GuiGraphics owner, before the class redirect.
                    if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                            com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
                        preStripped = com.retromod.shim.common.Gui2DTransformMigration.migrate(preStripped);
                    }
                    byte[] transformed = bytecodeTransformer.transformClass(preStripped, className);
                    // Keep the ValueIO repair in the same post-remap position on every loader.
                    if (transformed != null) {
                        transformed = mixinTransformer.applyPostRemapRepairs(transformed);
                    }
                    // These helpers inspect the class and leave unrelated versions unchanged.
                    transformed = com.retromod.shim.forge.ForgeEventBusSynthetics
                            .stripLenientAutoSubscriber(transformed);
                    transformed = com.retromod.shim.forge.Forge1122LifecycleSynthetics
                            .upgradeLegacyModClass(transformed);
                    boolean wroteFirst = false;
                    if (transformed != null && transformed != original) {
                        Files.write(classFile, transformed);
                        counter.incrementAndGet();
                        wroteFirst = true;
                    }

                    // Inject missing abstract methods for Button subclasses.
                    byte[] current = (transformed != null && transformed != original) ? transformed : original;
                    byte[] patched = com.retromod.shim.common.LegacyAbstractButtonBridge
                            .apply(current);
                    if (patched != null && patched != current) {
                        Files.write(classFile, patched);
                        if (!wroteFirst) counter.incrementAndGet();
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not transform class: {} ({}: {})",
                            classFile.getFileName(), e.getClass().getSimpleName(), e.getMessage());
                }
            });
        } finally {
            // A nested jar transforms through this same method, so the provider has to be dropped
            // again or the inner jar's directory would answer for the outer one's classes.
            bytecodeTransformer.clearJarClassBytesProvider();
        }

        return counter.get();
    }

    private int transformRefmaps(Path dir) throws IOException {
        int changed = 0;
        try (var files = Files.walk(dir)) {
            for (Path refmap : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("refmap"))
                    .toList()) {
                String original = Files.readString(refmap);
                String transformed = MixinRefmapRemapper.remapForgeSelectors(
                        original, mixinTransformer);
                if (!original.equals(transformed)) {
                    Files.writeString(refmap, transformed);
                    changed++;
                }
            }
        }
        return changed;
    }

    /** Max Jar-in-Jar nesting depth Retromod recurses through. */
    private static final int MAX_JIJ_DEPTH = 4;

    /** Patch metadata in every nested Jar-in-Jar dep. Returns the count patched. */
    private int patchJarInJarMetadata(Path dir) {
        return patchJarInJarMetadata(dir, 1);
    }

    private int patchJarInJarMetadata(Path dir, int depth) {
        Path jarjarDir = dir.resolve("META-INF/jarjar");
        if (!Files.isDirectory(jarjarDir)) {
            return 0;
        }

        int patched = 0;
        try (var entries = Files.list(jarjarDir)) {
            var jijList = entries.filter(p -> p.toString().endsWith(".jar")).toList();
            for (Path jijJar : jijList) {
                try {
                    if (patchSingleJijJar(jijJar, depth)) {
                        patched++;
                    }
                } catch (Exception e) {
                    // one bad JIJ shouldn't abort the whole transform
                    LOGGER.warn("Could not patch JIJ {}: {}", jijJar.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list JIJ directory: {}", e.getMessage());
        }
        return patched;
    }

    /**
     * Transform a single Jar-in-Jar library: extract, rewrite bytecode with the
     * outer mod's transformer, patch metadata, recurse into its own bundled jars,
     * and repack over the original. Returns true if the jar was rewritten, false
     * for a pure library with nothing to do (#95).
     */
    private boolean patchSingleJijJar(Path jijJar, int depth) throws IOException {
        Path tempDir = Files.createTempDirectory("retromod-jij-");
        try {
            extractJar(jijJar, tempDir);
            int classesTransformed = transformClasses(tempDir);
            int refmapsTransformed = transformRefmaps(tempDir);

            boolean hasForgeToml      = Files.exists(tempDir.resolve("META-INF/mods.toml"));
            boolean hasNeoForgeToml   = Files.exists(tempDir.resolve("META-INF/neoforge.mods.toml"));

            if (hasForgeToml) {
                updateModsToml(tempDir, "META-INF/mods.toml");
            }
            if (hasNeoForgeToml) {
                updateModsToml(tempDir, "META-INF/neoforge.mods.toml");
            }
            promoteToNeoForgeToml(tempDir);

            int dataMigrated = com.retromod.resources.ModDataMigrator.migrateTree(tempDir, targetMcVersion);

            int nestedPatched = (depth < MAX_JIJ_DEPTH) ? patchJarInJarMetadata(tempDir, depth + 1) : 0;

            boolean changed = classesTransformed > 0 || refmapsTransformed > 0
                    || hasForgeToml || hasNeoForgeToml
                    || dataMigrated > 0 || nestedPatched > 0;
            if (!changed) {
                return false;
            }

            repackageJar(tempDir, jijJar);
            LOGGER.debug("Transformed JIJ {} ({} class(es) rewritten)", jijJar.getFileName(), classesTransformed);
            return true;

        } finally {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    /**
     * Synthesize a {@code META-INF/mods.toml} from a pre-1.13 {@code mcmod.info} (#79). 1.12.2 mods
     * predate the toml format, so modern Forge/NeoForge never scans them and the class moves and
     * ctor bridge never run. No-op unless the mod has {@code mcmod.info} and neither toml.
     * The minecraft/forge ranges are relaxed to {@code [1,)}.
     */
    void generateTomlFromMcmodInfo(Path tempDir) throws IOException {
        Path forgeToml = tempDir.resolve("META-INF/mods.toml");
        Path neoToml = tempDir.resolve("META-INF/neoforge.mods.toml");
        Path mcmod = tempDir.resolve("mcmod.info");
        if (Files.exists(forgeToml) || Files.exists(neoToml) || !Files.exists(mcmod)) return;

        String json = Files.readString(mcmod);

        StringBuilder body = new StringBuilder();
        java.util.List<String> ids = new java.util.ArrayList<>();

        // Emit a [[mods]] block per mcmod.info entry: a single 1.12.2 jar can declare several
        // modids (The Betweenlands ships "thebetweenlands" + "mclib"), and a first-entry-only
        // toml left the rest unregistered (#115).
        for (com.google.gson.JsonObject entry : parseMcmodEntries(json)) {
            String modId = jsonStr(entry, "modid");
            if (modId == null) modId = jsonStr(entry, "modId");
            if (modId == null || modId.isBlank()) continue;
            // Sanitize: the modId feeds the derived JPMS module name, so '-' and Java-keyword
            // segments are illegal. (adapted from Sinytra Connector (MIT))
            modId = normalizeModId(modId);
            if (ids.contains(modId)) continue; // two entries collapsing to one id -> one block

            String name = jsonStr(entry, "name");
            if (name == null || name.isBlank()) name = modId;
            appendModBlock(body, modId, jsonStr(entry, "version"), name,
                    jsonStr(entry, "description"), displayTestForMcmodEntry(entry));
            ids.add(modId);
        }

        // Fallback for an mcmod.info the JSON parser can't read (comments, odd encodings): keep the
        // pre-#115 behavior and synthesize a single-mod toml from the first regex-matched id.
        if (ids.isEmpty()) {
            String modId = firstGroup(json, "\"modid\"\\s*:\\s*\"([^\"]+)\"");
            if (modId == null) modId = firstGroup(json, "\"modId\"\\s*:\\s*\"([^\"]+)\"");
            if (modId == null || modId.isBlank()) return; // can't build a toml without the id
            String name = firstGroup(json, "\"name\"\\s*:\\s*\"([^\"]*)\"");
            modId = normalizeModId(modId);
            appendModBlock(body, modId, firstGroup(json, "\"version\"\\s*:\\s*\"([^\"]+)\""),
                    (name == null || name.isBlank()) ? modId : name,
                    firstGroup(json, "\"description\"\\s*:\\s*\"([^\"]*)\""),
                    displayTestForMcmod(json));
            ids.add(modId);
        }

        StringBuilder toml = new StringBuilder()
                .append("# Generated by Retromod from legacy mcmod.info (pre-1.13 Forge mod, #79/#115)\n")
                .append("modLoader=\"javafml\"\n")
                .append("loaderVersion=\"[1,)\"\n")
                .append("license=\"All Rights Reserved\"\n")
                .append(body);
        for (String id : ids) appendDependencyBlocks(toml, id);

        Files.createDirectories(forgeToml.getParent());
        Files.writeString(forgeToml, toml.toString());
        LOGGER.info("Generated META-INF/mods.toml from legacy mcmod.info ({} mod(s): {}, #79/#115)",
                ids.size(), String.join(", ", ids));
    }

    /** One {@code [[mods]]} block. Placeholder/blank versions fall back to 1.0.0; name/desc TOML-escaped. */
    private static void appendModBlock(StringBuilder sb, String modId, String version,
                                       String name, String desc, String displayTest) {
        // mcmod.info often leaves ${version}/@VERSION@ placeholders; fall back to a valid literal.
        if (version == null || version.isBlank() || version.contains("$") || version.contains("@")) {
            version = "1.0.0";
        }
        version = normalizeVersion(version);
        sb.append("\n[[mods]]\n")
          .append("modId=\"").append(modId).append("\"\n")
          .append("version=\"").append(version).append("\"\n")
          .append("displayName=\"").append(tomlEscape(name)).append("\"\n");
        // A side-only mod must tell FML to skip the other side's version handshake, or a
        // vanilla/other-side peer rejects the connection. (adapted from Sinytra Connector (MIT))
        if (displayTest != null) sb.append("displayTest=\"").append(displayTest).append("\"\n");
        sb.append("description=\"").append(tomlEscape(desc == null ? "" : desc)).append("\"\n");
    }

    /** The minecraft + forge dependency array-tables for one modid. */
    private static void appendDependencyBlocks(StringBuilder sb, String modId) {
        sb.append("\n[[dependencies.").append(modId).append("]]\n")
          .append("modId=\"minecraft\"\nmandatory=true\nversionRange=\"[1,)\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
          .append("\n[[dependencies.").append(modId).append("]]\n")
          .append("modId=\"forge\"\nmandatory=true\nversionRange=\"[1,)\"\nordering=\"NONE\"\nside=\"BOTH\"\n");
    }

    /**
     * Parse mcmod.info into its mod entries: the common array form {@code [ {...}, {...} ]} or the
     * v2 wrapper {@code {"modListVersion":2,"modList":[ {...} ]}}. Returns empty (caller falls back
     * to regex) if the JSON can't be read.
     */
    private static java.util.List<com.google.gson.JsonObject> parseMcmodEntries(String json) {
        java.util.List<com.google.gson.JsonObject> out = new java.util.ArrayList<>();
        try {
            com.google.gson.stream.JsonReader jr =
                    new com.google.gson.stream.JsonReader(new java.io.StringReader(json));
            jr.setLenient(true);
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(jr);
            com.google.gson.JsonArray arr = null;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                com.google.gson.JsonObject obj = root.getAsJsonObject();
                if (obj.has("modList") && obj.get("modList").isJsonArray()) {
                    arr = obj.getAsJsonArray("modList"); // mcmod.info v2 wrapper
                } else {
                    out.add(obj); // a bare single object
                    return out;
                }
            }
            if (arr != null) {
                for (com.google.gson.JsonElement el : arr) {
                    if (el.isJsonObject()) out.add(el.getAsJsonObject());
                }
            }
        } catch (Throwable parseFail) {
            // Leave empty: caller uses the regex fallback. Throwable, not Exception, so a hostile
            // mcmod.info that StackOverflowErrors inside gson degrades instead of aborting the jar.
        }
        return out;
    }

    private static String jsonStr(com.google.gson.JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static String displayTestForMcmodEntry(com.google.gson.JsonObject e) {
        if (jsonBool(e, "clientSideOnly")) return IGNORE_ALL;
        if (jsonBool(e, "serverSideOnly")) return IGNORE_SERVER;
        return null;
    }

    private static boolean jsonBool(com.google.gson.JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String firstGroup(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** Make a string safe inside a double-quoted TOML value (no quotes/backslashes/newlines). */
    private static String tomlEscape(String s) {
        return s.replace("\\", " ").replace("\"", "'").replace("\n", " ").replace("\r", " ").trim();
    }

    /**
     * Java reserved words. A JPMS module-name segment equal to one of these is illegal,
     * so a synthesized modid that lands on one gets a suffix. (adapted from Sinytra Connector (MIT))
     */
    private static final Set<String> JAVA_RESERVED = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null", "_"
    );

    /**
     * Normalize a synthesized modid so the derived JPMS module name is valid: FML forbids
     * '-' (and other non [a-z0-9_] chars) in a modid, and a segment equal to a Java keyword
     * breaks module resolution. Lowercase, map illegal chars to '_', and if the result is a
     * reserved word (or would start with a digit) append '_'. (adapted from Sinytra Connector (MIT))
     */
    static String normalizeModId(String modId) {
        String id = modId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (id.isEmpty() || Character.isDigit(id.charAt(0))) {
            id = "_" + id;
        }
        if (JAVA_RESERVED.contains(id)) {
            id = id + "_";
        }
        return id;
    }

    /**
     * Normalize a synthesized mod version for FML/JPMS: '+' (build metadata) is illegal in a
     * module version, and a module version that does not start with a digit is rejected, so fall
     * back to a valid literal in that case. (adapted from Sinytra Connector (MIT))
     */
    static String normalizeVersion(String version) {
        String v = version.replace('+', '_');
        // mcmod.info is untrusted and the JSON path decodes escapes into real quotes/newlines,
        // so a crafted version could break out of the quoted TOML value and inject keys. Allow
        // only a digit-led [A-Za-z0-9._-] version (also a valid JPMS module version); anything
        // else falls back to the literal.
        if (v.isEmpty() || !Character.isDigit(v.charAt(0)) || !v.matches("[A-Za-z0-9._-]+")) {
            return "1.0.0";
        }
        return v;
    }

    /** FML displayTest value that suppresses the peer's version check for a client-only mod. */
    private static final String IGNORE_ALL = "IGNORE_ALL_VERSION";
    /** FML displayTest value that suppresses the server's version check for a server-only mod. */
    private static final String IGNORE_SERVER = "IGNORE_SERVER_VERSION";

    /**
     * The FML {@code displayTest} value for a side, or {@code null} for BOTH/UNKNOWN (leave the
     * default handshake in place). A client-only mod ignores the whole version check so a vanilla
     * server accepts the client; a server-only mod ignores the server's side of the check.
     * (adapted from Sinytra Connector (MIT))
     */
    static String displayTestForEnvironment(ModEnvironmentDetector.ModEnvironment env) {
        return switch (env) {
            case CLIENT -> IGNORE_ALL;
            case SERVER -> IGNORE_SERVER;
            default -> null;
        };
    }

    /** Read mcmod.info's clientSideOnly/serverSideOnly booleans and map to a displayTest value. */
    private static String displayTestForMcmod(String mcmodJson) {
        if (Pattern.compile("\"clientSideOnly\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE)
                .matcher(mcmodJson).find()) {
            return IGNORE_ALL;
        }
        if (Pattern.compile("\"serverSideOnly\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE)
                .matcher(mcmodJson).find()) {
            return IGNORE_SERVER;
        }
        return null;
    }

    /** Update mods.toml / neoforge.mods.toml to target the correct MC version. */
    private void updateModsToml(Path dir, String tomlPath) throws IOException {
        Path tomlFile = dir.resolve(tomlPath);
        if (!Files.exists(tomlFile)) return;

        String content = Files.readString(tomlFile);
        String original = content;

        content = updateMinecraftVersionRange(content);

        // Forge 1.16+ rejects a jar whose mods.toml lacks a top-level license;
        // supply a neutral default when the source has none (#62).
        content = ensureLicense(content);

        // A side-only mod needs displayTest so the other side does not reject the connection.
        content = ensureDisplayTest(content);

        if (!content.contains("retromod_transformed")) {
            content = content + "\n# Transformed by Retromod (original version modified)\n";
        }

        if (!content.equals(original)) {
            Files.writeString(tomlFile, content);
            LOGGER.info("Updated {}: minecraft versionRange -> [{}]", tomlPath, targetMcVersion);
        }
    }

    /**
     * On a NeoForge 1.20.2+ host, promote a legacy {@code META-INF/mods.toml} to
     * {@code META-INF/neoforge.mods.toml}. NeoForge renamed the file in 1.20.2 and
     * skips a jar carrying only {@code mods.toml} at scan time, before any bytecode
     * runs, so a 1.20.1 (Neo)Forge mod never loads (#42). Gated to NeoForge.
     */
    private void promoteToNeoForgeToml(Path tempDir) throws IOException {
        if (!com.retromod.util.McReflect.isNeoForge()) return;
        // 1.20.1 still wants mods.toml
        if (RetromodVersion.mcVersionExceeds("1.20.2", targetMcVersion)) return;

        Path forgeToml = tempDir.resolve("META-INF/mods.toml");
        Path neoToml = tempDir.resolve("META-INF/neoforge.mods.toml");
        if (!Files.exists(forgeToml) || Files.exists(neoToml)) return;

        String content = promoteTomlContentForNeoForge(Files.readString(forgeToml));
        Files.writeString(neoToml, content);
        Files.delete(forgeToml); // NeoForge keys off the filename
        LOGGER.info("Promoted META-INF/mods.toml -> neoforge.mods.toml for NeoForge {} (#42)",
                targetMcVersion);
    }

    /**
     * Apply the mods.toml -> neoforge.mods.toml content transforms (relax the top-level
     * loaderVersion, repoint the mandatory {@code forge} loader dependency at {@code neoforge})
     * without touching the filesystem. Shared by the runtime {@link #promoteToNeoForgeToml} and the
     * offline CLI promotion path, which renames the entry itself.
     */
    public static String promoteTomlContentForNeoForge(String toml) {
        return pointForgeDependencyAtNeoForge(relaxLoaderVersion(toml));
    }

    /**
     * Strip the Mixin synthetic-args dummy package from the extracted mod (#87).
     * Some 1.20.1-era Forge mods ship a placeholder at
     * {@code org/spongepowered/asm/synthetic/args/Dummy.class}. NeoForge 1.20.2+ has
     * its own {@code mixin_synthetic} module owning that package, so a jar still
     * shipping the dummy split-packages with it and module resolution fails at boot.
     * Old Forge hosts still need the dummy.
     */
    private void stripMixinSyntheticPackage(Path tempDir) throws IOException {
        if (!com.retromod.util.McReflect.isNeoForge()) return;
        if (RetromodVersion.mcVersionExceeds("1.20.2", targetMcVersion)) return;

        if (stripMixinSyntheticEntries(tempDir)) {
            LOGGER.info("Stripped org/spongepowered/asm/synthetic/ from mod jar - NeoForge's "
                    + "mixin_synthetic module owns that package (#87)");
        }
    }

    /**
     * Delete {@code org/spongepowered/asm/synthetic/} and prune now-empty ancestors.
     * A mod shading full Mixin keeps its other {@code org/spongepowered/asm/} content.
     */
    static boolean stripMixinSyntheticEntries(Path tempDir) throws IOException {
        Path synthetic = tempDir.resolve("org/spongepowered/asm/synthetic");
        if (!Files.exists(synthetic)) return false;

        try (var walk = Files.walk(synthetic)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }

        // prune now-empty ancestors
        Path parent = synthetic.getParent();
        while (parent != null && !parent.equals(tempDir)) {
            try (var children = Files.list(parent)) {
                if (children.findAny().isPresent()) break;
            }
            Files.delete(parent);
            parent = parent.getParent();
        }
        return true;
    }

    /**
     * Relax the top-level {@code loaderVersion} to {@code "[1,)"}. A Forge/old-NeoForge
     * value is checked against the host's FancyModLoader version, which doesn't track
     * that number, so a literal range rejects the mod on NeoForge.
     */
    static String relaxLoaderVersion(String toml) {
        return toml.replaceAll("(?m)^(\\s*loaderVersion\\s*=\\s*)\"[^\"]*\"", "$1\"[1,)\"");
    }

    /**
     * Ensure the toml declares a top-level {@code license}; Forge 1.16+ rejects a mod
     * whose {@code mods.toml} lacks one (#62). Inserted before the first table header,
     * since TOML top-level keys must precede any {@code [table]}.
     */
    static String ensureLicense(String toml) {
        if (java.util.regex.Pattern.compile("(?m)^\\s*license\\s*=").matcher(toml).find()) {
            return toml; // author already declares one
        }
        String line = "license=\"All Rights Reserved\" # added by Retromod (source declared none) (#62)\n";
        java.util.regex.Matcher firstTable =
                java.util.regex.Pattern.compile("(?m)^\\s*\\[").matcher(toml);
        if (firstTable.find()) {
            return toml.substring(0, firstTable.start()) + line + toml.substring(firstTable.start());
        }
        return toml.endsWith("\n") ? toml + line : toml + "\n" + line;
    }

    /**
     * Ensure a side-only mod declares a {@code displayTest} in its {@code [[mods]]} block, so
     * FML skips the other side's version handshake and a vanilla/other-side peer accepts the
     * connection. No-op for BOTH/UNKNOWN mods or when the author already set displayTest.
     *
     * <p>Side is read only from an author-provided {@code side=} inside the mod's own
     * {@code [[mods]]} block, never from a whole-file scan: elsewhere in an FML toml,
     * {@code side=} lives in {@code [[dependencies.<modid>]]} blocks and describes the
     * DEPENDENCY's side, so a whole-file scan misreads a both-sided mod with a side-scoped
     * dependency as single-sided. The mcmod.info path (clientSideOnly/serverSideOnly) stays
     * the authoritative side source. (adapted from Sinytra Connector (MIT))
     */
    static String ensureDisplayTest(String toml) {
        if (Pattern.compile("(?m)^\\s*displayTest\\s*=").matcher(toml).find()) {
            return toml; // author already declares one
        }
        String value = displayTestForEnvironment(sideFromModsBlock(toml));
        if (value == null) return toml; // BOTH / UNKNOWN / no explicit mod-level side

        Matcher modsHeader = Pattern.compile("(?m)^\\s*\\[\\[mods\\]\\]\\s*$").matcher(toml);
        if (!modsHeader.find()) return toml; // no [[mods]] table to attach it to

        int insertAt = toml.indexOf('\n', modsHeader.end());
        if (insertAt < 0) return toml;
        String line = "\ndisplayTest=\"" + value + "\" # added by Retromod (side-only mod)";
        return toml.substring(0, insertAt) + line + toml.substring(insertAt);
    }

    /**
     * Read an explicit {@code side=} only from the mod's own {@code [[mods]]} block (bounded by
     * the next table header), never from a {@code [[dependencies.*]]} block. Returns
     * {@code UNKNOWN} when no {@code [[mods]]} block exists or it declares no {@code side}.
     */
    static ModEnvironmentDetector.ModEnvironment sideFromModsBlock(String toml) {
        Matcher modsHeader = Pattern.compile("(?m)^\\s*\\[\\[mods\\]\\]\\s*$").matcher(toml);
        if (!modsHeader.find()) return ModEnvironmentDetector.ModEnvironment.UNKNOWN;

        int blockStart = modsHeader.end();
        // The [[mods]] block ends at the next table header ([ ... ) on its own line.
        Matcher nextTable = Pattern.compile("(?m)^\\s*\\[").matcher(toml);
        int blockEnd = toml.length();
        if (nextTable.find(blockStart)) {
            blockEnd = nextTable.start();
        }
        String modsBlock = toml.substring(blockStart, blockEnd);
        return ModEnvironmentDetector.parseSide(modsBlock);
    }

    /**
     * Repoint a mod's mandatory {@code forge} loader dependency at {@code neoforge}.
     * NeoForge has no mod with id {@code forge}, so without this it rejects the mod
     * even after the toml is promoted (#42). Only the {@code forge} dependency id is touched.
     */
    static String pointForgeDependencyAtNeoForge(String toml) {
        return toml.replaceAll("(modId\\s*=\\s*)\"forge\"", "$1\"neoforge\"");
    }

    /**
     * Update the minecraft versionRange in TOML content, and relax ranges for
     * third-party APIs Retromod shims (otherwise the loader blocks the mod even
     * though its bytecode is already transformed).
     */
    private String updateMinecraftVersionRange(String toml) {
        StringBuilder result = new StringBuilder();
        String[] lines = toml.split("\n");
        String currentDepModId = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("[[dependencies")) {
                currentDepModId = null;
            }

            // find() not matches(): a trailing inline comment like modId="forge"
            // #mandatory defeats a full-line match
            Matcher modIdMatcher = MOD_ID_PATTERN.matcher(trimmed);
            if (modIdMatcher.find()) {
                currentDepModId = modIdMatcher.group(1);
            }

            if (currentDepModId != null && trimmed.startsWith("versionRange")) {
                if ("minecraft".equals(currentDepModId)) {
                    result.append("versionRange = \"[").append(targetMcVersion).append(",)\"\n");
                    LOGGER.info("  Updated minecraft versionRange -> [{},...)", targetMcVersion);
                    currentDepModId = null;
                } else if (SHIMMED_API_MOD_IDS.contains(currentDepModId)) {
                    result.append("versionRange = \"[0,)\"\n");
                    LOGGER.info("  Relaxed API dependency: {} -> [0,...) (Retromod has shims)", currentDepModId);
                    currentDepModId = null;
                } else if ("forge".equals(currentDepModId) || "neoforge".equals(currentDepModId)) {
                    result.append("versionRange = \"[0,)\"\n");
                    currentDepModId = null;
                } else if (targetMcVersion.startsWith("26.")) {
                    // 26.1+: relax all non-core deps; most old versions aren't available
                    result.append("versionRange = \"[0,)\"\n");
                    LOGGER.info("  Relaxed dependency: {} -> [0,...) (26.1+ compat)", currentDepModId);
                    currentDepModId = null;
                } else {
                    result.append(line).append("\n");
                }
            } else if (currentDepModId != null &&
                       !("minecraft".equals(currentDepModId) || "neoforge".equals(currentDepModId) || "forge".equals(currentDepModId))
                       && trimmed.startsWith("mandatory")) {
                result.append("mandatory = false\n");
            } else if (currentDepModId != null &&
                       !("minecraft".equals(currentDepModId) || "neoforge".equals(currentDepModId) || "forge".equals(currentDepModId))
                       && trimmed.matches("type\\s*=\\s*\"required\".*")) {
                // NeoForge format: type="required" -> "optional"
                result.append(line.replaceFirst("\"required\"", "\"optional\"")).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Inject a no-op {@code extractContents} into Button/AbstractButton subclasses.
     * MC 26.1's AbstractButton gained that abstract method; old widgets that don't
     * implement it hit AbstractMethodError.
     */
    private byte[] injectMissingAbstractMethods(byte[] classBytes, String className) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            String superName = reader.getSuperName();
            if (superName == null) return null;

            if (!BUTTON_SUPERCLASSES.contains(superName)) {
                return null;
            }

            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            String methodName = "extractContents";
            String methodDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

            boolean hasMethod = false;
            for (MethodNode method : classNode.methods) {
                if (method.name.equals(methodName)) {
                    hasMethod = true;
                    break;
                }
            }

            if (hasMethod) {
                return null;
            }

            // no-op body; can't call super since it's abstract
            MethodNode newMethod = new MethodNode(
                Opcodes.ACC_PUBLIC,
                methodName,
                methodDesc,
                null,
                null
            );

            newMethod.instructions = new InsnList();
            newMethod.instructions.add(new InsnNode(Opcodes.RETURN));
            newMethod.maxStack = 0;
            newMethod.maxLocals = 5;

            classNode.methods.add(newMethod);

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            byte[] result = writer.toByteArray();
            LOGGER.info("Injected missing extractContents() into {} (extends {})", className, superName);
            return result;
        } catch (Exception e) {
            LOGGER.debug("Could not inject abstract methods into {}: {}", className, e.getMessage());
            return null;
        }
    }

    /** Make every mixin config under {@code dir} non-fatal. */
    private void makeMixinConfigsNonFatal(Path dir) {
        try (var stream = Files.walk(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String entryName = dir.relativize(file).toString()
                    .replace(File.separator, "/");
                if (isMixinConfigFile(entryName)) {
                    try {
                        String json = Files.readString(file);
                        String transformed = makeMixinConfigNonFatal(json);
                        if (!transformed.equals(json)) {
                            Files.writeString(file, transformed, java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to process mixin config {}: {}", entryName, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan for mixin configs: {}", e.getMessage());
        }
    }

    /**
     * Set {@code "required": false} and {@code "injectors": {"defaultRequire": 0}}
     * so @Accessor/@Invoker targeting fields/methods removed in newer MC don't crash.
     */
    private String makeMixinConfigNonFatal(String json) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            root.addProperty("required", false);

            com.google.gson.JsonObject injectors = root.has("injectors") && root.get("injectors").isJsonObject()
                ? root.getAsJsonObject("injectors")
                : new com.google.gson.JsonObject();
            injectors.addProperty("defaultRequire", 0);
            root.add("injectors", injectors);

            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();
            String result = gson.toJson(root);
            LOGGER.debug("Set mixin config to non-fatal (required=false, defaultRequire=0)");
            return result;
        } catch (Exception e) {
            LOGGER.warn("Failed to make mixin config non-fatal: {}", e.getMessage());
            return json;
        }
    }

    private boolean isMixinConfigFile(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        if (name.endsWith(".mixins.json")) return true;       // modid.mixins.json
        if (name.endsWith("mixin.json")) return true;         // modid.mixin.json
        if (name.startsWith("mixins.") && name.endsWith(".json")) return true; // mixins.modid.json
        if (path.contains("mixins/") && name.endsWith(".json") && name.contains("mixin")) return true;
        return false;
    }

    /**
     * Convert Fabric access wideners / class tweakers in a Forge/NeoForge jar
     * (cross-loader mods ship both) into an AccessTransformer at
     * {@code META-INF/accesstransformer.cfg}, then remove the AW file (Forge
     * can't read that format).
     *
     * <p>Skipped when the jar already declares an AT at that path, so an
     * author-provided AT is never clobbered. The converter refuses an
     * intermediary-namespace AW (no name remapping happens here), which is then
     * simply deleted rather than turned into an unresolvable AT.
     */
    private void stripAccessWideners(Path dir) {
        Path atFile = dir.resolve("META-INF").resolve("accesstransformer.cfg");
        boolean containsAT = Files.exists(atFile);
        StringBuilder generatedAt = new StringBuilder();

        try (var stream = Files.walk(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                String nameLower = name.toLowerCase(java.util.Locale.ROOT);
                if (nameLower.endsWith(".accesswidener") || nameLower.endsWith(".classtweaker")) {
                    if (!containsAT) {
                        try {
                            String at = AccessWidenerToAtConverter.convert(Files.readString(file));
                            if (!at.isBlank()) {
                                generatedAt.append(at);
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Could not convert access widener {}: {}", name, e.getMessage());
                        }
                    }
                    LOGGER.info("Removing Fabric access widener from Forge mod: {}", name);
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to scan for access wideners: {}", e.getMessage());
        }

        if (generatedAt.length() > 0) {
            try {
                Files.createDirectories(atFile.getParent());
                Files.writeString(atFile, generatedAt.toString());
                LOGGER.info("Emitted META-INF/accesstransformer.cfg from Fabric access widener(s)");
            } catch (IOException e) {
                LOGGER.warn("Failed to write generated accesstransformer.cfg: {}", e.getMessage());
            }
        }
    }

    // --- Runtime transform cache + membership stamp (Sinytra Tier A #1/#2) ---

    /** Manifest attribute stamped on every Retromod-transformed Forge/NeoForge jar. */
    static final String TRANSFORMED_ATTRIBUTE = "Retromod-Transformed";
    /** Suffix of the per-output cache sidecar holding the "version,loader,sha256" key. */
    static final String CACHE_SIDECAR_SUFFIX = ".retromod-cache";

    /** The loader tag folded into the cache key (the transform differs by host). */
    private static String loaderTag() {
        try {
            return com.retromod.util.McReflect.isNeoForge() ? "neoforge" : "forge";
        } catch (Throwable t) {
            return "forge";
        }
    }

    /**
     * The cache key for a source jar: {@code retromodVersion,loader,sha256(source)}, mirroring the
     * AOT key. Returns null if the source can't be hashed (then the cache is simply not used).
     * (adapted from Sinytra Connector (MIT))
     */
    private String transformCacheKey(Path sourceJar) {
        String hash = sha256(sourceJar);
        if (hash == null) return null;
        return RetromodVersion.RETROMOD_VERSION + "," + loaderTag() + "," + targetMcVersion + "," + hash;
    }

    /** The sidecar path for a given transformed output jar. */
    private static Path cacheSidecar(Path outputJar) {
        return outputJar.resolveSibling(outputJar.getFileName().toString() + CACHE_SIDECAR_SUFFIX);
    }

    /** True when the output jar and its sidecar both exist and the sidecar matches the current key. */
    private boolean isCacheUpToDate(Path outputJar, String expectedKey) {
        try {
            Path sidecar = cacheSidecar(outputJar);
            if (!Files.exists(outputJar) || !Files.exists(sidecar)) return false;
            return expectedKey.equals(Files.readString(sidecar).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** Delete a stale/partial output and its sidecar so a re-transform starts clean. */
    private void invalidateCache(Path outputJar) {
        try {
            Files.deleteIfExists(cacheSidecar(outputJar));
        } catch (IOException ignored) {}
    }

    /** Write the cache sidecar next to a freshly transformed output jar. */
    private void writeCacheSidecar(Path outputJar, String key) {
        try {
            Files.writeString(cacheSidecar(outputJar), key);
        } catch (IOException e) {
            LOGGER.debug("Could not write transform cache sidecar for {}: {}",
                    outputJar.getFileName(), e.getMessage());
        }
    }

    /** SHA-256 hex of a file, or null on error. */
    private static String sha256(Path file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Add the {@code Retromod-Transformed} manifest attribute to the extracted mod before
     * repackaging, so {@link #isTransformedMod(Path)} (and pitfalls #14/#46 guards) can tell a
     * Retromod-brought mod from a native one. Creates a manifest if the source lacked one.
     */
    private void stampTransformedManifest(Path tempDir, String originalName) {
        Path manifestFile = tempDir.resolve("META-INF/MANIFEST.MF");
        try {
            Manifest manifest;
            if (Files.exists(manifestFile)) {
                try (InputStream is = Files.newInputStream(manifestFile)) {
                    manifest = new Manifest(is);
                }
            } else {
                manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            }
            manifest.getMainAttributes().putValue(TRANSFORMED_ATTRIBUTE, "true");
            manifest.getMainAttributes().putValue("Retromod-Target-Version", targetMcVersion);
            manifest.getMainAttributes().putValue("Retromod-Original-Jar", originalName);
            Files.createDirectories(manifestFile.getParent());
            try (OutputStream os = Files.newOutputStream(manifestFile)) {
                manifest.write(os);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not stamp Retromod-Transformed manifest for {}: {}",
                    originalName, e.getMessage());
        }
    }

    /**
     * Runtime membership predicate: did Retromod transform this jar (Forge/NeoForge or Fabric)?
     * Reads the {@code Retromod-Transformed} manifest attribute stamped at transform time. Used
     * to ensure Retromod never mutates a native mod (pitfalls #14/#46). Returns false on any
     * read error or when the attribute is absent.
     */
    public static boolean isTransformedMod(Path jar) {
        if (jar == null || !Files.exists(jar)) return false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return false;
            return "true".equalsIgnoreCase(mf.getMainAttributes().getValue(TRANSFORMED_ATTRIBUTE));
        } catch (IOException e) {
            return false;
        }
    }

    private void repackageJar(Path sourceDir, Path outputJar) throws IOException {
        Path parent = outputJar.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("JAR output has no parent: " + outputJar);
        Files.createDirectories(parent);
        Path stagedJar = Files.createTempFile(parent,
                "." + outputJar.getFileName() + ".", ".tmp");
        try {
            writeJar(sourceDir, stagedJar);
            try (JarFile ignored = new JarFile(stagedJar.toFile())) {
                // Opening the completed archive validates its central directory.
            }
            moveReplacing(stagedJar, outputJar);
        } finally {
            Files.deleteIfExists(stagedJar);
        }
    }

    private void writeJar(Path sourceDir, Path outputJar) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outputJar)))) {

            // ZIP directory entries: package resources (ClassLoader.getResources) and classpath
// scanners (Reflections - YungsApi @AutoRegister) silently find nothing without them.
            com.retromod.util.JarDirectoryEntries.writeAll(jos, sourceDir);

            // A source mod's central directory can list an entry twice (or two
            // collide on a case-insensitive FS); the second putNextEntry would throw
            // ZipException. Keep the first, skip dupes.
            Set<String> writtenEntries = new java.util.HashSet<>();

            try (var walk = Files.walk(sourceDir)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String entryName = sourceDir.relativize(file).toString()
                        .replace(File.separator, "/");

                    if (!writtenEntries.add(entryName)) {
                        LOGGER.warn("Skipping duplicate JAR entry from source: {} (keeping first copy)",
                                entryName);
                        continue;
                    }

                    JarEntry entry = new JarEntry(entryName);
                    jos.putNextEntry(entry);
                    Files.copy(file, jos);
                    jos.closeEntry();
                }
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
