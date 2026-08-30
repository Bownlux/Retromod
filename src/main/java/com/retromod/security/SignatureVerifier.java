/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.security;

import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Checks whether the running build's bytecode still matches the published release hash. */
public final class SignatureVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /**
     * SHA-256 (uppercase hex) of the executable release surface. This covers every class except
     * loader-provided ASM, loader-variant annotation stubs, and this verifier, plus ServiceLoader
     * descriptors and Retromod's transformation data. Those exclusions keep one value valid
     * across every loader variant without leaving an unhashed provider injection path.
     *
     * <p>Empty in dev/source builds: status is then {@link Status#UNKNOWN} and the computed
     * hash is logged so a release build can embed it. See {@code docs/authenticity.md}.
     */
    private static final String EXPECTED_SELF_HASH = "";

    /** This class's own jar entry, excluded from the hash (it carries the hash). */
    private static final String SELF_ENTRY = "com/retromod/security/SignatureVerifier.class";

    private static final String EXPECTED_IMPL_TITLE = "Retromod";
    private static final int MAX_JAR_ENTRIES = 100_000;
    private static final long MAX_MANIFEST_BYTES = 1024L * 1024;

    private static final byte[] HASH_DOMAIN =
            "RETROMOD-SELF-HASH\u0000V2".getBytes(StandardCharsets.UTF_8);

    private static volatile VerificationResult cachedResult;

    private SignatureVerifier() {}

    /** Verify the running build and log the result. Called once at init. */
    public static VerificationResult verifyAndLog() {
        VerificationResult result = verify();
        logResult(result);
        return result;
    }

    /** Verify the running build. Cached after the first call. */
    public static VerificationResult verify() {
        VerificationResult cached = cachedResult;
        if (cached != null) return cached;
        synchronized (SignatureVerifier.class) {
            if (cachedResult != null) return cachedResult;
            cachedResult = doVerify();
            return cachedResult;
        }
    }

    private static VerificationResult doVerify() {
        Path jarPath = findOwnJar();
        if (jarPath == null || !Files.exists(jarPath)) {
            return new VerificationResult(Status.UNKNOWN,
                    "Not running from a JAR (dev/source build)", null, null);
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // manifest must identify this as Retromod
            Manifest manifest = readBoundedManifest(jar);
            String implTitle = (manifest != null)
                    ? manifest.getMainAttributes().getValue("Implementation-Title")
                    : null;
            if (implTitle != null && !EXPECTED_IMPL_TITLE.equalsIgnoreCase(implTitle)) {
                return new VerificationResult(Status.IMPOSTOR,
                        "JAR claims Implementation-Title=" + implTitle + " (not Retromod)",
                        jarPath, null);
            }

            String actual = computeSelfHash(jar);
            if (actual == null) {
                return new VerificationResult(Status.UNKNOWN, "No class entries to hash",
                        jarPath, null);
            }

            String expected = EXPECTED_SELF_HASH.trim();
            if (expected.isEmpty()) {
                // dev/source build: logResult surfaces the computed value to embed
                return new VerificationResult(Status.UNKNOWN,
                        "Self-hash not embedded in this build", jarPath, actual);
            }
            if (actual.equalsIgnoreCase(expected)) {
                return new VerificationResult(Status.VERIFIED,
                        "Bytecode matches the published release hash", jarPath, actual);
            }
            return new VerificationResult(Status.MODIFIED,
                    "Bytecode differs from the published release hash", jarPath, actual);

        } catch (Exception e) {
            return new VerificationResult(Status.UNKNOWN,
                    "Could not verify: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    jarPath, null);
        }
    }

    static Manifest readBoundedManifest(JarFile jar) throws IOException {
        JarEntry manifestEntry = null;
        Set<String> canonicalNames = new HashSet<>();
        int entryCount = 0;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (++entryCount > MAX_JAR_ENTRIES) {
                throw new IOException("JAR has too many entries for integrity verification");
            }
            String canonicalName = ZipSecurity.canonicalEntryName(entry.getName());
            if (!canonicalNames.add(canonicalName)) {
                throw new IOException("JAR contains a duplicate normalized entry: "
                        + entry.getName());
            }
            if ("META-INF/MANIFEST.MF".equalsIgnoreCase(canonicalName)) {
                if (manifestEntry != null) {
                    throw new IOException("JAR contains multiple manifest entries");
                }
                manifestEntry = entry;
            }
        }
        if (manifestEntry == null) return null;
        long declaredSize = manifestEntry.getSize();
        if (declaredSize > MAX_MANIFEST_BYTES) {
            throw new IOException("JAR manifest exceeds the verification size limit");
        }
        try (InputStream input = jar.getInputStream(manifestEntry)) {
            byte[] bytes = ZipSecurity.safeReadAllBytes(input, MAX_MANIFEST_BYTES);
            return new Manifest(new ByteArrayInputStream(bytes));
        }
    }

    /**
     * SHA-256 over the executable release surface, sorted by name. Each name and entry body is
     * length-framed so different archive structures cannot produce the same byte stream.
     * Uppercase hex, or {@code null} if no covered entry exists.
     * Package-private so release tooling and tests can compute the same value.
     */
    static String computeSelfHash(JarFile jar) throws Exception {
        List<JarEntry> hashedEntries = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        Enumeration<JarEntry> e = jar.entries();
        while (e.hasMoreElements()) {
            JarEntry je = e.nextElement();
            if (je.isDirectory()) continue;
            String n = je.getName();
            if (!seenNames.add(n)) {
                throw new SecurityException("Duplicate JAR entry: " + n);
            }
            if (isHashedEntry(n)) hashedEntries.add(je);
        }
        if (hashedEntries.isEmpty()) return null;
        hashedEntries.sort(Comparator.comparing(JarEntry::getName));

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(HASH_DOMAIN);
        byte[] buf = new byte[8192];
        for (JarEntry je : hashedEntries) {
            byte[] name = je.getName().getBytes(StandardCharsets.UTF_8);
            md.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
            md.update(name);
            long expectedSize = je.getSize();
            if (expectedSize < 0) {
                throw new SecurityException("JAR entry has no declared size: " + je.getName());
            }
            md.update(ByteBuffer.allocate(Long.BYTES).putLong(expectedSize).array());
            long actualSize = 0;
            try (InputStream is = jar.getInputStream(je)) {
                int r;
                while ((r = is.read(buf)) != -1) {
                    actualSize += r;
                    md.update(buf, 0, r);
                }
            }
            if (actualSize != expectedSize) {
                throw new SecurityException("JAR entry size changed while hashing: "
                        + je.getName());
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static boolean isHashedEntry(String name) {
        if (SELF_ENTRY.equals(name)) return false;
        if (name.endsWith(".class")) {
            // Loader distributions remove these where the host supplies them. They are the only
            // class differences between the full release variants.
            return !name.startsWith("org/objectweb/asm/")
                    && !name.startsWith("javax/annotation/");
        }
        return name.startsWith("META-INF/services/")
                || name.startsWith("retromod/")
                || name.equals("intermediary-to-mojang.tsv")
                || name.equals("mojang-class-moves-26.1.tsv")
                || name.equals("retromod.mixins.json");
    }

    /** Locate the JAR this class is loaded from, or null if running from a directory. */
    private static Path findOwnJar() {
        try {
            var codeSource = SignatureVerifier.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) return null;
            return jarPathFromCodeSource(codeSource.getLocation());
        } catch (Exception e) {
            return null;
        }
    }

    static Path jarPathFromCodeSource(URL url) {
        if (url == null) return null;
        try {
            URI uri = url.toURI();
            if ("jar".equalsIgnoreCase(uri.getScheme())) {
                String nested = uri.getRawSchemeSpecificPart();
                int bang = nested.indexOf('!');
                if (bang >= 0) nested = nested.substring(0, bang);
                uri = URI.create(nested);
            }
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;

            Path path = Path.of(uri);
            return Files.isDirectory(path) ? null : path;
        } catch (Exception e) {
            return null;
        }
    }

    // This is an informational notice, not a security boundary.
    private static final String FORK_NOTICE_TEMPLATE =
        "This appears to be a modified %s build. If you expected the published %s, "
        + "compare it with the files at github.com/Bownlux/%s.";

    private static String forkNotice() {
        return String.format(FORK_NOTICE_TEMPLATE,
                EXPECTED_IMPL_TITLE, EXPECTED_IMPL_TITLE, EXPECTED_IMPL_TITLE);
    }

    /**
     * @deprecated {@link #logResult} now emits the notice automatically when the status
     *     isn't VERIFIED. Kept for external callers.
     */
    @Deprecated
    public static void logForkNotice() {
        LOGGER.warn("{}", forkNotice());
    }

    private static void logResult(VerificationResult result) {
        switch (result.status()) {
            case VERIFIED -> LOGGER.info("Build integrity check passed: {}",
                    result.detail());
            case MODIFIED -> {
                LOGGER.warn("This build differs from its embedded class hash: {}",
                        result.detail());
                LOGGER.warn("{}", forkNotice());
            }
            case IMPOSTOR -> {
                LOGGER.error("This jar does not identify itself as Retromod: {}",
                        result.detail());
                LOGGER.error("{}", forkNotice());
            }
            case UNKNOWN -> {
                LOGGER.debug("Build integrity was not checked: {}", result.detail());
                if (result.selfHash() != null
                        && "Self-hash not embedded in this build".equals(result.detail())) {
                    LOGGER.info("Computed self-hash (embed in EXPECTED_SELF_HASH "
                            + "for release): {}", result.selfHash());
                }
            }
        }
    }

    public enum Status {
        /** Bytecode matches the embedded release hash; unchanged since publish. */
        VERIFIED,
        /** Bytecode differs from the release hash: a fork, repack, or corruption. */
        MODIFIED,
        /** Manifest says this JAR isn't Retromod at all. */
        IMPOSTOR,
        /** Could not determine: dev/source build, no embedded hash, or unreadable. */
        UNKNOWN,
    }

    public record VerificationResult(Status status, String detail,
                                     Path jarPath, String selfHash) {

        /** Does the bytecode match the published release hash? */
        public boolean isVerified() { return status == Status.VERIFIED; }

        /**
         * @deprecated a hash match doesn't prove provenance (no secret key), so the status is
         *     {@link Status#VERIFIED}, not "official". Use {@link #isVerified()}.
         */
        @Deprecated
        public boolean isOfficial() { return isVerified(); }

        /** Should the user be nudged that this might not be the genuine build? */
        public boolean isSuspicious() {
            return status == Status.MODIFIED || status == Status.IMPOSTOR;
        }

        public String displayLine() {
            return switch (status) {
                case VERIFIED -> "§aVerified build§r";
                case MODIFIED -> "§eModified / unofficial build§r";
                case IMPOSTOR -> "§cNot Retromod (manifest mismatch)§r";
                case UNKNOWN  -> "§7Authenticity unknown§r";
            };
        }
    }
}
