/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.RetromodConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Every input besides the source jar and the Retromod build that changes a translation.
 *
 * <p>The cache key is the source jar's SHA-256 and the folder stamp covers the Retromod build
 * and host. That alone would keep serving an old translation after the player turns polyfills
 * off, after AutoFix saves a rule meant to repair the next launch, or after the loader is
 * updated under the same Minecraft version. Folding these values into the folder stamp wipes
 * the folder whenever one of them changes.
 *
 * <p>Only values that reach a transform belong here. {@code verify_transforms} and
 * {@code debug} only report on a finished jar, and {@code force_translate_complex} only gates
 * the in-game picker, so changing them must not throw the cache away.
 */
public final class TransformContext {

    /** Config keys that change translated output on at least one loader. */
    static final String[] OUTPUT_CONFIG_KEYS = {"polyfills_enabled", "transform_mixins"};

    /** Same flag the loader entry points read before applying saved AutoFix rules. */
    static final String AUTOFIX_PROPERTY = "retromod.autoFix";

    /** Relative to the game directory, like {@code AutoFixEngine}'s file. */
    static final Path AUTOFIX_FILE = Path.of("config", "retromod", "auto-fixes.json");

    private static final long MAX_AUTOFIX_BYTES = 2L * 1024 * 1024;

    private final Map<String, String> values;

    private TransformContext(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    /** Starts an empty context, for callers that add their own inputs. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Collects the standard inputs for one game directory.
     *
     * @param loader      {@code forge}, {@code neoforge}, {@code fabric}, or {@code quilt}
     * @param loaderBuild the loader's own version (for example {@code 47.4.26}), or empty
     */
    public static TransformContext forGame(Path gameDir, String loader, String loaderBuild,
            Properties systemProperties) {
        Objects.requireNonNull(gameDir, "gameDir");
        Builder builder = builder();
        Path config = RetromodConfig.configPath(gameDir);
        for (String key : OUTPUT_CONFIG_KEYS) {
            // Both keys default to true in DEFAULT_CONFIG and in every reader.
            boolean value = RetromodConfig.findBoolean(config, key).orElse(Boolean.TRUE);
            builder.put("config." + key, Boolean.toString(value));
        }
        builder.put("loader", loader == null ? "" : loader.toLowerCase(Locale.ROOT));
        builder.put("loader.build", loaderBuild == null ? "" : loaderBuild);

        boolean autoFixApplies = autoFixApplies(loader, systemProperties);
        builder.put("autofix.applies", Boolean.toString(autoFixApplies));
        if (autoFixApplies) {
            builder.put("autofix.rules", digestOrMarker(gameDir.resolve(AUTOFIX_FILE)));
        }
        return builder.build();
    }

    /**
     * Fabric's pre-launch loads saved AutoFix rules unconditionally, while Forge and NeoForge
     * load them only under {@code -Dretromod.autoFix=true}. Mirror that, or a Forge cache would
     * be thrown away whenever an unused rule file changes.
     */
    static boolean autoFixApplies(String loader, Properties systemProperties) {
        String normalized = loader == null ? "" : loader.toLowerCase(Locale.ROOT);
        if (normalized.equals("fabric") || normalized.equals("quilt")) return true;
        String flag = systemProperties == null ? null
                : systemProperties.getProperty(AUTOFIX_PROPERTY);
        return flag != null && flag.trim().equalsIgnoreCase("true");
    }

    private static String digestOrMarker(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "absent";
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_AUTOFIX_BYTES) return "oversized";
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            // An unreadable file still has to change the key, or a rule that later becomes
            // readable would be ignored by a cache built while it was not.
            return "unreadable";
        }
    }

    /** The recorded inputs, sorted by key. */
    public Map<String, String> values() {
        return values;
    }

    /**
     * A short stable digest of the inputs. Sixteen hex characters keep the folder stamp
     * readable and are plenty to tell launches of one game directory apart.
     */
    public String hash() {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Builds a context from explicit values. Keys and values must be single-line text. */
    public static final class Builder {
        private final Map<String, String> values = new TreeMap<>();

        private Builder() {}

        public Builder put(String key, String value) {
            requireSingleLine(key, "key");
            requireSingleLine(value, "value");
            if (key.isEmpty() || key.indexOf('=') >= 0) {
                throw new IllegalArgumentException("Transform context key is invalid: " + key);
            }
            values.put(key, value);
            return this;
        }

        public TransformContext build() {
            return new TransformContext(values);
        }

        private static void requireSingleLine(String text, String what) {
            Objects.requireNonNull(text, what);
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Transform context " + what
                        + " must be one line: " + text);
            }
        }
    }
}
