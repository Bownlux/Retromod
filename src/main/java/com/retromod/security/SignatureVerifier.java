/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Checks whether the running build's bytecode still matches the published release hash. */
public final class SignatureVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /**
     * SHA-256 (uppercase hex) of Retromod's own classes: every {@code com/retromod/}
     * {@code .class} entry except this verifier (it carries the value) and the relocated
     * {@code com/retromod/shaded/} deps (build-all.sh strips/varies them per loader, so
     * hashing only our own code keeps one value valid across every full dist jar; lite differs).
     *
     * <p>Empty in dev/source builds: status is then {@link Status#UNKNOWN} and the computed
     * hash is logged so a release build can embed it. See {@code docs/authenticity.md}.
     */
    private static final String EXPECTED_SELF_HASH = "2F7C76A1F3481C8DCB0DB624AAFBF718A96BE780A19FD3B7BF6E191548524776";

    /** This class's own jar entry, excluded from the hash (it carries the hash). */
    private static final String SELF_ENTRY = "com/retromod/security/SignatureVerifier.class";

    private static final String EXPECTED_IMPL_TITLE = "Retromod";

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
            Manifest manifest = jar.getManifest();
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

    /**
     * SHA-256 over Retromod's own {@code com/retromod/} classes, sorted by name, excluding
     * this class ({@link #SELF_ENTRY}) and the relocated {@code com/retromod/shaded/} deps,
     * hashing each entry's name (UTF-8) then its bytes. Uppercase hex, or {@code null} if none.
     * Package-private so release tooling and tests can compute the same value.
     */
    static String computeSelfHash(JarFile jar) throws Exception {
        List<JarEntry> classes = new ArrayList<>();
        Enumeration<JarEntry> e = jar.entries();
        while (e.hasMoreElements()) {
            JarEntry je = e.nextElement();
            if (je.isDirectory()) continue;
            String n = je.getName();
            if (!n.endsWith(".class")) continue;
            // only our own classes: skip relocated shaded deps and this verifier
            if (!n.startsWith("com/retromod/")) continue;
            if (n.startsWith("com/retromod/shaded/")) continue;
            if (n.equals(SELF_ENTRY)) continue;
            classes.add(je);
        }
        if (classes.isEmpty()) return null;
        classes.sort(Comparator.comparing(JarEntry::getName));

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        for (JarEntry je : classes) {
            md.update(je.getName().getBytes(StandardCharsets.UTF_8));
            try (InputStream is = jar.getInputStream(je)) {
                int r;
                while ((r = is.read(buf)) != -1) md.update(buf, 0, r);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /** Locate the JAR this class is loaded from, or null if running from a directory. */
    private static Path findOwnJar() {
        try {
            var codeSource = SignatureVerifier.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) return null;
            var url = codeSource.getLocation();
            if (url == null) return null;

            String path = url.getPath();
            int bang = path.indexOf('!');
            if (bang >= 0) path = path.substring(0, bang);
            if (path.startsWith("file:")) path = path.substring("file:".length());

            Path p = Path.of(path);
            if (Files.isDirectory(p)) return null; // classes dir, not a jar
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // A fork can change this text (MIT). The deterrent is social: a build silently missing it is the red flag.
    private static final String FORK_NOTICE_TEMPLATE =
        "You are using a %s Fork. If this was advertised as the official %s, "
        + "this is NOT official! Check github.com/Bownlux/%s for the real thing.";

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
        LOGGER.warn("[Retromod] {}", forkNotice());
    }

    private static void logResult(VerificationResult result) {
        switch (result.status()) {
            case VERIFIED -> LOGGER.info("[Retromod] ✓ Authenticity: VERIFIED - {}",
                    result.detail());
            case MODIFIED -> {
                LOGGER.warn("[Retromod] ⚠ Authenticity: MODIFIED build - {}",
                        result.detail());
                LOGGER.warn("[Retromod] {}", forkNotice());
            }
            case IMPOSTOR -> {
                LOGGER.error("[Retromod] ✗ Authenticity: IMPOSTOR - {}",
                        result.detail());
                LOGGER.error("[Retromod] {}", forkNotice());
            }
            case UNKNOWN -> {
                LOGGER.debug("[Retromod] Authenticity: unknown - {}", result.detail());
                // dev build with no embedded hash: log the computed value to embed in EXPECTED_SELF_HASH
                if (result.selfHash() != null
                        && "Self-hash not embedded in this build".equals(result.detail())) {
                    LOGGER.info("[Retromod] Computed self-hash (embed in EXPECTED_SELF_HASH "
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
