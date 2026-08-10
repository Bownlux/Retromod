/*
 * Retromod - per-mod synthetic-class embedding.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Embeds Retromod's registered synthetic classes (ASM-generated polyfills for deleted MC or
 * loader classes) into the NeoForge/Forge mods that reference them, and rewrites those
 * references to the embedded copies.
 *
 * <p>Each copy goes under {@code com/retromod/embedded/<mod-key>/}, unique per mod. Embedding at
 * the original name would split-package with the loader/neoforge modules that still own those
 * packages (and two mods would split-package with each other), which is a JPMS crash.
 */
public final class SyntheticEmbedder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    /** Retromod-owned package root for embedded synthetics. */
    public static final String PREFIX = "com/retromod/embedded/";

    private SyntheticEmbedder() {}

    /**
     * Register one of Retromod's compiled helper classes for per-mod embedding.
     *
     * <p>{@link VersionShim#getShimClasses()} is useful for reporting, but the embedder works from
     * the transformer's registered byte arrays. A redirect to a compiled helper therefore has to
     * register that helper here as well or an offline transform leaves a Retromod-only call owner
     * in the output jar.
     *
     * @return true when the class was already registered or its bytes were loaded successfully
     */
    public static boolean registerClassResource(
            RetromodTransformer transformer, String internalName, Class<?> resourceAnchor) {
        if (transformer == null || internalName == null || resourceAnchor == null) return false;
        if (transformer.getSyntheticClasses().containsKey(internalName)) return true;
        try (var in = resourceAnchor.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                LOGGER.warn("Could not find compatibility class resource {}.class", internalName);
                return false;
            }
            transformer.registerSyntheticClass(internalName, in.readAllBytes());
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not register compatibility class {}: {}", internalName, e.toString());
            return false;
        }
    }

    /**
     * Embed every registered synthetic that {@code modDir}'s classes reference and rewrite those
     * references to the embedded copies.
     *
     * @param modDir    extracted mod directory (classes at their package paths)
     * @param uniqueKey per-mod identifier (mod id or jar name) keeping the embedded package distinct
     * @return number of synthetics embedded (0 if none referenced / none registered / error)
     */
    public static int embed(Path modDir, String uniqueKey, RetromodTransformer transformer) {
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        if (synthetics == null || synthetics.isEmpty()) return 0;
        try {
            final List<Path> classFiles;
            try (var s = Files.walk(modDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.toString().contains("META-INF"))
                        .toList();
            }

            // which registered synthetics does this mod reference?
            Set<String> referenced = new HashSet<>();
            for (Path cf : classFiles) {
                try {
                    referenced.addAll(referencedClasses(Files.readAllBytes(cf)));
                } catch (IOException ignored) {
                }
            }
            referenced.retainAll(synthetics.keySet());
            if (referenced.isEmpty()) return 0;
            expandTransitively(referenced, synthetics);

            String base = PREFIX + sanitize(uniqueKey) + "/";
            Map<String, String> rename = new HashMap<>();
            for (String n : referenced) {
                rename.put(n, base + n.substring(n.lastIndexOf('/') + 1));
            }
            Remapper remapper = new SimpleRemapper(rename);

            for (String n : referenced) {
                byte[] renamed = remap(synthetics.get(n), remapper);
                Path target = modDir.resolve(rename.get(n) + ".class");
                Files.createDirectories(target.getParent());
                Files.write(target, renamed);
            }
            for (Path cf : classFiles) {
                byte[] in = Files.readAllBytes(cf);
                byte[] out = remap(in, remapper);
                if (!Arrays.equals(in, out)) Files.write(cf, out);
            }
            LOGGER.info("Embedded {} referenced synthetic class(es) into '{}' under {}",
                    referenced.size(), uniqueKey, base);
            return referenced.size();
        } catch (Exception e) {
            LOGGER.warn("Synthetic embedding skipped for '{}': {}", uniqueKey, e.toString());
            return 0;
        }
    }

    /**
     * A synthetic may reference OTHER registered synthetics (e.g. the Forge 26.2 LegacyEventBus
     * bridge constructs ReflectedConsumer, which no mod class names directly). Expand the
     * mod-referenced set to its transitive closure so an embedded copy never dangles on a
     * helper that was renamed away with it - or worse, never embedded at all.
     */
    private static void expandTransitively(Set<String> referenced, Map<String, byte[]> synthetics) {
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>(referenced);
        while (!work.isEmpty()) {
            byte[] bytes = synthetics.get(work.poll());
            if (bytes == null) continue;
            for (String dep : referencedClasses(bytes)) {
                if (synthetics.containsKey(dep) && referenced.add(dep)) work.add(dep);
            }
        }
    }

    /**
     * Jar-based variant for the offline CLI/AOT paths: reads {@code jarPath}, embeds the referenced
     * synthetics, rewrites references, and writes the jar back. Uses {@code Zip*Stream} so
     * {@code META-INF/MANIFEST.MF} and every other entry survive.
     */
    public static int embedIntoJar(Path jarPath, String uniqueKey, RetromodTransformer transformer) {
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        if (synthetics == null || synthetics.isEmpty()) return 0;
        try {
            java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
            long total = 0;
            try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(jarPath))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    // Bound per-entry (50MB) and aggregate (500MB) like the other extract paths:
                    // this reads an untrusted jar fully into memory, and the per-entry cap alone
                    // doesn't stop a many-entry decompression bomb. On overflow, bail: the outer
                    // catch returns 0 and the original jar is untouched, since writes go through
                    // the temp-then-move below.
                    byte[] data = e.isDirectory() ? new byte[0]
                            : com.retromod.util.ZipSecurity.safeReadAllBytes(zis);
                    total += data.length;
                    if (total > com.retromod.util.ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                        throw new java.io.IOException("jar exceeds max total size during synthetic embed: "
                                + jarPath.getFileName());
                    }
                    // directory entries are retained: dropping them breaks package-resource
                    // lookups and classpath scanners in the rewritten jar
                    entries.put(e.getName(), data);
                }
            }
            Set<String> referenced = new HashSet<>();
            for (var en : entries.entrySet()) {
                if (en.getKey().endsWith(".class")) referenced.addAll(referencedClasses(en.getValue()));
            }
            referenced.retainAll(synthetics.keySet());
            if (referenced.isEmpty()) return 0;
            expandTransitively(referenced, synthetics);

            String base = PREFIX + sanitize(uniqueKey) + "/";
            Map<String, String> rename = new HashMap<>();
            for (String n : referenced) rename.put(n, base + n.substring(n.lastIndexOf('/') + 1));
            Remapper remapper = new SimpleRemapper(rename);

            java.util.LinkedHashMap<String, byte[]> out = new java.util.LinkedHashMap<>();
            for (var en : entries.entrySet()) {
                byte[] v = en.getValue();
                if (en.getKey().endsWith(".class")) v = remap(v, remapper);
                out.put(en.getKey(), v);
            }
            for (String n : referenced) out.put(rename.get(n) + ".class", remap(synthetics.get(n), remapper));

            // write to a sibling temp + move: rewriting in place with a truncating stream
            // destroys the jar if anything throws mid-write (review finding)
            Path tmp = jarPath.resolveSibling(jarPath.getFileName() + ".rmtmp");
            try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
                for (var en : out.entrySet()) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(en.getKey()));
                    zos.write(en.getValue());
                    zos.closeEntry();
                }
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            Files.move(tmp, jarPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Embedded {} referenced synthetic class(es) into jar '{}' under {}",
                    referenced.size(), uniqueKey, base);
            return referenced.size();
        } catch (Exception e) {
            LOGGER.warn("Synthetic embedding (jar) skipped for '{}': {}", uniqueKey, e.toString());
            return 0;
        }
    }

    /** Internal class names referenced by a class. */
    static Set<String> referencedClasses(byte[] classBytes) {
        Set<String> refs = new HashSet<>();
        try {
            new ClassReader(classBytes).accept(new ClassRemapper(
                    new ClassWriter(0),
                    new Remapper() {
                        @Override public String map(String internalName) {
                            refs.add(internalName);
                            return internalName;
                        }
                    }), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (Exception ignored) {
        }
        return refs;
    }

    private static byte[] remap(byte[] classBytes, Remapper remapper) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0); // pure rename, no frame/maxs recomputation
        cr.accept(new ClassRemapper(cw, remapper), 0);
        return cw.toByteArray();
    }

    /** A jar name / mod id reduced to a package-legal segment. */
    private static String sanitize(String key) {
        String s = key.replaceAll("\\.jar$", "").replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? "mod" : s;
    }
}
