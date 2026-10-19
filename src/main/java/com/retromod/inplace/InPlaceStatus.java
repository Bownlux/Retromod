/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.util.JsonSecurity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What the discovery pass did in one launch: which originals it kept away from the loader,
 * which translated copies it served, and which sources still wait for translation.
 *
 * <p>The discovery pass runs in its own class loader before any mod is constructed, so static
 * state cannot carry this to Retromod's entry point. It travels as {@code status.json}, whose
 * path the bootstrap publishes in the {@link #STATUS_PROPERTY} system property.
 *
 * @param mode       {@link #MODE_IN_PLACE}, {@link #MODE_REWRITE}, or {@link #MODE_UNAVAILABLE}
 * @param loader     cache loader folder name, such as {@code forge}
 * @param minecraft  host Minecraft version the pass translated for
 * @param gameDir    the game directory with links resolved
 * @param cacheState the cache state the pass ran with
 * @param served     translated copies handed to the loader
 * @param pending    sources waiting for the entry point to translate them
 * @param failed     sources whose translation failed and is not retried yet
 * @param superseded sources left out because another jar provides the same mod
 * @param hidden     every original kept away from the loader, as absolute paths
 */
public record InPlaceStatus(String mode, String loader, String minecraft, Path gameDir,
        String cacheState, List<Served> served, List<Source> pending, List<Source> failed,
        List<Source> superseded, List<String> hidden) {

    /** System property naming the status file of this launch. */
    public static final String STATUS_PROPERTY = "retromod.inplace.status";

    /** System property with the mode the discovery pass ran in. */
    public static final String MODE_PROPERTY = "retromod.inplace.mode";

    /** Translated copies live in the cache and {@code mods/} is never written. */
    public static final String MODE_IN_PLACE = "in-place";

    /** {@code write_translated_jars} is true: the entry point runs the rewrite flow. */
    public static final String MODE_REWRITE = "rewrite";

    /** In-place mode is configured, but the cache could not be used this launch. */
    public static final String MODE_UNAVAILABLE = "unavailable";

    static final int FORMAT = 1;
    private static final long MAX_STATUS_BYTES = 4L * 1024 * 1024;

    /** A translated copy the loader received in place of its original. */
    public record Served(String source, String location, String sha256, String file) {
        public Served {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * An original the pass saw.
     *
     * @param hidden whether the loader was kept from loading it this launch
     * @param reason one line for logs, or null
     */
    public record Source(String source, String location, String sha256, boolean hidden,
            String reason) {
        public Source {
            Objects.requireNonNull(source, "source");
        }
    }

    public InPlaceStatus {
        served = List.copyOf(served);
        pending = List.copyOf(pending);
        failed = List.copyOf(failed);
        superseded = List.copyOf(superseded);
        hidden = List.copyOf(hidden);
    }

    /** True when the in-game entry point must leave {@code mods/} alone. */
    public boolean inPlace() {
        return MODE_IN_PLACE.equals(mode);
    }

    /** Absolute paths of hidden originals, with and without links resolved. */
    public Set<String> hiddenPaths() {
        Set<String> paths = new HashSet<>();
        for (String path : hidden) {
            paths.add(path);
            paths.add(resolved(Path.of(path)));
        }
        return paths;
    }

    /** True when {@code jar} is an original the discovery pass kept away from the loader. */
    public boolean isHidden(Path jar) {
        if (jar == null) return false;
        Set<String> paths = hiddenPaths();
        return paths.contains(jar.toAbsolutePath().normalize().toString())
                || paths.contains(resolved(jar));
    }

    static String resolved(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    /** Writes the status through a sibling temporary file and an atomic rename. */
    public void write(Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("mode", mode);
        root.addProperty("loader", loader);
        root.addProperty("minecraft", minecraft);
        root.addProperty("gameDir", gameDir == null ? null : gameDir.toString());
        root.addProperty("cacheState", cacheState);
        JsonArray servedRows = new JsonArray();
        for (Served row : served) {
            JsonObject value = new JsonObject();
            value.addProperty("source", row.source());
            value.addProperty("location", row.location());
            value.addProperty("sha256", row.sha256());
            value.addProperty("file", row.file());
            servedRows.add(value);
        }
        root.add("served", servedRows);
        root.add("pending", sourceRows(pending));
        root.add("failed", sourceRows(failed));
        root.add("superseded", sourceRows(superseded));
        JsonArray hiddenRows = new JsonArray();
        hidden.forEach(hiddenRows::add);
        root.add("hidden", hiddenRows);

        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls()
                .create().toJson(root) + "\n";
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(file)
                || (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Status file is not a regular file: " + file);
        }
        Path staged = Files.createTempFile(parent, ".status-", ".tmp");
        try {
            Files.writeString(staged, json, StandardCharsets.UTF_8);
            try {
                Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static JsonArray sourceRows(List<Source> sources) {
        JsonArray rows = new JsonArray();
        for (Source row : sources) {
            JsonObject value = new JsonObject();
            value.addProperty("source", row.source());
            value.addProperty("location", row.location());
            value.addProperty("sha256", row.sha256());
            value.addProperty("hidden", row.hidden());
            value.addProperty("reason", row.reason());
            rows.add(value);
        }
        return rows;
    }

    /** Reads a status file. Throws when it is missing, oversized, or not a status file. */
    public static InPlaceStatus read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("No status file at " + file);
        }
        String json = JsonSecurity.readUtf8(file, MAX_STATUS_BYTES,
                JsonSecurity.DEFAULT_MAX_DEPTH, "in-place status");
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IOException("Status file is not a JSON object");
        JsonObject root = parsed.getAsJsonObject();
        if (intValue(root, "format") != FORMAT) {
            throw new IOException("Status file has an unknown format");
        }
        List<Served> served = new ArrayList<>();
        for (JsonElement element : array(root, "served")) {
            JsonObject row = object(element);
            served.add(new Served(string(row, "source"), string(row, "location"),
                    string(row, "sha256"), string(row, "file")));
        }
        List<String> hidden = new ArrayList<>();
        for (JsonElement element : array(root, "hidden")) {
            if (element.isJsonPrimitive()) hidden.add(element.getAsString());
        }
        String gameDir = string(root, "gameDir");
        return new InPlaceStatus(string(root, "mode"), string(root, "loader"),
                string(root, "minecraft"), gameDir == null ? null : Path.of(gameDir),
                string(root, "cacheState"), served, sources(root, "pending"),
                sources(root, "failed"), sources(root, "superseded"), hidden);
    }

    /** The status this launch's bootstrap published, or null when there is none. */
    public static InPlaceStatus fromSystemProperty() throws IOException {
        String path = System.getProperty(STATUS_PROPERTY);
        if (path == null || path.isBlank()) return null;
        return read(Path.of(path));
    }

    private static List<Source> sources(JsonObject root, String key) throws IOException {
        List<Source> rows = new ArrayList<>();
        for (JsonElement element : array(root, key)) {
            JsonObject row = object(element);
            JsonElement hidden = row.get("hidden");
            rows.add(new Source(string(row, "source"), string(row, "location"),
                    string(row, "sha256"),
                    hidden != null && hidden.isJsonPrimitive() && hidden.getAsBoolean(),
                    string(row, "reason")));
        }
        return rows;
    }

    private static JsonArray array(JsonObject root, String key) throws IOException {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) return new JsonArray();
        if (!value.isJsonArray()) throw new IOException("Status field " + key + " is not a list");
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonElement element) throws IOException {
        if (!element.isJsonObject()) throw new IOException("Status row is not an object");
        return element.getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null : value.getAsString();
    }

    private static int intValue(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        try {
            return value == null ? -1 : value.getAsInt();
        } catch (RuntimeException e) {
            throw new IOException("Status field " + key + " is not a number");
        }
    }
}
